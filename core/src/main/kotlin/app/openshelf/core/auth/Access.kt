package app.openshelf.core.auth

import app.openshelf.core.model.ShareDefaults
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class SessionStore {
    private val tokens = HashSet<String>()

    @Synchronized
    fun issue(): String {
        val token = Token.randomHex(16)
        tokens.add(token)
        return token
    }

    @Synchronized
    fun valid(token: String): Boolean = tokens.contains(token)
}

internal class AccessGuard(
    private val password: () -> String,
    private val username: () -> String,
    private val sessions: SessionStore,
) {
    fun allow(headers: Map<String, String>): Boolean {
        val expected = password()
        if (expected.isEmpty()) {
            return true
        }
        val expectedUser = username().ifBlank { ShareDefaults.USERNAME }
        val authorization = headers["authorization"]
        if (authorization != null) {
            if (authorization.startsWith("Bearer ", ignoreCase = true)) {
                if (Token.constantEquals(expected, authorization.substring(7).trim())) {
                    return true
                }
            } else if (authorization.startsWith("Basic ", ignoreCase = true)) {
                val given = decodeBasicCredentials(authorization.substring(6).trim())
                if (
                    given != null &&
                    Token.constantEquals(expectedUser, given.first) &&
                    Token.constantEquals(expected, given.second)
                ) {
                    return true
                }
            }
        }
        val headerToken = headers["x-quicktemp-token"]
        if (headerToken != null && Token.constantEquals(expected, headerToken.trim())) {
            return true
        }
        val cookie = headers["cookie"]
        if (cookie != null) {
            val session = cookieValue(cookie, ShareDefaults.SESSION_COOKIE)
            if (session != null && validSession(session)) {
                return true
            }
        }
        return false
    }

    fun issueSession(): String = sessions.issue()

    fun validSession(token: String): Boolean {
        if (token.isEmpty() || token.length > 128) {
            return false
        }
        return sessions.valid(token)
    }
}

internal fun decodeBasicCredentials(encoded: String): Pair<String, String>? {
    val bytes = decodeBase64(encoded) ?: return null
    val text = bytes.toString(Charsets.UTF_8)
    val colon = text.indexOf(':')
    if (colon < 0) {
        return null
    }
    return text.substring(0, colon) to text.substring(colon + 1)
}

internal fun cookieValue(header: String, name: String): String? {
    for (part in header.split(';')) {
        val item = part.trim()
        val eq = item.indexOf('=')
        if (eq <= 0) {
            continue
        }
        if (item.substring(0, eq).trim() == name) {
            return item.substring(eq + 1).trim()
        }
    }
    return null
}

internal object Token {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    fun password(length: Int = 12): String {
        return buildString(length) {
            repeat(length) {
                append(ALPHABET[random.nextInt(ALPHABET.length)])
            }
        }
    }

    fun constantEquals(expected: String, given: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val left = digest.digest(expected.toByteArray(Charsets.UTF_8))
        val right = digest.digest(given.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(left, right)
    }
}

private fun decodeBase64(raw: String): ByteArray? {
    val trimmed = raw.trim()
    val padded = when (trimmed.length % 4) {
        2 -> "$trimmed=="
        3 -> "$trimmed="
        else -> trimmed
    }
    return try {
        Base64.getDecoder().decode(padded)
    } catch (error: IllegalArgumentException) {
        null
    }
}

object PasswordGenerator {
    fun generate(): String = Token.password()
}

object IdGenerator {
    fun newMountId(): String = "m" + Token.randomHex(4)
}
