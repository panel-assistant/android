package io.panelassistant.android

import android.app.Dialog
import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigActivityResultDialogLifecycleTest {
    @Test fun `destroy during a suspended transition prevents a late result window`() = runBlocking {
        val owner = ActivityResultDialog()
        val transition = CompletableDeferred<Unit>()
        var created = false
        val result = launch(start = CoroutineStart.UNDISPATCHED) {
            transition.await()
            owner.show(activityUnavailable = false) {
                created = true
                FakeDialog()
            }
        }

        owner.destroy()
        transition.complete(Unit)
        result.join()

        assertFalse("a completed transition must not create a window for its destroyed owner", created)
    }

    @Test fun `destroy dismisses an already visible result window`() {
        val owner = ActivityResultDialog()
        val dialog = FakeDialog()
        owner.show(activityUnavailable = false) { dialog }
        assertTrue(dialog.visible)

        owner.destroy()
        owner.destroy()

        assertFalse(dialog.visible)
        assertEquals("teardown releases window ownership once", 1, dialog.dismissals)
    }

    @Test fun `finishing activity cannot create a result window`() {
        val owner = ActivityResultDialog()
        var created = false

        owner.show(activityUnavailable = true) {
            created = true
            FakeDialog()
        }

        assertFalse(created)
    }

    /** Window operations are the platform boundary; ownership and suspension use production code. */
    private class FakeDialog : Dialog(ContextWrapper(null)) {
        var visible = false
        var dismissals = 0
        override fun show() { visible = true }
        override fun dismiss() {
            visible = false
            dismissals++
        }
    }
}
