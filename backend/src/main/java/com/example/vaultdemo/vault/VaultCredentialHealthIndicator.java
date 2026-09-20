package com.example.vaultdemo.vault;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("databaseCredentials")
public class VaultCredentialHealthIndicator implements HealthIndicator {

    private final VaultDatabaseCredentialManager credentialManager;

    public VaultCredentialHealthIndicator(VaultDatabaseCredentialManager credentialManager) {
        this.credentialManager = credentialManager;
    }

    @Override
    public Health health() {
        VaultStatusResponse status = credentialManager.status();
        Health.Builder builder = credentialManager.isReady() ? Health.up() : Health.outOfService();
        return builder
                .withDetail("state", status.connectionState())
                .withDetail("credentialGeneration", status.credentialGeneration())
                .withDetail("eventType", status.eventType())
                .build();
    }
}
