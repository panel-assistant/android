package io.panelassistant.android.camera

/** One AAC-LC/16kHz/mono encode shared by clients that negotiate both camera tracks. */
interface CameraAudioSource {
    /** Capability and permission only; must not open the microphone. */
    fun available(): Boolean
    /** A live STREAM lease, or null when capture/foreground/encoder admission fails. */
    fun start(output: (unit: ByteArray, ptsUs: Long) -> Unit): AutoCloseable?
}
