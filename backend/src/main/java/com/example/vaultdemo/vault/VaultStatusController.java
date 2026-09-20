package com.example.vaultdemo.vault;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vault")
public class VaultStatusController {

    private final VaultDatabaseCredentialManager credentialManager;

    public VaultStatusController(VaultDatabaseCredentialManager credentialManager) {
        this.credentialManager = credentialManager;
    }

    @GetMapping("/status")
    public VaultStatusResponse status() {
        return credentialManager.status();
    }
}
