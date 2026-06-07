package com.shelfj.iam.auth;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Argon2 password hashing. Hashes are self-describing (algorithm + params + salt embedded), so verification
 * needs only the stored hash and the candidate password.
 */
@ApplicationScoped
public class Passwords {

    private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);

    // Reasonable interactive cost. Tune iterations/memory/parallelism per deployment.
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 65536;   // 64 MB
    private static final int PARALLELISM = 1;

    /** Hash a password. The char[] is wiped by argon2 after use. */
    public String hash(String rawPassword) {
        char[] chars = rawPassword.toCharArray();
        try {
            return argon2.hash(ITERATIONS, MEMORY_KB, PARALLELISM, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }

    /** Verify a candidate password against a stored Argon2 hash. */
    public boolean verify(String storedHash, String candidate) {
        if (storedHash == null) {
            return false;
        }
        char[] chars = candidate.toCharArray();
        try {
            return argon2.verify(storedHash, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }
}
