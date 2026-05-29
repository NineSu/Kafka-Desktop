package com.kdt.app

import com.kdt.kafka.StartingPosition
import com.kdt.storage.ConnectionStore
import com.kdt.storage.ExportFormat
import com.kdt.storage.SavedConnection
import com.kdt.storage.StoredAuth
import com.kdt.ui.common.AuthFormState
import com.kdt.ui.common.ConnectionVM
import com.kdt.ui.common.ExportChoice
import com.kdt.ui.common.SaslMechanism
import com.kdt.ui.common.SecurityProtocol
import com.kdt.ui.common.StartChoice

/**
 * Translations between UI-layer view models and storage/core-kafka domain types.
 * Lives in the app (composition root) so neither ui-common nor core-storage need to
 * know about each other.
 */
object ConnectionMapping {

    /** Build a UI view model from a saved connection. Secrets are NOT loaded here. */
    fun toVM(conn: SavedConnection): ConnectionVM {
        val auth = AuthFormState().apply {
            protocol = parseProtocol(conn.auth.protocol)
            saslMechanism = parseMechanism(conn.auth.saslMechanism)
            username = conn.auth.username
            truststorePath = conn.auth.truststorePath
            keystorePath = conn.auth.keystorePath
            keytabPath = conn.auth.keytabPath
            kerberosService = conn.auth.kerberosService
            kerberosPrincipal = conn.auth.kerberosPrincipal
            verifyHostname = conn.auth.verifyHostname
        }
        return ConnectionVM(id = conn.id, name = conn.name, bootstrap = conn.bootstrapServers, authState = auth)
    }

    /** Split a VM into the secret-free [SavedConnection] plus its [ConnectionStore.Secrets]. */
    fun toSaved(vm: ConnectionVM): Pair<SavedConnection, ConnectionStore.Secrets> {
        val a = vm.authState
        val stored = StoredAuth(
            protocol = a.protocol.name,
            saslMechanism = a.saslMechanism.name,
            username = a.username,
            truststorePath = a.truststorePath,
            keystorePath = a.keystorePath,
            keytabPath = a.keytabPath,
            kerberosService = a.kerberosService,
            kerberosPrincipal = a.kerberosPrincipal,
            verifyHostname = a.verifyHostname,
        )
        val secrets = ConnectionStore.Secrets(
            password = a.password,
            truststorePassword = a.truststorePassword,
            keystorePassword = a.keystorePassword,
            keyPassword = a.keyPassword,
        )
        return SavedConnection(vm.id, vm.name, vm.bootstrap, stored) to secrets
    }

    /** Populate a VM's secret fields from the vault (before connecting or editing). */
    fun loadSecretsInto(vm: ConnectionVM, store: ConnectionStore) {
        val s = store.loadSecrets(vm.id)
        vm.authState.password = s.password
        vm.authState.truststorePassword = s.truststorePassword
        vm.authState.keystorePassword = s.keystorePassword
        vm.authState.keyPassword = s.keyPassword
    }

    fun toStartingPosition(choice: StartChoice): StartingPosition = when (choice) {
        StartChoice.Beginning -> StartingPosition.Beginning
        StartChoice.End -> StartingPosition.End
        is StartChoice.LastN -> StartingPosition.LastN(choice.n)
        is StartChoice.FromTimestamp -> StartingPosition.FromTimestamp(choice.epochMs)
        is StartChoice.FromOffset -> StartingPosition.FromOffset(choice.offset)
    }

    fun positionLabel(choice: StartChoice): String = when (choice) {
        StartChoice.Beginning -> "from beginning"
        StartChoice.End -> "from end"
        is StartChoice.LastN -> "last ${choice.n}/partition"
        is StartChoice.FromTimestamp -> "from timestamp"
        is StartChoice.FromOffset -> "from offset ${choice.offset}"
    }

    fun toExportFormat(choice: ExportChoice): ExportFormat = when (choice) {
        ExportChoice.CSV -> ExportFormat.CSV
        ExportChoice.JSON -> ExportFormat.JSON
        ExportChoice.JSONL -> ExportFormat.JSONL
    }

    private fun parseProtocol(name: String): SecurityProtocol =
        runCatching { SecurityProtocol.valueOf(name) }.getOrDefault(SecurityProtocol.PLAINTEXT)

    private fun parseMechanism(name: String): SaslMechanism =
        runCatching { SaslMechanism.valueOf(name) }.getOrDefault(SaslMechanism.PLAIN)
}
