package com.example.vaultdemo;

import com.example.vaultdemo.config.CorsProperties;
import com.example.vaultdemo.config.DatabaseCredentialProperties;
import com.example.vaultdemo.config.DatabaseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        CorsProperties.class,
        DatabaseCredentialProperties.class,
        DatabaseProperties.class,
})
public class VaultTodoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaultTodoApplication.class, args);
    }
}
