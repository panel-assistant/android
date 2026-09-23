package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.CoreInstrumentation
import io.github.maxlyth.hapaneld.control.TameDesiredStateReconciler
import io.github.maxlyth.hapaneld.control.TamePackageObservation
import io.github.maxlyth.hapaneld.control.TamePackagePresence
import io.github.maxlyth.hapaneld.control.TamePackageSafety
import io.github.maxlyth.hapaneld.control.TameStatePolicy
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.persistence.CleanDatabaseProof
import io.github.maxlyth.hapaneld.persistence.commitWithDurableVisibility
import io.github.maxlyth.hapaneld.persistence.readAppStateSemanticProof
import io.github.maxlyth.hapaneld.upgrade.UpgradeRequestCompletion
import io.github.maxlyth.hapaneld.upgrade.UpgradeRequestGate
import io.github.maxlyth.hapaneld.util.LatestDispatcher
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real worker, ownership policy and SQLite shutdown proof; package actuators are controlled fixtures. */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class TameShutdownInstrumentedTest {
    @Test fun quiescingMidTameFinishesOnlyCurrentPackageAndKeepsDurableOwnership() {
        for (actionSucceeds in listOf(true, false)) withState { context, state ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val interrupted = AtomicBoolean()
            val actions = AtomicInteger()
            val desired = setOf(FIRST, SECOND)
            val reconciler = TameDesiredStateReconciler(
                readOwned = { TameStatePolicy.parseOwnedMarkers(state.all) },
                observePackages = { packages -> packages.associateWith {
                    TamePackageObservation(TamePackagePresence.PRESENT, TamePackageSafety.SAFE)
                } },
                reassert = { pkg ->
                    val key = TameStatePolicy.markerKey(pkg)
                    TameStatePolicy.reassertOwnership(
                        markerExists = state.contains(key),
                        markerMode = state.getString(key, null),
                        captureMode = { "foreground" },
                        persistMarker = { mode -> state.commitWithDurableVisibility { putString(key, mode) } },
                        mutate = {
                            actions.incrementAndGet()
                            entered.countDown()
                            try {
                                check(release.await(10, TimeUnit.SECONDS))
                            } catch (error: InterruptedException) {
                                interrupted.set(true)
                                throw error
                            }
                            actionSucceeds
                        },
                    )
                },
                restore = { error("no restoration requested") },
                clearAbsent = { error("fixture packages are present") },
            )
            val owner = TameReconcileAuthority({ desired }, reconciler::reconcile, { false })
            try {
                assertEquals(LatestDispatcher.Admission.ACCEPTED, owner.request())
                assertTrue("first package did not enter its actuator", entered.await(5, TimeUnit.SECONDS))
                owner.request()
                assertFalse("active transaction must prevent clean quiescence", owner.closeAndJoin(25))
                assertFalse("shutdown interrupted a package transaction", interrupted.get())
                assertEquals("foreground", state.getString(TameStatePolicy.markerKey(FIRST), null))
                release.countDown()
                assertTrue("worker failed to terminate after current transaction", owner.closeAndJoin(2_000))
                assertFalse(interrupted.get())
                assertEquals("remaining packages and queued wake must be cancelled", 1, actions.get())
                assertEquals(LatestDispatcher.Admission.CLOSED, owner.request())
                assertFalse(state.contains(TameStatePolicy.markerKey(SECOND)))
                assertCleanHold(context, state, mapOf(TameStatePolicy.markerKey(FIRST) to "foreground"))
            } finally {
                release.countDown()
                owner.closeAndJoin(2_000)
            }
        }
    }

    @Test fun noTamingLoopStillProducesTheSameCleanDatabaseHold() = withState { context, state ->
        assertTrue(state.edit().putString("untouched", "value").commit())
        val owner = TameReconcileAuthority(
            readDesired = { emptySet() },
            reconcile = { _, _ -> error("an idle authority must not start a pass during close") },
            stopping = { false },
        )
        try {
            assertTrue(owner.closeAndJoin(2_000))
            assertCleanHold(context, state, mapOf("untouched" to "value"))
        } finally {
            owner.closeAndJoin(2_000)
        }
    }

    private fun assertCleanHold(context: Context, state: SharedPreferences, expected: Map<String, String>) {
        val gate = UpgradeRequestGate()
        var readyProof: CleanDatabaseProof? = null
        assertTrue(gate.arm(NONCE, object : UpgradeRequestCompletion {
            override fun ready(nonce: String, proof: CleanDatabaseProof) {
                assertEquals(NONCE, nonce)
                readyProof = proof
            }
            override fun failed(reason: String) = error("unexpected shutdown failure: $reason")
        }))
        val claim = checkNotNull(gate.claimShutdown())
        val freeze = checkNotNull(AppState.freezeForServiceShutdown(context))
        try {
            assertFalse("cached writers must reject mutations while frozen", state.edit().putString("late", "write").commit())
            val proof = AppState.proveCleanServiceShutdown(context, 5_000)
            assertNotNull("quiesced SQLite failed clean shutdown proof", proof)
            assertTrue(gate.holdReady(claim, freeze, proof!!) {})
            assertEquals(proof, readyProof)
            val databaseFile = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
            assertEquals(proof.databaseBytes, databaseFile.length())
            assertEquals(proof.sha256, sha256(databaseFile))
            assertEquals(0L, File(databaseFile.path + "-wal").length())
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
                val semantic = checkNotNull(readAppStateSemanticProof(db))
                assertEquals(expected.size.toLong(), proof.appStateRows)
                assertEquals(proof.appStateRows, semantic.count)
                assertEquals(proof.orderedAppStateSha256, semantic.orderedSha256)
                assertEquals(proof.settingsSemanticSha256, semantic.settingsSha256)
                val actual = db.rawQuery("SELECT state_key,value_text FROM app_state", null).use { cursor ->
                    buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
                }
                assertEquals(expected, actual)
            }
            assertFalse("READY must retain the admission freeze", state.edit().clear().commit())
            assertEquals(proof.sha256, sha256(databaseFile))
            val released = gate.release(NONCE)
            assertTrue(released.matched)
            assertNotNull(released.freeze)
            released.freeze!!.close()
            assertTrue("matching release must reopen writes", state.edit().putString("resumed", "yes").commit())
        } finally {
            gate.release(NONCE).freeze?.close()
            freeze.close()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun withState(test: (Context, SharedPreferences) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "tame-shutdown-${System.nanoTime()}").apply { mkdirs() }
        val context = IsolatedStateContext(base, directory)
        val state = AppState.preferences(context, "shutdown-test", "ownership")
        try {
            test(context, state)
        } finally {
            AppState.freezeForServiceShutdown(context)?.use {
                AppState.proveCleanServiceShutdown(context, 5_000)
            }
            context.clearPreferences()
            directory.deleteRecursively()
        }
    }

    private class IsolatedStateContext(base: Context, private val directory: File) : ContextWrapper(base) {
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "${super.getPackageName()}.${directory.name}"
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "${directory.name}-$name"
            preferenceNames += isolatedName
            return super.getSharedPreferences(isolatedName, mode)
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        fun clearPreferences() = preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
    }

    private companion object {
        const val FIRST = "com.vendor.one"
        const val SECOND = "com.vendor.two"
        const val NONCE = "0123456789abcdef0123456789abcdef"
    }
}
