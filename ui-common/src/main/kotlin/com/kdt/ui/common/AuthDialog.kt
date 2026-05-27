package com.kdt.ui.common

import com.kdt.auth.AuthStrategy
import com.kdt.auth.KerberosAuth
import com.kdt.auth.MtlsAuth
import com.kdt.auth.PlaintextAuth
import com.kdt.auth.SaslPlainAuth
import com.kdt.auth.SaslScramAuth
import com.kdt.auth.ScramMechanism
import com.kdt.auth.SslAuth
import com.kdt.auth.SslConfig
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

enum class SecurityProtocol { PLAINTEXT, SASL_PLAINTEXT, SASL_SSL, SSL_ONLY, MTLS }
enum class SaslMechanism { PLAIN, SCRAM_SHA_256, SCRAM_SHA_512, GSSAPI }

/**
 * Mutable form-state for the auth editor. UI binds fields here; on "OK" the form
 * is materialized into an [AuthStrategy] via [toAuthStrategy].
 */
class AuthFormState {
    var protocol: SecurityProtocol = SecurityProtocol.PLAINTEXT
    var saslMechanism: SaslMechanism = SaslMechanism.PLAIN
    var username: String = ""
    var password: String = ""
    var truststorePath: String = ""
    var truststorePassword: String = ""
    var keystorePath: String = ""
    var keystorePassword: String = ""
    var keyPassword: String = ""
    var verifyHostname: Boolean = true
    var kerberosService: String = "kafka"
    var kerberosPrincipal: String = ""
    var keytabPath: String = ""

    fun toAuthStrategy(): AuthStrategy {
        val ssl = SslConfig(
            truststorePath = truststorePath.ifBlank { null },
            truststorePassword = truststorePassword.ifBlank { null },
            keystorePath = keystorePath.ifBlank { null },
            keystorePassword = keystorePassword.ifBlank { null },
            keyPassword = keyPassword.ifBlank { null },
            verifyHostname = verifyHostname,
        )
        return when (protocol) {
            SecurityProtocol.PLAINTEXT -> PlaintextAuth
            SecurityProtocol.SSL_ONLY -> SslAuth(ssl)
            SecurityProtocol.MTLS -> MtlsAuth(ssl)
            SecurityProtocol.SASL_PLAINTEXT, SecurityProtocol.SASL_SSL -> {
                val useTls = protocol == SecurityProtocol.SASL_SSL
                when (saslMechanism) {
                    SaslMechanism.PLAIN -> SaslPlainAuth(username, password, useTls, if (useTls) ssl else null)
                    SaslMechanism.SCRAM_SHA_256 -> SaslScramAuth(username, password, ScramMechanism.SCRAM_SHA_256, useTls, if (useTls) ssl else null)
                    SaslMechanism.SCRAM_SHA_512 -> SaslScramAuth(username, password, ScramMechanism.SCRAM_SHA_512, useTls, if (useTls) ssl else null)
                    SaslMechanism.GSSAPI -> KerberosAuth(
                        servicePrincipal = kerberosService.ifBlank { "kafka" },
                        userPrincipal = kerberosPrincipal,
                        keytabPath = keytabPath.ifBlank { null },
                        useTls = useTls,
                        sslConfig = if (useTls) ssl else null,
                    )
                }
            }
        }
    }
}

/**
 * Modal dialog that edits an [AuthFormState] and returns the produced [AuthStrategy].
 * UI shows/hides fields based on the chosen protocol + SASL mechanism.
 */
class AuthDialog(initial: AuthFormState = AuthFormState()) : Dialog<AuthStrategy>() {

    private val state = initial

    private val protocolBox = ChoiceBox<SecurityProtocol>().apply {
        items.addAll(*SecurityProtocol.values())
        value = state.protocol
    }
    private val mechBox = ChoiceBox<SaslMechanism>().apply {
        items.addAll(*SaslMechanism.values())
        value = state.saslMechanism
    }
    private val userField = TextField(state.username)
    private val pwdField = PasswordField().apply { text = state.password }
    private val trustPathField = TextField(state.truststorePath).apply { promptText = "/path/to/truststore.jks" }
    private val trustPwdField = PasswordField().apply { text = state.truststorePassword }
    private val keyPathField = TextField(state.keystorePath).apply { promptText = "/path/to/keystore.jks" }
    private val keyPwdField = PasswordField().apply { text = state.keystorePassword }
    private val keyKeyPwdField = PasswordField().apply { text = state.keyPassword }
    private val verifyHostnameCheck = CheckBox("Verify broker hostname").apply { isSelected = state.verifyHostname }
    private val krbServiceField = TextField(state.kerberosService)
    private val krbPrincipalField = TextField(state.kerberosPrincipal).apply { promptText = "user@REALM" }
    private val keytabField = TextField(state.keytabPath).apply { promptText = "/etc/kafka/user.keytab (optional)" }

    init {
        title = "Connection security"
        headerText = "Configure how this client authenticates to the broker."

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(12.0)
        }
        var row = 0
        grid.add(Label("Security protocol:"), 0, row); grid.add(protocolBox, 1, row++)
        grid.add(Label("SASL mechanism:"), 0, row); grid.add(mechBox, 1, row++)
        grid.add(Label("Username:"), 0, row); grid.add(userField, 1, row++)
        grid.add(Label("Password:"), 0, row); grid.add(pwdField, 1, row++)
        grid.add(Label("Truststore path:"), 0, row); grid.add(trustPathField, 1, row++)
        grid.add(Label("Truststore password:"), 0, row); grid.add(trustPwdField, 1, row++)
        grid.add(Label("Keystore path:"), 0, row); grid.add(keyPathField, 1, row++)
        grid.add(Label("Keystore password:"), 0, row); grid.add(keyPwdField, 1, row++)
        grid.add(Label("Key password:"), 0, row); grid.add(keyKeyPwdField, 1, row++)
        grid.add(verifyHostnameCheck, 1, row++)
        grid.add(Label("Kerberos service name:"), 0, row); grid.add(krbServiceField, 1, row++)
        grid.add(Label("Kerberos principal:"), 0, row); grid.add(krbPrincipalField, 1, row++)
        grid.add(Label("Keytab path:"), 0, row); grid.add(keytabField, 1, row)

        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        protocolBox.valueProperty().addListener { _, _, _ -> syncVisibility() }
        mechBox.valueProperty().addListener { _, _, _ -> syncVisibility() }
        syncVisibility()

        setResultConverter { btn ->
            if (btn != ButtonType.OK) null
            else {
                state.protocol = protocolBox.value
                state.saslMechanism = mechBox.value
                state.username = userField.text
                state.password = pwdField.text
                state.truststorePath = trustPathField.text
                state.truststorePassword = trustPwdField.text
                state.keystorePath = keyPathField.text
                state.keystorePassword = keyPwdField.text
                state.keyPassword = keyKeyPwdField.text
                state.verifyHostname = verifyHostnameCheck.isSelected
                state.kerberosService = krbServiceField.text
                state.kerberosPrincipal = krbPrincipalField.text
                state.keytabPath = keytabField.text
                state.toAuthStrategy()
            }
        }
    }

    private fun syncVisibility() {
        val protocol = protocolBox.value
        val isSasl = protocol == SecurityProtocol.SASL_PLAINTEXT || protocol == SecurityProtocol.SASL_SSL
        val isSsl = protocol == SecurityProtocol.SSL_ONLY || protocol == SecurityProtocol.MTLS || protocol == SecurityProtocol.SASL_SSL
        val isMtls = protocol == SecurityProtocol.MTLS
        val isKrb = isSasl && mechBox.value == SaslMechanism.GSSAPI
        val isSaslUserPwd = isSasl && mechBox.value != SaslMechanism.GSSAPI

        mechBox.isDisable = !isSasl
        userField.isDisable = !isSaslUserPwd
        pwdField.isDisable = !isSaslUserPwd
        trustPathField.isDisable = !isSsl
        trustPwdField.isDisable = !isSsl
        keyPathField.isDisable = !isMtls
        keyPwdField.isDisable = !isMtls
        keyKeyPwdField.isDisable = !isMtls
        verifyHostnameCheck.isDisable = !isSsl
        krbServiceField.isDisable = !isKrb
        krbPrincipalField.isDisable = !isKrb
        keytabField.isDisable = !isKrb
    }
}
