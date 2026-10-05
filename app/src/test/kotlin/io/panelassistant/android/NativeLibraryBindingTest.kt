package io.panelassistant.android

import io.panelassistant.android.hardware.NativeLed
import java.io.File
import kotlin.test.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Both JNI libraries bind to Kotlin classes by name: the wake-word library registers its natives on
 * a class path in JNI_OnLoad, and the LED library exports `Java_<package>_<class>_<method>` symbols.
 * A package or class rename that misses either side fails only when a panel loads the library, and
 * for the wake word silently (it reports unavailable). These load the binding sources, built for the
 * host by `buildHostJniLibraries`, against the real classes.
 */
class NativeLibraryBindingTest {
    private val directory = System.getProperty("hapaneld.test.hostJniDirectory")?.let(::File)

    @Test fun `the wake-word library registers every native on the wake-word class`() {
        // JNI_OnLoad fails, and System.load throws, if the class or any registered method is missing.
        System.load(library("libhapaneld_mww.so").absolutePath)
    }

    @Test fun `the LED library exports the natives the LED class declares`() {
        System.load(library("libhapaneld_led.so").absolutePath)
        // Throws UnsatisfiedLinkError if the symbol names do not match the class; the host has no LED.
        assertFalse(NativeLed.nativeProbe())
    }

    private fun library(name: String): File {
        assumeTrue("host JNI libraries are built on Linux only", directory != null)
        return File(directory, name)
    }
}
