package io.panelassistant.android.panelassistant

import android.content.Context
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.control.Su
import io.panelassistant.android.util.helperAssetName
import io.panelassistant.android.util.openRootAbstractSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

internal data class LocalAdbState(val managed: Boolean, val recoveryHold: Boolean, val securityUnverified: Boolean = false)
internal interface LocalAdbEndpoint {
    fun state(): LocalAdbState?
    fun arm(nonce: String): Boolean
    fun beat(nonce: String): Boolean
    fun recover(nonce: String): Boolean
    fun shell(command: ByteArray, timeoutMs: Int): JSONObject
}

/** Session authority stays here; the root daemon only trusts this app's UID, never a remote token. */
internal class LocalAdbRuntime(
    private val scope: CoroutineScope,
    private val endpoint: LocalAdbEndpoint,
    private val hardened: () -> Boolean,
    private val prepare: () -> JSONObject,
    private val bootstrap: () -> Boolean,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val securityTransition: (() -> Boolean) -> Boolean = { RemoteDebugSecurityTransitionGate.mutate(it) },
    private val authorize: suspend (String, () -> Unit) -> String? = { _, _ -> "not_commandable" },
    private val onClosed: () -> Unit = {},
    private val healthy: suspend () -> Boolean = { true },
) : PanelAssistantLocalAdb {
    private val generation = AtomicLong()
    private val effects = Mutex()
    private val shells = Mutex()
    @Volatile private var nonce = ""
    @Volatile private var granted = false
    @Volatile private var compatibleHistory = false

    override fun session(token: String, granted: Boolean) {
        val current = generation.incrementAndGet()
        this.granted = granted
        val retainExistingLease = !granted && compatibleHistory && nonce.isNotEmpty()
        if (granted) compatibleHistory = true
        val fresh = if (retainExistingLease) nonce else MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray()).joinToString("") { "%02x".format(it) }
        nonce = fresh
        scope.launch(dispatcher) {
            try { effects.withLock {
                if (generation.get() != current) return@withLock
                if (!granted) {
                    if (!hardened() && endpoint.state() != null) transition { generation.get() == current && !hardened() && endpoint.recover(fresh) }
                    publish()
                    return@withLock
                }
                if (endpoint.state() == null) bootstrap()
                if (generation.get() == current && endpoint.state() != null && healthy()) {
                    transition { generation.get() == current && endpoint.arm(fresh) }
                    publish()
                    onClosed()
                }
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { publish() }
            if ((granted || (retainExistingLease && hardened())) && generation.get() == current) {
                while (scope.isActive && generation.get() == current) {
                    delay(30_000)
                    if (!healthy()) continue
                    try { effects.withLock {
                        if (generation.get() == current) {
                            var state = endpoint.state()
                            if (state == null && granted) { bootstrap(); state = endpoint.state() }
                            if (state != null && !state.managed && !state.recoveryHold && granted) {
                                transition { generation.get() == current && endpoint.arm(fresh) }
                                publish()
                                onClosed()
                            }
                            else if (state != null) endpoint.beat(fresh)
                            publish()
                        }
                    } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                      catch (_: Exception) { publish() }
                }
            }
        }
    }

    private fun transition(action: () -> Boolean): Boolean = securityTransition(action)
    private fun publish(): LocalAdbState? = endpoint.state().also {
        ManagedNetworkAdb.managed = it?.managed == true
        ManagedNetworkAdb.recoveryHold = it?.recoveryHold == true
    }

    override suspend fun request(op: String, args: JSONObject, admit: () -> Boolean, onPending: () -> Unit): PanelAssistantManageReply {
        fun refused(code: String = "invalid_value") = PanelAssistantManageReply("refused", code, null)
        val current = generation.get()
        val command = if (op == "adb.shell") (args.opt("command") as? String)?.toByteArray(Charsets.UTF_8) else null
        val rawTimeout = args.opt("timeout_ms")
        val timeout = if (rawTimeout is Int || rawTimeout is Long) (rawTimeout as Number).toLong() else null
        if (op == "adb.shell" && (command == null || command.isEmpty() || command.size > 65_536 || command.contains(0) || timeout == null || timeout !in 1..180_000)) return refused()
        if (!granted || generation.get() != current || !admit()) return refused("expired")
        if (op != "adb.shell" && args.length() != 0) return refused()
        if (op in listOf("adb.shell", "adb.recover") && hardened()) {
            val digest = MessageDigest.getInstance("SHA-256").digest(command ?: ByteArray(0)).joinToString("") { "%02x".format(it) }
            val refusal = authorize("$op:$nonce:$timeout:$digest", onPending)
            if (refusal != null) return refused(refusal)
        }
        return withContext(dispatcher) {
            if (op == "adb.shell") return@withContext shells.withLock {
                if (!granted || generation.get() != current || !admit()) return@withLock refused("expired")
                // Shell execution has its own serial queue. It must never hold healthy-app heartbeat
                // renewal or replacement-session recovery behind a long Android framework command.
                if (generation.get() != current || !admit()) return@withLock refused("expired")
                try {
                    PanelAssistantManageReply("applied", null, endpoint.shell(requireNotNull(command), requireNotNull(timeout).toInt()))
                } catch (_: Exception) {
                    PanelAssistantManageReply("failed", "local_adb_unavailable", null)
                }
            }
            effects.withLock {
                // A queued request cannot use a replacement session's authority.
                if (!granted || generation.get() != current || !admit()) return@withLock refused("expired")
                try {
                    val result = when (op) {
                        "adb.prepare" -> prepare()
                        "adb.ready" -> {
                            val state = publish()
                            JSONObject().put("ready", state != null).put("managed", state?.managed == true).put("recovery_hold", state?.recoveryHold == true).put("security_unverified", state?.securityUnverified == true)
                        }
                        "adb.recover" -> {
                            if (!admit() || !transition { endpoint.recover(nonce) }) return@withLock PanelAssistantManageReply("failed", "local_adb_unavailable", null)
                            publish()
                            JSONObject().put("enabled", true)
                        }
                        else -> return@withLock refused("unknown_command")
                    }
                    PanelAssistantManageReply("applied", null, result)
                } catch (_: Exception) {
                    PanelAssistantManageReply("failed", "local_adb_unavailable", null)
                }
            }
        }
    }

    companion object {
        fun create(context: Context, scope: CoroutineScope, config: Config): LocalAdbRuntime {
            val staging = LocalAdbAsset(context)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            return LocalAdbRuntime(scope, RootLocalAdbEndpoint(), { config.hardenedSecurityEnabled }, staging::prepare, {
                if (!Su.available()) false else {
                    val metadata = staging.prepare()
                    val path = metadata.getString("path")
                    val hash = metadata.getString("sha256")
                    Su.runSingleAttempt(localAdbBootstrapProgram(path, hash), 15_000)
                }
            }, authorize = { payload, pending ->
                io.panelassistant.android.http.authorizeSessionRequest(
                    true, io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                    payload, "Run Panel Assistant maintenance", io.panelassistant.android.security.LocalApprovalBroker.instance, pending,
                )
            }, onClosed = { io.panelassistant.android.control.AdbController(context, config).resealManagedHardened() }, healthy = {
                kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { acknowledgement ->
                        main.post { if (acknowledgement.isActive) acknowledgement.resume(true) }
                    }
                } == true
            })
        }
    }
}

internal class LocalAdbAsset(private val context: Context) {
    @Synchronized fun prepare(): JSONObject {
        val name = requireNotNull(helperAssetName(android.os.Build.SUPPORTED_ABIS.asIterable()))
        val bytes = context.assets.open(name).use { it.readBytes() }
        require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = File(context.cacheDir, "adb-recovery")
        check(directory.mkdirs() || directory.isDirectory)
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false)
        check(directory.setReadable(true, true) && directory.setWritable(true, true) && directory.setExecutable(true, true))
        val path = File(directory, "helper-$hash")
        check(!java.nio.file.Files.isSymbolicLink(path.toPath()))
        java.io.FileOutputStream(path).use { it.write(bytes); it.fd.sync() }
        path.setReadable(false, false); path.setWritable(false, false); path.setExecutable(false, false)
        check(path.setReadable(true, true) && path.setWritable(true, true))
        return JSONObject().put("path", path.absolutePath).put("sha256", hash).put("build_id", BuildConfig.HELPER_BUILD_ID)
    }
}

internal fun localAdbBootstrapProgram(path: String, hash: String, filesystemRoot: String = ""): String {
    require(filesystemRoot.isEmpty() || filesystemRoot.matches(Regex("/[A-Za-z0-9_/-]+")))
    require(filesystemRoot.split('/').none { it == ".." })
    require(hash.matches(Regex("[0-9a-f]{64}")))
    require(path.removePrefix(filesystemRoot).matches(Regex("/data/(user/0|data)/io\\.panelassistant\\.android/cache/adb-recovery/helper-$hash")))
    return """
        set -e
        source='$path'
        parent='$filesystemRoot/data/local'
        directory='$filesystemRoot/data/local/panel-assistant-adb-recovery'
        target='$filesystemRoot/data/local/panel-assistant-adb-recovery/helper'
        [ -d "${'$'}parent" ] && [ ! -L "${'$'}parent" ]
        [ "${'$'}(stat -c '%u' "${'$'}parent")" = 0 ]
        if [ ! -e "${'$'}directory" ] && [ ! -L "${'$'}directory" ]; then
          ( umask 077; mkdir "${'$'}directory" )
        fi
        [ -d "${'$'}directory" ] && [ ! -L "${'$'}directory" ]
        [ "${'$'}(stat -c '%u:%g:%a' "${'$'}directory")" = '0:0:700' ]
        [ -f "${'$'}source" ] && [ ! -L "${'$'}source" ]
        [ "${'$'}(sha256sum "${'$'}source" | cut -d' ' -f1)" = '$hash' ]
        if [ -e "${'$'}target" ] || [ -L "${'$'}target" ]; then
          [ -f "${'$'}target" ] && [ ! -L "${'$'}target" ]
          [ "${'$'}(stat -c '%u:%g:%a:%h' "${'$'}target")" = '0:0:700:1' ]
        fi
        umask 077
        stage=${'$'}(mktemp "${'$'}directory/.helper.XXXXXX")
        trap 'rm -f "${'$'}stage"' 0 1 2 15
        cat "${'$'}source" > "${'$'}stage"
        chown 0:0 "${'$'}stage"
        chmod 700 "${'$'}stage"
        [ "${'$'}(sha256sum "${'$'}stage" | cut -d' ' -f1)" = '$hash' ]
        sync
        mv "${'$'}stage" "${'$'}target"
        trap - 0 1 2 15
        sync
        "${'$'}target" --adb-recovery </dev/null >/dev/null 2>&1 &
    """.trimIndent()
}

internal class RootLocalAdbEndpoint : LocalAdbEndpoint {
    private fun <T> exchange(timeoutMs: Int = 20_000, body: (java.io.InputStream, java.io.OutputStream) -> T): T =
        openRootAbstractSocket("hapaneld-adb-recovery").use { socket ->
            socket.soTimeout = timeoutMs
            body(socket.inputStream, socket.outputStream)
        }
    private fun line(input: java.io.InputStream): String {
        val result = StringBuilder()
        repeat(160) {
            val value = input.read()
            check(value >= 0)
            if (value == 10) return result.toString()
            check(value in 32..126)
            result.append(value.toChar())
        }
        error("local adb header too long")
    }
    private fun command(text: String): Boolean = exchange { input, output ->
        output.write((text + "\n").toByteArray()); output.flush(); line(input) == "OK"
    }
    override fun state(): LocalAdbState? = runCatching {
        exchange { input, output ->
            output.write("STATUS\n".toByteArray()); output.flush()
            val fields = line(input).split(' ')
            check(fields.size == 6 && fields[0] == "READY" && fields[1] == "1" && fields[2].matches(Regex("[0-9a-f]{64}")) && fields[3] in listOf("0", "1") && fields[4] in listOf("0", "1") && fields[5] in listOf("0", "1"))
            LocalAdbState(fields[3] == "1", fields[4] == "1", fields[5] == "1")
        }
    }.getOrNull()
    override fun arm(nonce: String): Boolean = command("ARM $nonce")
    override fun beat(nonce: String): Boolean = command("BEAT $nonce")
    override fun recover(nonce: String): Boolean = command("RECOVER $nonce")
    override fun shell(command: ByteArray, timeoutMs: Int): JSONObject = exchange(timeoutMs + 5_000) { input, output ->
        output.write("SHELL $timeoutMs ${command.size}\n".toByteArray()); output.write(command); output.flush()
        val fields = line(input).split(' ')
        check(fields.size == 5 && fields[0] == "RESULT")
        val exit = fields[1].toInt(); val count = fields[4].toInt()
        check(exit in -1..255 && fields[2] in listOf("0", "1") && fields[3] in listOf("0", "1") && count in 0..32_768)
        val bytes = ByteArray(count); var at = 0
        while (at < count) { val read = input.read(bytes, at, count - at); check(read > 0); at += read }
        JSONObject().put("stdout_b64", Base64.getEncoder().encodeToString(bytes)).put("exit_code", if (exit < 0) JSONObject.NULL else exit)
            .put("timed_out", fields[2] == "1").put("truncated", fields[3] == "1")
    }
}
