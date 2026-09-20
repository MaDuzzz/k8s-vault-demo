package com.example.vaultdemo.vault;

import java.time.Instant;

public record VaultStatusResponse(
        ConnectionState connectionState,
        String currentDbUsername,
        String passwordFingerprint,
        String leaseId,
        Long leaseDurationSeconds,
        Boolean renewable,
        Instant acquiredAt,
        Instant expiresAt,
        Instant nextRenewalAt,
        Instant estimatedRotationAt,
        Instant lastRenewedAt,
        long renewalCount,
        long credentialGeneration,
        String eventType,
        String message,
        String credentialSource,
        Integer renewalPercent,
        boolean timingEstimated) {

    public static VaultStatusResponse starting() {
        return new VaultStatusResponse(
                ConnectionState.STARTING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                "STARTING",
                "Waiting for the database credential Secret",
                "Kubernetes Secret",
                null,
                false);
    }
}
