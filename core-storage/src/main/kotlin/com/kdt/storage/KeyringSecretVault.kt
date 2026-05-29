package com.kdt.storage

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import org.slf4j.LoggerFactory

/**
 * [SecretVault] backed by the OS keystore via java-keyring
 * (macOS Keychain / Windows Credential Manager / Linux libsecret).
 *
 * Secrets are stored under a fixed domain with account = "$connectionId/$field".
 * Every keyring call is wrapped: if the backend is unavailable or a call fails,
 * we log and degrade gracefully rather than crashing — the connection metadata
 * still persists, only the secret is lost.
 */
class KeyringSecretVault(
    private val domain: String = "kafka-desktop",
) : SecretVault {

    private val log = LoggerFactory.getLogger(KeyringSecretVault::class.java)

    private val keyring: Keyring? = try {
        Keyring.create()
    } catch (t: Throwable) {
        log.warn("OS keyring unavailable — secrets will not persist this session", t)
        null
    }

    private fun account(connectionId: String, field: String) = "$connectionId/$field"

    override fun put(connectionId: String, field: String, secret: String) {
        val kr = keyring ?: return
        val acct = account(connectionId, field)
        try {
            if (secret.isBlank()) {
                deleteQuietly(acct)
            } else {
                kr.setPassword(domain, acct, secret)
            }
        } catch (t: Throwable) {
            log.warn("Failed to store secret for {}", acct, t)
        }
    }

    override fun get(connectionId: String, field: String): String? {
        val kr = keyring ?: return null
        return try {
            kr.getPassword(domain, account(connectionId, field))
        } catch (_: PasswordAccessException) {
            null // not set
        } catch (t: Throwable) {
            log.warn("Failed to read secret for {}/{}", connectionId, field, t)
            null
        }
    }

    override fun deleteAll(connectionId: String) {
        if (keyring == null) return
        for (field in SecretFields.ALL) {
            deleteQuietly(account(connectionId, field))
        }
    }

    private fun deleteQuietly(account: String) {
        val kr = keyring ?: return
        try {
            kr.deletePassword(domain, account)
        } catch (_: PasswordAccessException) {
            // already absent — fine
        } catch (t: Throwable) {
            log.warn("Failed to delete secret for {}", account, t)
        }
    }
}
