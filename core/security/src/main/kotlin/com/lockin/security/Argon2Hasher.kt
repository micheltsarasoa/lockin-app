package com.lockin.security

import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Argon2id-based password hasher.
 *
 * Parameters are tuned to be slow enough to resist brute-force (>200ms on modern hardware)
 * while remaining acceptable for interactive use.
 *
 * Output format: PHC string compatible
 *   $argon2id$v=19$m=65536,t=3,p=1$<base64 salt>$<base64 hash>
 */
@Singleton
class Argon2Hasher @Inject constructor() {

    companion object {
        private const val MEMORY_KB = 65536    // 64 MB
        private const val ITERATIONS = 3
        private const val PARALLELISM = 1
        private const val SALT_LENGTH = 16     // bytes
        private const val HASH_LENGTH = 32     // bytes

        // Fallback for low-RAM devices (<1 GB)
        private const val MEMORY_KB_LOW = 16384  // 16 MB
    }

    private val secureRandom = SecureRandom()

    /**
     * Hashes a raw PIN string and returns the PHC-format string.
     * @throws IllegalArgumentException if rawPin is empty
     */
    fun hash(rawPin: String): String {
        require(rawPin.isNotEmpty()) { "PIN must not be empty" }

        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val hash = computeHash(rawPin.toByteArray(Charsets.UTF_8), salt)

        val b64Salt = Base64.encodeToString(salt, Base64.NO_WRAP or Base64.NO_PADDING)
        val b64Hash = Base64.encodeToString(hash, Base64.NO_WRAP or Base64.NO_PADDING)
        return "\$argon2id\$v=19\$m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM\$$b64Salt\$$b64Hash"
    }

    /**
     * Verifies a raw PIN against a stored PHC string. Constant-time comparison.
     */
    fun verify(rawPin: String, storedHash: String): Boolean {
        return try {
            val (salt, expectedHash) = parsePHC(storedHash)
            val actualHash = computeHash(rawPin.toByteArray(Charsets.UTF_8), salt)
            constantTimeEqual(actualHash, expectedHash)
        } catch (e: Exception) {
            false
        }
    }

    private fun computeHash(password: ByteArray, salt: ByteArray): ByteArray {
        val memoryKb = try {
            MEMORY_KB
        } catch (e: OutOfMemoryError) {
            MEMORY_KB_LOW
        }

        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ITERATIONS)
            .withMemoryAsKB(memoryKb)
            .withParallelism(PARALLELISM)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)

        val output = ByteArray(HASH_LENGTH)
        generator.generateBytes(password, output)
        return output
    }

    /**
     * Parses a PHC-format Argon2id string and returns (salt, hash) byte arrays.
     */
    private fun parsePHC(phc: String): Pair<ByteArray, ByteArray> {
        // Format: $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>
        val parts = phc.split("$").filter { it.isNotEmpty() }
        require(parts.size == 4) { "Invalid PHC string format" }
        require(parts[0] == "argon2id") { "Not an argon2id hash" }
        // parts[1] = "v=19"
        // parts[2] = "m=...,t=...,p=..."
        val salt = Base64.decode(parts[2], Base64.NO_WRAP or Base64.NO_PADDING)
        val hash = Base64.decode(parts[3], Base64.NO_WRAP or Base64.NO_PADDING)
        return Pair(salt, hash)
    }

    /**
     * Constant-time byte array comparison to prevent timing attacks.
     */
    private fun constantTimeEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
