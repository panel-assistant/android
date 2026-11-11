package io.panelassistant.android.http

import io.panelassistant.android.security.SensitiveOperation

internal enum class ConfigSensitiveAdmissionResult { AUTHORIZED, DENIED, SEPARATE_SENSITIVE_CHANGES }

/** Stateless admission for sensitive Configure changes; exact peer/payload binding stays in the broker. */
internal object ConfigSensitiveAdmission {
    suspend fun authorize(
        hardenedSecurityEnabled: Boolean,
        loopbackPeer: Boolean,
        requestedOperations: List<SensitiveOperation>,
        /** Operations this caller performs without approval; only the others are held to one approval per save. */
        exempt: (SensitiveOperation) -> Boolean = { false },
        authorize: suspend (SensitiveOperation) -> Boolean,
    ): ConfigSensitiveAdmissionResult {
        if (!hardenedSecurityEnabled || loopbackPeer) return ConfigSensitiveAdmissionResult.AUTHORIZED
        val operations = requestedOperations.distinct().filterNot(exempt)
        if (operations.size > 1) return ConfigSensitiveAdmissionResult.SEPARATE_SENSITIVE_CHANGES
        for (operation in operations) {
            if (!authorize(operation)) return ConfigSensitiveAdmissionResult.DENIED
        }
        return ConfigSensitiveAdmissionResult.AUTHORIZED
    }
}
