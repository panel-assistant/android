package io.panelassistant.android.platform

/** Linux flock on the immutable system directory, shared with the rootless installer. */
internal object NativeHomeLock {
    init {
        try {
            System.loadLibrary("hapaneld_home")
        } catch (_: UnsatisfiedLinkError) {
            // Missing ABI support must leave handback unconfirmed, never use an unlocked actuator.
        }
    }

    fun tryAcquire(): AutoCloseable? {
        val fd = try { nativeAcquire() } catch (_: UnsatisfiedLinkError) { return null }
        if (fd < 0) return null
        return AutoCloseable { nativeClose(fd) }
    }

    /** Opens and validates the same inode without locking: the shell already holds its lock. */
    fun identity(): String? = try { nativeIdentity() } catch (_: UnsatisfiedLinkError) { null }

    private external fun nativeAcquire(): Int
    private external fun nativeIdentity(): String?
    private external fun nativeClose(fd: Int)
}
