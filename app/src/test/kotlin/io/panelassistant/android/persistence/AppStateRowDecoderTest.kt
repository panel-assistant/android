package io.panelassistant.android.persistence

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.lang.reflect.InvocationTargetException
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Test

/** Runs the compiled production callers with an isolated, scripted Android SQLite boundary. */
class AppStateRowDecoderTest {
    private data class Example(val type: String, val text: String?, val value: Any)

    private val readable = listOf(
        Example("string", "hello ☃", "hello ☃"), Example("string", null, ""),
        Example("int", "-42", -42), Example("long", "4294967296", 4294967296L),
        Example("float", "1.25", 1.25f), Example("boolean", "1", true),
        Example("boolean", "0", false), Example("boolean", "true", false),
        Example("boolean", null, false),
        Example("string_set", "[\"a\",\"b\",\"a\"]", setOf("a", "b")),
        Example("string_set", "[]", emptySet<String>()),
    )
    private val unreadable = listOf(
        "int" to null, "long" to null, "float" to null, "string_set" to null,
        "int" to "2147483648", "long" to "9223372036854775808",
        "float" to "bad", "string_set" to "not-json", "unknown" to "value",
    )

    @Test fun validRowsLoadWithExistingNullSemantics() = Harness().use { h ->
        h.seed("live", readable.mapIndexed { i, e -> arrayOf("key$i", e.type, e.text) })
        val preferences = h.preferences("live")
        readable.forEachIndexed { i, e -> assertEquals("${e.type} ${e.text}", e.value, preferences.all["key$i"]) }
    }

    @Test fun restoreUpdatesCachedNamespaceSkipsAndCounts() = Harness().use { h ->
        val cached = h.preferences("restore")
        assertTrue(cached.edit().putString("untouched", "keep").putString("invalid", "old").commit())
        val validRows = readable.mapIndexed { i, e -> ConfigVault.StateRow("restore", "key$i", e.type, e.text, 0L) }
        val badRows = unreadable.map { (type, text) -> ConfigVault.StateRow("restore", "invalid", type, text, 0L) }
        assertEquals(readable.size, h.restore(validRows + badRows))
        assertSame(cached, h.preferences("restore"))
        readable.forEachIndexed { i, e -> assertEquals(e.value, cached.all["key$i"]) }
        assertEquals("keep", cached.getString("untouched", null))
        assertEquals("old", cached.getString("invalid", null))
        assertEquals(2, h.committedTransactions())
        assertEquals(0, h.restore(badRows))
        assertEquals(2, h.committedTransactions())
        h.failWrites()
        assertEquals(0, h.restore(listOf(ConfigVault.StateRow("restore", "failed", "string", "new", 0L))))
        assertEquals(2, h.committedTransactions())
    }

    @Test fun invalidRowsFailLoadButRestoreContinues() = Harness().use { h ->
        unreadable.forEachIndexed { index, (type, text) ->
            val namespace = "broken$index"
            h.seed(namespace, listOf(arrayOf("broken", type, text)))
            val failure = assertThrows(InvocationTargetException::class.java) { h.preferences(namespace) }
            val expected = when (type) {
                "int", "long", "float" -> NumberFormatException::class.java
                "string_set" -> org.json.JSONException::class.java
                else -> IllegalStateException::class.java
            }
            assertTrue("$type $text: ${failure.cause}", expected.isInstance(failure.cause))
        }
        assertEquals(1, h.restore(listOf(
            ConfigVault.StateRow("healthy", "bad", "int", "bad", 0L),
            ConfigVault.StateRow("healthy", "good", "int", "7", 0L),
        )))
        assertEquals(7, h.preferences("healthy").getInt("good", 0))
        assertFalse(h.preferences("healthy").contains("bad"))
    }

    private class Harness : AutoCloseable {
        private val directory = scratchDirectory("state-row-sqlite").toFile()
        private val parent = AppStateRowDecoderTest::class.java.classLoader
        private val databaseName = "android.database.sqlite.SQLiteDatabase"
        private val helperName = "io.panelassistant.android.dashboard.EntityCatalogStore"
        private val sources = listOf(databaseName to DATABASE, helperName to HELPER)
        private val loader: ClassLoader
        private val app: Class<*>
        private val instance: Any
        private val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "state.rows.${directory.name}"
            override fun deleteSharedPreferences(name: String): Boolean = true
        }

        init {
            val files = sources.map { (name, source) ->
                File(directory, name.replace('.', '/') + ".java").apply { parentFile.mkdirs(); writeText(source) }
            }
            val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "tests require a JDK" }
            compiler.getStandardFileManager(null, null, null).use { manager ->
                val classpath = listOf(Context::class.java, android.database.Cursor::class.java)
                    .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
                check(compiler.getTask(null, manager, null, listOf("-classpath", classpath, "-d", directory.path), null,
                    manager.getJavaFileObjectsFromFiles(files)).call()) { "could not compile isolated SQLite adapter" }
            }
            loader = object : ClassLoader(parent) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    val isolated = name == databaseName || name.startsWith("$databaseName$") || name == helperName ||
                        (name.startsWith("io.panelassistant.android.persistence.") && !name.startsWith("io.panelassistant.android.persistence.ConfigVault"))
                    if (!isolated) return super.loadClass(name, resolve)
                    synchronized(this) {
                        val type = findLoadedClass(name) ?: run {
                            val path = name.replace('.', '/') + ".class"
                            val local = File(directory, path)
                            val bytes = if (local.exists()) local.readBytes() else requireNotNull(parent.getResourceAsStream(path)).use { it.readBytes() }
                            defineClass(name, bytes, 0, bytes.size)
                        }
                        if (resolve) resolveClass(type)
                        return type
                    }
                }
            }
            app = loader.loadClass("io.panelassistant.android.persistence.AppState")
            instance = app.getField("INSTANCE").get(null)
        }

        fun committedTransactions(): Int = loader.loadClass(databaseName).getField("committedTransactions").getInt(null)
        fun failWrites() { loader.loadClass(databaseName).getField("failWrites").setBoolean(null, true) }
        fun seed(namespace: String, rows: List<Array<String?>>) {
            @Suppress("UNCHECKED_CAST")
            val data = loader.loadClass(databaseName).getField("rows").get(null) as MutableMap<String, List<Array<String?>>>
            data[namespace] = rows
        }
        fun preferences(namespace: String): SharedPreferences =
            app.getMethod("preferences", Context::class.java, String::class.java).invoke(instance, context, namespace) as SharedPreferences
        fun restore(rows: List<ConfigVault.StateRow>): Int =
            app.getMethod("applyRestoredRows", Context::class.java, List::class.java).invoke(instance, context, rows) as Int
        override fun close() {
            try {
                app.getMethod("flush", Context::class.java, java.lang.Long.TYPE).invoke(instance, context, 5_000L)
            } finally {
                val cache = app.getDeclaredField("processes").apply { isAccessible = true }.get(instance)
                val processes = cache.javaClass.getMethod("snapshotValues").invoke(cache) as List<*>
                processes.forEach { process ->
                    val executor = process!!.javaClass.getDeclaredMethod("getExecutor").apply { isAccessible = true }.invoke(process)
                    (executor as java.util.concurrent.ExecutorService).shutdownNow()
                }
                directory.deleteRecursively()
            }
        }
    }

    private companion object {
        val HELPER = """
            package io.panelassistant.android.dashboard;
            public class EntityCatalogStore {
                private final android.database.sqlite.SQLiteDatabase db = new android.database.sqlite.SQLiteDatabase();
                public EntityCatalogStore(android.content.Context context) {}
                public android.database.sqlite.SQLiteDatabase getReadableDatabase() { return db; }
                public android.database.sqlite.SQLiteDatabase getWritableDatabase() { return db; }
                public void close() {}
            }
        """.trimIndent()
        val DATABASE = """
            package android.database.sqlite;
            import android.database.Cursor;
            import java.lang.reflect.Proxy;
            import java.util.*;
            public class SQLiteDatabase {
                public static final Map<String,List<String[]>> rows = new HashMap<>();
                public static boolean failWrites;
                public static int committedTransactions;
                private boolean successful;
                public Cursor rawQuery(String sql, String[] args) {
                    if (!sql.equals("SELECT last_insert_rowid()") &&
                        !sql.equals("SELECT state_key,value_type,value_text FROM app_state WHERE namespace=?"))
                        throw new UnsupportedOperationException(sql);
                    List<String[]> selected = sql.contains("last_insert_rowid")
                        ? Collections.singletonList(new String[]{"1"}) : rows.getOrDefault(args[0], Collections.emptyList());
                    int[] position = {-1};
                    return (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class[]{Cursor.class}, (p,m,a) -> {
                        switch(m.getName()) {
                            case "moveToNext": return ++position[0] < selected.size();
                            case "moveToFirst": position[0] = 0; return !selected.isEmpty();
                            case "getString": return selected.get(position[0])[(Integer)a[0]];
                            case "getLong": return Long.parseLong(selected.get(position[0])[(Integer)a[0]]);
                            case "close": return null;
                            default: throw new UnsupportedOperationException(m.getName());
                        }
                    });
                }
                public void beginTransaction() { successful = false; }
                public void setTransactionSuccessful() { successful = true; }
                public void endTransaction() { if (successful) committedTransactions++; }
                public void execSQL(String sql, Object[] args) {
                    if (failWrites) throw new IllegalStateException("scripted write failure");
                    if (!sql.startsWith("INSERT INTO app_state_revision(") &&
                        !sql.startsWith("INSERT OR REPLACE INTO app_state(") &&
                        !sql.startsWith("DELETE FROM app_state_revision")) throw new UnsupportedOperationException(sql);
                }
                public int delete(String table, String where, String[] args) {
                    if (!table.equals("app_state")) throw new UnsupportedOperationException(table);
                    return 0;
                }
            }
        """.trimIndent()
    }
}
