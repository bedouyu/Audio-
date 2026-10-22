package com.bedouyu.audiorag.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 口令派生与校验：PBKDF2-HMAC-SHA256，逐用户随机盐，不引入额外依赖。
 *
 * <p>旧版本使用无盐 SHA-256（{@link Hashing#sha256(String)}）。存量口令仍能校验成功，
 * 但只在登录那一刻使用一次；随后由调用方写回 PBKDF2 结果，完成透明迁移。
 */
public final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** 生成形如 {@code pbkdf2$210000$<salt>$<hash>} 的可存字符串，约 84 个字符。 */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return PREFIX + "$" + ITERATIONS + "$" + encode(salt) + "$"
                + encode(derive(password, salt, ITERATIONS));
    }

    /** 兼容两种格式：新 PBKDF2 字符串，或历史无盐 SHA-256 十六进制串。 */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null || stored.isEmpty()) {
            return false;
        }
        if (stored.startsWith(PREFIX + "$")) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                if (iterations < 1) {
                    return false;
                }
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                return MessageDigest.isEqual(expected, derive(password, salt, iterations));
            } catch (IllegalArgumentException exception) {
                // 存储值损坏不等同于口令正确，也不抛给调用方。
                return false;
            }
        }
        return MessageDigest.isEqual(
                Hashing.sha256(password).getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
    }

    /** 该口令串是否仍是历史无盐格式，需要在校验通过后升级。 */
    public static boolean needsUpgrade(String stored) {
        return stored == null || !stored.startsWith(PREFIX + "$");
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2 is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
