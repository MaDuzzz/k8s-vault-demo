package com.example.vaultdemo.vault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.example.vaultdemo.config.DatabaseCredentialProperties;
import com.example.vaultdemo.config.DatabaseProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.stereotype.Component;

/**
 * Consumes a database credential projected from a Kubernetes Secret.
 * Kubernetes projects Secret updates through an atomic symlink swap, so the
 * application can hot-swap its connection pool without a pod restart.
 */
@Component
public class VaultDatabaseCredentialManager {

    private static final Logger log = LoggerFactory.getLogger(VaultDatabaseCredentialManager.class);

    private final DatabaseCredentialProperties properties;
    private final RotatingDataSource dataSource;
    private final CountDownLatch firstCredential = new CountDownLatch(1);
    private final AtomicReference<CredentialSnapshot> snapshot =
            new AtomicReference<>(CredentialSnapshot.starting());
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "database-secret-watcher");
        thread.setDaemon(true);
        return thread;
    });

    public VaultDatabaseCredentialManager(
            DatabaseCredentialProperties properties, DatabaseProperties databaseProperties) {
        this.properties = properties;
        this.dataSource = new RotatingDataSource(databaseProperties);
    }

    public RotatingDataSource dataSource() {
        return dataSource;
    }

    public VaultStatusResponse status() {
        CredentialSnapshot current = snapshot.get();
        boolean managedByVso = "vso".equalsIgnoreCase(current.provider());
        if (current.acquiredAt() == null) {
            return new VaultStatusResponse(
                    current.state(), null, null, null, null, null,
                    null, null, null, null, null, 0, 0,
                    current.eventType(), current.message(), credentialSource(current.provider()),
                    managedByVso ? properties.renewalPercent() : null, managedByVso);
        }

        if (!managedByVso) {
            return new VaultStatusResponse(
                    current.state(),
                    current.username(),
                    current.passwordFingerprint(),
                    null,
                    null,
                    false,
                    current.acquiredAt(),
                    null,
                    null,
                    null,
                    null,
                    0,
                    current.generation(),
                    current.eventType(),
                    current.message(),
                    credentialSource(current.provider()),
                    null,
                    false);
        }

        Instant now = Instant.now();
        Duration renewalInterval = renewalInterval();
        long estimatedRenewals = 0;
        Instant lastRenewedAt = null;
        Instant nextRenewalAt = null;
        Instant expiresAt = current.acquiredAt().plus(properties.leaseDuration());

        long renewalMillis = renewalInterval.toMillis();
        if (renewalMillis > 0) {
            long elapsedMillis = Math.max(0, Duration.between(current.acquiredAt(), now).toMillis());
            estimatedRenewals = elapsedMillis / renewalMillis;
            lastRenewedAt = estimatedRenewals == 0
                    ? null
                    : current.acquiredAt().plus(renewalInterval.multipliedBy(estimatedRenewals));
            nextRenewalAt = current.acquiredAt().plus(renewalInterval.multipliedBy(estimatedRenewals + 1));
            expiresAt = (lastRenewedAt == null ? current.acquiredAt() : lastRenewedAt)
                    .plus(properties.leaseDuration());
        }

        Instant estimatedRotationAt = current.acquiredAt().plus(properties.maxCredentialTtl());
        if (nextRenewalAt != null && !nextRenewalAt.isBefore(estimatedRotationAt)) {
            nextRenewalAt = null;
        }

        return new VaultStatusResponse(
                current.state(),
                current.username(),
                current.passwordFingerprint(),
                null,
                properties.leaseDuration().toSeconds(),
                true,
                current.acquiredAt(),
                expiresAt,
                nextRenewalAt,
                estimatedRotationAt,
                lastRenewedAt,
                estimatedRenewals,
                current.generation(),
                current.eventType(),
                current.message(),
                credentialSource(current.provider()),
                properties.renewalPercent(),
                true);
    }

    public boolean isReady() {
        return dataSource.isReady() && snapshot.get().state() == ConnectionState.CONNECTED;
    }

    public void startAndAwaitInitialCredential() {
        if (started.compareAndSet(false, true)) {
            reloadCredential();
            watcher.scheduleWithFixedDelay(
                    this::reloadCredential,
                    properties.pollInterval().toMillis(),
                    properties.pollInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        try {
            if (!firstCredential.await(properties.startupTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                CredentialSnapshot current = snapshot.get();
                throw new BeanInitializationException(
                        "Timed out waiting for the database credential Secret; last event: "
                                + current.eventType());
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BeanInitializationException(
                    "Interrupted while waiting for the database credential Secret", exception);
        }
    }

    private void reloadCredential() {
        if (stopping.get()) {
            return;
        }

        try {
            String username = Files.readString(Path.of(properties.usernameFile())).trim();
            String password = Files.readString(Path.of(properties.passwordFile()));
            String provider = readProvider();
            if (username.isBlank() || password.isEmpty()) {
                throw new IOException("The projected database credential files are empty");
            }

            String passwordFingerprint = SecretFingerprint.password(password);
            CredentialSnapshot current = snapshot.get();
            if (username.equals(current.username())
                    && passwordFingerprint.equals(current.passwordFingerprint())
                    && provider.equals(current.provider())) {
                if (current.state() == ConnectionState.DEGRADED) {
                    snapshot.set(current.restored());
                }
                return;
            }

            Duration poolCredentialLifetime = "vso".equalsIgnoreCase(provider)
                    ? properties.leaseDuration()
                    : Duration.ofDays(1);
            dataSource.rotate(username, password, poolCredentialLifetime);
            Instant now = Instant.now();
            long generation = current.generation() + 1;
            boolean rotated = current.username() != null;
            snapshot.set(new CredentialSnapshot(
                    ConnectionState.CONNECTED,
                    username,
                    passwordFingerprint,
                    provider,
                    now,
                    generation,
                    rotated ? "DATABASE_CREDENTIAL_ROTATED" : "DATABASE_SECRET_LOADED",
                    rotated
                            ? "The projected Kubernetes Secret changed; the database pool was rotated"
                            : "Database credentials were loaded from the projected Kubernetes Secret"));
            firstCredential.countDown();
            log.info("Activated database credential generation {} for username {}", generation, username);
        }
        catch (IOException | SQLException | RuntimeException exception) {
            CredentialSnapshot current = snapshot.get();
            snapshot.set(current.failed(exception.getClass().getSimpleName()));
            log.error("Could not load or activate the database credential: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private String readProvider() throws IOException {
        Path providerPath = Path.of(properties.providerFile());
        if (!Files.exists(providerPath)) {
            return "unknown";
        }
        String provider = Files.readString(providerPath).trim();
        return provider.isBlank() ? "unknown" : provider;
    }

    private Duration renewalInterval() {
        return properties.leaseDuration()
                .multipliedBy(properties.renewalPercent())
                .dividedBy(100);
    }

    private String credentialSource(String provider) {
        if ("vso".equalsIgnoreCase(provider)) {
            return "Vault Secrets Operator → Secret/" + properties.destinationSecretName();
        }
        if ("bootstrap".equalsIgnoreCase(provider)) {
            return "Bootstrap Secret/" + properties.destinationSecretName();
        }
        return "Kubernetes Secret/" + properties.destinationSecretName();
    }

    @PreDestroy
    void shutdown() {
        stopping.set(true);
        watcher.shutdownNow();
        CredentialSnapshot current = snapshot.get();
        snapshot.set(current.stopped());
        dataSource.close();
    }

    private record CredentialSnapshot(
            ConnectionState state,
            String username,
            String passwordFingerprint,
            String provider,
            Instant acquiredAt,
            long generation,
            String eventType,
            String message) {

        static CredentialSnapshot starting() {
            return new CredentialSnapshot(
                    ConnectionState.STARTING, null, null, "unknown", null, 0,
                    "WAITING_FOR_DATABASE_SECRET",
                    "Waiting for the database credential Secret to be projected");
        }

        CredentialSnapshot failed(String reason) {
            return new CredentialSnapshot(
                    ConnectionState.DEGRADED, username, passwordFingerprint, provider, acquiredAt, generation,
                    "DATABASE_SECRET_RELOAD_FAILED",
                    "Could not reload the projected database credential: " + reason);
        }

        CredentialSnapshot restored() {
            return new CredentialSnapshot(
                    ConnectionState.CONNECTED, username, passwordFingerprint, provider, acquiredAt, generation,
                    "DATABASE_SECRET_RESTORED",
                    "The projected credential files are readable again");
        }

        CredentialSnapshot stopped() {
            return new CredentialSnapshot(
                    ConnectionState.STOPPED, username, passwordFingerprint, provider, acquiredAt, generation,
                    "STOPPED", "Database credential watcher is stopping");
        }
    }
}
