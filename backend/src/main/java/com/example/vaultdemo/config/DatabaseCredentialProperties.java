package com.example.vaultdemo.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Configuration for database credentials projected from a Kubernetes Secret. */
@Validated
@ConfigurationProperties("app.database-credential")
public record DatabaseCredentialProperties(
        @DefaultValue("/var/run/secrets/database-credentials/username") @NotBlank String usernameFile,
        @DefaultValue("/var/run/secrets/database-credentials/password") @NotBlank String passwordFile,
        @DefaultValue("/var/run/secrets/database-credentials/managed_by") @NotBlank String providerFile,
        @DefaultValue("30s") Duration startupTimeout,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("67") @Min(0) @Max(90) int renewalPercent,
        @DefaultValue("2m") Duration maxCredentialTtl,
        @DefaultValue("todo-database-credentials") @NotBlank String destinationSecretName) {
}
