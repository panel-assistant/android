package io.panelassistant.android.control

/** Effective ownership by the authenticated local channel; saved manual intent remains separate. */
internal object ManagedNetworkAdb {
    @Volatile var managed = false
    @Volatile var recoveryHold = false
    fun ownsRuntime(): Boolean = managed || recoveryHold
}
