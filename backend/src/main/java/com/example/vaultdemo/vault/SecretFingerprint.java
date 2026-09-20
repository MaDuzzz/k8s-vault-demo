package com.example.vaultdemo.vault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class SecretFingerprint {

    private SecretFingerprint() {
    }

    static String password(String value) {
        return "sha256:" + digestPrefix(value, 12);
    }

    static String leaseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "lease-sha256:" + digestPrefix(value, 12);
    }

    private static String digestPrefix(String value, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, length);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
