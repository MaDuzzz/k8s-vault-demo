package com.example.vaultdemo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretFingerprintTest {

    @Test
    void fingerprintsAreStableAndDoNotContainTheSecret() {
        String password = "vault-generated-secret";

        String fingerprint = SecretFingerprint.password(password);

        assertThat(fingerprint).startsWith("sha256:").hasSize(19);
        assertThat(fingerprint).doesNotContain(password);
        assertThat(fingerprint).isEqualTo(SecretFingerprint.password(password));
    }

    @Test
    void leaseIdsAreMasked() {
        String leaseId = "database/creds/todo-app/abc123";

        assertThat(SecretFingerprint.leaseId(leaseId))
                .startsWith("lease-sha256:")
                .doesNotContain("abc123");
        assertThat(SecretFingerprint.leaseId(null)).isNull();
    }
}
