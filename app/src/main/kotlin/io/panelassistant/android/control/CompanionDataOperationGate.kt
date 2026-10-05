package io.panelassistant.android.control

import io.panelassistant.android.util.CompanionInstaller
import java.util.concurrent.atomic.AtomicReference

/**
 * Suppresses ha-paneld's own automatic launch paths while the root helper owns Companion data.
 * This is a correctness lease, not the root security boundary: helper operations still use descriptor-
 * relative no-follow I/O because an external launcher cannot be prevented by an app-process flag.
 */
internal object CompanionDataOperationGate {
    class Lease internal constructor(private val packageName: String) : AutoCloseable {
        override fun close() {
            active.compareAndSet(packageName, null)
        }
    }

    private val active = AtomicReference<String?>()

    fun acquire(packageName: String): Lease? =
        if (active.compareAndSet(null, packageName)) Lease(packageName) else null

    fun blocks(packageName: String): Boolean {
        val owner = active.get() ?: return false
        return owner == packageName ||
            (isCompanionPackage(owner) && isCompanionPackage(packageName))
    }

    /** Implicit VIEW intents can fall back from one Companion variant to the other/system default. */
    fun blocksImplicitNavigation(): Boolean = active.get() != null

    fun isCompanionPackage(packageName: String): Boolean = packageName in CompanionInstaller.SUPPORTED_PACKAGES
}
