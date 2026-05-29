package com.kdt.ui.common

import java.util.UUID

/**
 * UI-layer view model for a saved connection. Carries an [AuthFormState] so the
 * auth editor can bind directly. Secret fields inside [authState] may be empty
 * until the app lazily loads them from the secret vault (on connect or edit).
 *
 * The app layer maps this to/from the storage layer's `SavedConnection`/`StoredAuth`.
 */
class ConnectionVM(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var bootstrap: String = "localhost:9092",
    val authState: AuthFormState = AuthFormState(),
) {
    /** Shown in the ComboBox / ListView. */
    override fun toString(): String =
        if (name.isBlank()) bootstrap else "$name  ($bootstrap)"
}
