package com.example.vaultdemo.vault;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.example.vaultdemo.config.DatabaseProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * A stable DataSource facade whose underlying Hikari pool can be replaced whenever
 * the projected Secret changes its username/password pair. New borrowers immediately use the new
 * pool. Existing borrowers get a grace period before the old pool is closed.
 */
public final class RotatingDataSource extends AbstractDataSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RotatingDataSource.class);
    private static final long HIKARI_MIN_MAX_LIFETIME_MILLIS = 30_000;

    private final DatabaseProperties properties;
    private final AtomicReference<HikariDataSource> current = new AtomicReference<>();
    private final Set<HikariDataSource> draining = ConcurrentHashMap.newKeySet();
    private final AtomicLong poolSequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService poolCloser = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vault-datasource-drain");
        thread.setDaemon(true);
        return thread;
    });

    public RotatingDataSource(DatabaseProperties properties) {
        this.properties = properties;
    }

    public synchronized void rotate(String username, String password, Duration leaseDuration) throws SQLException {
        if (closed.get()) {
            throw new SQLException("The rotating DataSource is already closed");
        }

        HikariDataSource candidate = createPool(username, password, leaseDuration);
        try (Connection connection = candidate.getConnection()) {
            int validationSeconds = Math.max(1, Math.toIntExact(properties.validationTimeout().toSeconds()));
            if (!connection.isValid(validationSeconds)) {
                throw new SQLException("The candidate database pool did not pass connection validation");
            }
        }
        catch (SQLException | RuntimeException exception) {
            candidate.close();
            throw exception;
        }

        HikariDataSource previous = current.getAndSet(candidate);
        log.info("Activated database connection pool generation {} for database username {}",
                poolSequence.get(), username);
        if (previous != null) {
            drain(previous);
        }
    }

    public boolean isReady() {
        HikariDataSource dataSource = current.get();
        return !closed.get() && dataSource != null && !dataSource.isClosed();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return requireCurrent().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return requireCurrent().getConnection(username, password);
    }

    private HikariDataSource requireCurrent() throws SQLException {
        HikariDataSource dataSource = current.get();
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLTransientConnectionException("Database credentials are not available yet");
        }
        return dataSource;
    }

    private HikariDataSource createPool(String username, String password, Duration leaseDuration) {
        long sequence = poolSequence.incrementAndGet();
        HikariConfig config = new HikariConfig();
        config.setPoolName("vault-db-" + sequence);
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(properties.maximumPoolSize());
        config.setMinimumIdle(Math.min(properties.minimumIdle(), properties.maximumPoolSize()));
        config.setConnectionTimeout(properties.connectionTimeout().toMillis());
        config.setValidationTimeout(properties.validationTimeout().toMillis());
        config.setInitializationFailTimeout(properties.connectionTimeout().toMillis());
        config.setMaxLifetime(effectiveMaxLifetime(leaseDuration));
        config.setRegisterMbeans(false);
        return new HikariDataSource(config);
    }

    private long effectiveMaxLifetime(Duration leaseDuration) {
        long configured = properties.maxLifetime().toMillis();
        long leaseBound = Math.max(HIKARI_MIN_MAX_LIFETIME_MILLIS, leaseDuration.minusSeconds(5).toMillis());
        return Math.max(HIKARI_MIN_MAX_LIFETIME_MILLIS, Math.min(configured, leaseBound));
    }

    private void drain(HikariDataSource previous) {
        draining.add(previous);
        if (previous.getHikariPoolMXBean() != null) {
            previous.getHikariPoolMXBean().softEvictConnections();
        }
        poolCloser.schedule(() -> {
            try {
                previous.close();
                log.info("Closed drained database connection pool {}", previous.getPoolName());
            }
            finally {
                draining.remove(previous);
            }
        }, properties.drainGracePeriod().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        HikariDataSource active = current.getAndSet(null);
        if (active != null) {
            active.close();
        }
        draining.forEach(HikariDataSource::close);
        draining.clear();
        poolCloser.shutdownNow();
    }
}
