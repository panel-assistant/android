package io.panelassistant.android

/** Marks tests that belong to the release-critical Android instrumentation suite. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class CoreInstrumentation
