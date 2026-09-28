package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Test

class LogStreamSseTest {
    @Test fun multilineExceptionRemainsOneBrowserEvent() {
        val entry = "[ 1790592713.123  123: 456 E/AndroidRuntime ]\n" +
            "java.lang.IllegalStateException\n    at Example.first(Example.kt:12)"
        assertEquals(
            "data: [ 1790592713.123  123: 456 E/AndroidRuntime ]\n" +
                "data: java.lang.IllegalStateException\n" +
                "data:     at Example.first(Example.kt:12)\n\n",
            logSseEvent(entry),
        )
    }
}
