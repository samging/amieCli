package org.example.project.auth

/**
 * Whether the app has accounts: signing in with Keycloak, the backup and cloud save it gives, the package repository
 * and the device sync with the Amie backend. The app is a desktop app on its own for now, so they are off, and nothing
 * asks to sign in or calls Keycloak or the backend; `AMIE_ACCOUNTS=1` (or `-Damie.accounts=true`) brings them back.
 */
object Accounts {
    val enabled: Boolean
        get() = System.getenv("AMIE_ACCOUNTS") == "1" || System.getProperty("amie.accounts") == "true"

    /** What a call that needs an account says while they are off */
    const val OFF = "Accounts and the Amie backend aren't part of amie yet (AMIE_ACCOUNTS=1 turns them on)"
}
