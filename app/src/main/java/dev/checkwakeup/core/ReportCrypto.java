package dev.checkwakeup.core;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/** RFC 7516 compact JWE; never puts business fields in URLs or headers. */
public final class ReportCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private ReportCrypto() {}

    public static PublicKey publicKey(String pem) throws java.security.GeneralSecurityException {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        PublicKey key = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        if (!(key instanceof RSAPublicKey)
                || ((RSAPublicKey) key).getModulus().bitLength() < 3072) {
            throw new java.security.InvalidKeyException("RSA public key must be at least 3072 bits");
        }
        return key;
    }

    public static String fingerprint(PublicKey key) throws java.security.GeneralSecurityException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        StringBuilder text = new StringBuilder();
        for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    public static String encrypt(byte[] plaintext, PublicKey key, String kid)
            throws java.security.GeneralSecurityException {
        if (!kid.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid kid");
        if (plaintext.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Payload too large");
        String header = "{\"alg\":\"RSA-OAEP-256\",\"enc\":\"A256GCM\",\"kid\":\""
                + kid + "\",\"typ\":\"checkwakeup+jwe\",\"cty\":\"application/json\"}";
        byte[] cek = new byte[32];
        byte[] iv = new byte[12];
        RANDOM.nextBytes(cek);
        RANDOM.nextBytes(iv);
        String protectedPart = b64(header.getBytes(StandardCharsets.UTF_8));
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, key, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] encryptedKey = rsa.doFinal(cek);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, iv));
        aes.updateAAD(protectedPart.getBytes(StandardCharsets.US_ASCII));
        byte[] encrypted = aes.doFinal(plaintext);
        java.util.Arrays.fill(cek, (byte) 0);
        int size = encrypted.length - 16;
        return protectedPart + "." + b64(encryptedKey) + "." + b64(iv) + "."
                + b64(java.util.Arrays.copyOf(encrypted, size)) + "."
                + b64(java.util.Arrays.copyOfRange(encrypted, size, encrypted.length));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
