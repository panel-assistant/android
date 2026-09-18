package io.github.maxlyth.hapaneld.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableTextFileTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun writesCreateTheDirectoryAndReadBackExactly() {
        val record = DurableTextFile(temp.root.resolve("nested/step.v1"))

        assertFalse(record.exists())
        assertNull(record.read())
        assertTrue(record.write("pulled sha=abc"))
        assertTrue(record.exists())
        assertEquals("pulled sha=abc", DurableTextFile(temp.root.resolve("nested/step.v1")).read())
    }

    @Test fun aRewriteReplacesTheRecordAndLeavesNoTemporary() {
        val record = DurableTextFile(temp.root.resolve("step.v1"))
        record.write("first")
        record.write("second")

        assertEquals("second", record.read())
        assertEquals(listOf("step.v1"), temp.root.list()!!.toList())
    }

    @Test fun anOversizedValueIsRefusedAndAnOversizedFileIsUnreadable() {
        val record = DurableTextFile(temp.root.resolve("step.v1"), maxChars = 8)

        assertFalse(record.write("123456789"))
        assertFalse(record.exists())

        temp.root.resolve("step.v1").writeText("x".repeat(64))
        assertNull(record.read())
    }

    @Test fun anInterruptedWriteLeavesThePreviousRecord() {
        val record = DurableTextFile(temp.root.resolve("step.v1"))
        record.write("committed")
        // What a process death between the temporary write and the rename leaves behind.
        temp.root.resolve(".step.v1.tmp").writeText("half")

        assertEquals("committed", DurableTextFile(temp.root.resolve("step.v1")).read())
    }
}
