package com.example.vaultdemo.config;

import javax.sql.DataSource;

import com.example.vaultdemo.vault.VaultDatabaseCredentialManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {

    @Bean(destroyMethod = "")
    @Primary
    DataSource dataSource(VaultDatabaseCredentialManager credentialManager) {
        credentialManager.startAndAwaitInitialCredential();
        return credentialManager.dataSource();
    }
}
