package com.example.vaultdemo.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.database")
public record DatabaseProperties(
        String url,
        @DefaultValue("localhost") @NotBlank String host,
        @DefaultValue("5432") @Min(1) @Max(65535) int port,
        @DefaultValue("todo") @NotBlank String name,
        @DefaultValue("disable") @NotBlank String sslMode,
        @DefaultValue("10") @Min(1) int maximumPoolSize,
        @DefaultValue("1") @Min(0) int minimumIdle,
        @DefaultValue("5s") Duration connectionTimeout,
        @DefaultValue("3s") Duration validationTimeout,
        @DefaultValue("25m") Duration maxLifetime,
        @DefaultValue("30s") Duration drainGracePeriod) {

    public String jdbcUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        return "jdbc:postgresql://%s:%d/%s?sslmode=%s".formatted(host, port, name, sslMode);
    }
}
