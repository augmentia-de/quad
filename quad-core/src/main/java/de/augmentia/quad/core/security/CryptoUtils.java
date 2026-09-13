package de.augmentia.quad.core.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public final class CryptoUtils {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int KEY_SIZE = 256;
    private static final int IV_SIZE = 12;
    private static final int TAG_LENGTH_BIT = 128;

    /** Generates a random 256-bit key (32 bytes), returned as base64 string. */
    public static String generateKey() {
        byte[] keyBytes = new byte[KEY_SIZE / 8];
        new SecureRandom().nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    /** Decrypts value using key (base64-encoded AES key). Throws IllegalArgumentException on failure. */
    public static String decrypt(String encryptedBase64, String keyBase64) {
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) {
            throw new IllegalArgumentException("Encrypted value must not be null or empty");
        }
        if (keyBase64 == null || keyBase64.isEmpty()) {
            throw new IllegalArgumentException("Key must not be null or empty");
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            byte[] decoded = Base64.getDecoder().decode(encryptedBase64);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[IV_SIZE];
            buffer.get(iv);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Decryption failed: " + e.getMessage(), e);
        }
    }

    /** Encrypts plaintext using key (base64-encoded AES key). Returns base64(iv + ciphertext). */
    public static String encrypt(String plainText, String keyBase64) {
        if (plainText == null) {
            throw new IllegalArgumentException("Plaintext must not be null");
        }
        if (keyBase64 == null || keyBase64.isEmpty()) {
            throw new IllegalArgumentException("Key must not be null or empty");
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            byte[] plaintextBytes = plainText.getBytes(StandardCharsets.UTF_8);

            Cipher cipher = Cipher.getInstance(ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);

            byte[] iv = cipher.getIV();

            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalArgumentException("Encryption failed: " + e.getMessage(), e);
        }
    }

    private CryptoUtils() {
        // Prevent instantiation
    }
}
