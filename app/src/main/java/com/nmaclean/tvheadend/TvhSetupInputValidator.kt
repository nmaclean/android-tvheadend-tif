package com.nmaclean.tvheadend

import com.nmaclean.tvheadend.model.TvhConstants

/**
 * Pure input normalization and formatting helpers for the Leanback TIF setup flow.
 */
object TvhSetupInputValidator {

    const val PASSWORD_MASK = "••••••••"
    const val LEGACY_PASSWORD_MASK = "********"

    fun normalizeHost(raw: CharSequence?): String {
        val text = raw?.toString()?.trim().orEmpty()
        return if (text.startsWith("e.g.", ignoreCase = true)) "" else text
    }

    fun normalizePort(raw: CharSequence?, defaultPort: Int): Int {
        val digits = raw?.toString()?.trim()?.filter { it.isDigit() }.orEmpty()
        val parsed = digits.toIntOrNull() ?: return defaultPort
        return if (parsed in 1..65535) parsed else defaultPort
    }

    fun normalizeOptionalText(raw: CharSequence?, placeholder: String): String {
        val text = raw?.toString()?.trim().orEmpty()
        return if (text == placeholder) "" else text
    }

    fun resolvePassword(raw: CharSequence?, existingPassword: String, placeholder: String): String {
        val text = raw?.toString()?.trim().orEmpty()
        return when (text) {
            placeholder, PASSWORD_MASK, LEGACY_PASSWORD_MASK -> existingPassword
            else -> text
        }
    }

    fun formatHostDescription(host: String): String =
        host.ifBlank { "e.g., 192.168.1.50" }

    fun formatPortDescription(port: Int, defaultPort: Int, label: String): String =
        if (port <= 0) "$defaultPort (Default $label)" else port.toString()

    fun formatUsernameDescription(username: String): String =
        username.ifBlank { "Optional username" }

    fun formatPasswordDescription(password: String): String =
        if (password.isEmpty()) "Optional password" else PASSWORD_MASK

    fun formatProgressSummary(
        importedChannels: Int,
        totalChannels: Int,
        logosLoaded: Int
    ): String = "Imported $importedChannels / $totalChannels channels • $logosLoaded logos loaded"
}
