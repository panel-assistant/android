package io.panelassistant.android.panelassistant

import android.content.Context
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.control.Su
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.util.HelperClient
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

internal data class LocalAdbState(val managed: Boolean, val recoveryHold: Boolean, val securityUnverified: Boolean = false, val buildId: String = BuildConfig.HELPER_BUILD_ID)
internal interface LocalAdbEndpoint {
    fun state(): LocalAdbState?
    fun arm(nonce: String): Boolean
    fun beat(nonce: String): Boolean
    fun recover(nonce: String, submit: (() -> Unit) -> Boolean = { it(); true }): Boolean?
    fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject?
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
    private val authorize: suspend (String, () -> Unit, () -> Boolean) -> io.panelassistant.android.http.DeferredSessionApproval =
        { _, _, _ -> io.panelassistant.android.http.DeferredSessionApproval("not_commandable") },
    private val onClosed: () -> Unit = {},
    private val healthy: suspend () -> Boolean = { true },
    private val rootKnownUnusable: () -> Boolean = { false },
    private val daemon: Daemon = HelperClient,
) : PanelAssistantLocalAdb {
    private val generation = AtomicLong()
    private val effects = Mutex()
    private val shells = Mutex()
    @Volatile private var nonce = ""
    @Volatile private var granted = false
    @Volatile private var compatibleHistory = false

    // A structural su refusal need not hide a compatible running ordinary helper. This short read-only
    // capability check runs per PA hello; the eventual action independently admits its carrying socket.
    override fun offered(): Boolean = !rootKnownUnusable() || endpoint.state() != null || daemon.supportsAdbRecoveryEnsure()

    override fun session(token: String, granted: Boolean) {
        val (current, fresh, retainExistingLease) = RemoteDebugSecurityTransitionGate.withLock {
            val current = generation.incrementAndGet()
            this.granted = granted
            val retain = !granted && compatibleHistory && nonce.isNotEmpty()
            if (granted) compatibleHistory = true
            val fresh = if (retain) nonce else MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray()).joinToString("") { "%02x".format(it) }
            nonce = fresh
            Triple(current, fresh, retain)
        }
        scope.launch(dispatcher) {
            try { effects.withLock {
                if (generation.get() != current) return@withLock
                if (!granted) {
                    if (!hardened() && endpoint.state() != null) transition { generation.get() == current && !hardened() && endpoint.recover(fresh) == true }
                    publish()
                    return@withLock
                }
                val state = currentDaemon()
                if (generation.get() == current && state != null && healthy()) {
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
                            val state = if (granted) currentDaemon() else endpoint.state()
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

    /** Startup proves an open recovery route before retiring a different-build owner. */
    private fun currentDaemon(): LocalAdbState? {
        var state = endpoint.state()
        if (state == null || state.buildId != BuildConfig.HELPER_BUILD_ID) {
            bootstrap()
            state = endpoint.state()
        }
        // A failed bootstrap must never ARM or renew the stale owner as if replacement succeeded.
        return state?.takeIf { it.buildId == BuildConfig.HELPER_BUILD_ID }
    }

    private fun transition(action: () -> Boolean): Boolean = securityTransition(action)
    private fun publish(): LocalAdbState? = endpoint.state().also {
        ManagedNetworkAdb.managed = it?.managed == true
        ManagedNetworkAdb.recoveryHold = it?.recoveryHold == true
    }

    override suspend fun request(op: String, args: JSONObject, admit: () -> Boolean, onPending: () -> Unit): PanelAssistantManageReply {
        fun refused(code: String = "invalid_value") = PanelAssistantManageReply("refused", code, null)
        val (current, requestNonce) = RemoteDebugSecurityTransitionGate.withLock { generation.get() to nonce }
        val command = if (op == "adb.shell") (args.opt("command") as? String)?.toByteArray(Charsets.UTF_8) else null
        val rawTimeout = args.opt("timeout_ms")
        val timeout = if (rawTimeout is Int || rawTimeout is Long) (rawTimeout as Number).toLong() else null
        if (op == "adb.shell" && (command == null || command.isEmpty() || command.size > 65_536 || command.contains(0) || timeout == null || timeout !in 1..180_000)) return refused()
        fun currentRequest() = granted && generation.get() == current && nonce == requestNonce && admit()
        if (!currentRequest()) return refused("expired")
        if (op != "adb.shell" && args.length() != 0 &&
            !(op == "adb.ready" && args.length() == 1 && args.opt("include_build_id") is Boolean)) return refused()
        return withContext(dispatcher) {
            if (op == "adb.shell" || op == "adb.recover") {
                val digest = MessageDigest.getInstance("SHA-256").digest(command ?: ByteArray(0)).joinToString("") { "%02x".format(it) }
                val payload = "$op:$requestNonce:$timeout:$digest"
                var approval: io.panelassistant.android.http.DeferredSessionApproval? = null
                var approvalEpoch = 0L
                try {
                    while (true) {
                        var needsApproval = false
                        var refusal = "expired"
                        // Shell execution has its own serial queue. Approval waits and long shell
                        // replies must not hold healthy-app heartbeat or recovery behind this gate.
                        val reply = (if (op == "adb.shell") shells else effects).withLock {
                            if (!currentRequest()) return@withLock refused("expired")
                            val submit: (() -> Unit) -> Boolean = { write ->
                                RemoteDebugSecurityTransitionGate.withLock {
                                    when {
                                        !currentRequest() -> false
                                        hardened() && approval == null -> {
                                            needsApproval = true
                                            approvalEpoch = RemoteDebugSecurityTransitionGate.authorityEpoch()
                                            false
                                        }
                                        hardened() && (approvalEpoch != RemoteDebugSecurityTransitionGate.authorityEpoch() || approval?.consume?.invoke() != true) -> {
                                            refusal = "approval_timeout"
                                            false
                                        }
                                        op == "adb.recover" -> transition { write(); true }
                                        else -> { write(); true }
                                    }
                                }
                            }
                            try {
                                if (op == "adb.shell") {
                                    val result = endpoint.shell(requireNotNull(command), requireNotNull(timeout).toInt(), submit)
                                    if (result != null) PanelAssistantManageReply("applied", null, result)
                                    else if (needsApproval) null else refused(refusal)
                                } else {
                                    when (endpoint.recover(requestNonce, submit)) {
                                        true -> { publish(); PanelAssistantManageReply("applied", null, JSONObject().put("enabled", true)) }
                                        false -> PanelAssistantManageReply("failed", "local_adb_unavailable", null)
                                        null -> if (needsApproval) null else refused(refusal)
                                    }
                                }
                            } catch (_: Exception) {
                                PanelAssistantManageReply("failed", "local_adb_unavailable", null)
                            }
                        }
                        if (reply != null) return@withContext reply
                        approval?.cancel?.invoke()
                        approval = authorize(payload, onPending, ::currentRequest)
                        approval.refusal?.let { return@withContext refused(it) }
                    }
                } finally {
                    approval?.cancel?.invoke()
                }
            }
            effects.withLock {
                if (!currentRequest()) return@withLock refused("expired")
                try {
                    val result = when (op) {
                        "adb.prepare" -> prepare()
                        "adb.ready" -> {
                            val state = publish()
                            JSONObject().put("ready", state != null).put("managed", state?.managed == true).put("recovery_hold", state?.recoveryHold == true).put("security_unverified", state?.securityUnverified == true).also {
                                if (args.opt("include_build_id") == true) it.put("build_id", state?.buildId ?: JSONObject.NULL)
                            }
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
        fun create(context: Context, scope: CoroutineScope, config: Config, daemon: Daemon = HelperClient): LocalAdbRuntime {
            val staging = LocalAdbAsset(context)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            return LocalAdbRuntime(scope, RootLocalAdbEndpoint(), { config.hardenedSecurityEnabled }, staging::prepare, {
                daemon.ensureAdbRecovery()
            }, authorize = { payload, pending, admitted ->
                io.panelassistant.android.http.awaitSessionApproval(
                    io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                    payload, "Run Panel Assistant maintenance", io.panelassistant.android.security.LocalApprovalBroker.instance, pending, admitted = admitted,
                )
            }, onClosed = { io.panelassistant.android.control.AdbController(context, config).resealManagedHardened() }, healthy = {
                kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { acknowledgement ->
                        main.post { if (acknowledgement.isActive) acknowledgement.resume(true) }
                    }
                } == true
            }, rootKnownUnusable = { Su.rootKnownUnusable() }, daemon = daemon)
        }
    }
}

internal class LocalAdbAsset(
    private val cacheDirectory: () -> File,
    private val readAsset: () -> ByteArray,
    private val chmod: (File, Int) -> Unit,
) {
    constructor(context: Context) : this({ context.cacheDir }, {
        val name = requireNotNull(helperAssetName(android.os.Build.SUPPORTED_ABIS.asIterable()))
        context.assets.open(name).use { it.readBytes() }
    }, { file, mode -> android.system.Os.chmod(file.absolutePath, mode) })

    @Synchronized fun prepare(): JSONObject {
        val bytes = readAsset()
        require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = File(cacheDirectory(), "adb-recovery")
        check(directory.mkdirs() || directory.isDirectory)
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        // cache directories may inherit setgid; the bootstrap contract requires exact 0700.
        chmod(directory, 0x1c0)
        val path = File(directory, "helper-$hash")
        check(!java.nio.file.Files.isSymbolicLink(path.toPath()))
        java.io.FileOutputStream(path).use { it.write(bytes); it.fd.sync() }
        chmod(path, 0x180)
        return JSONObject().put("path", path.absolutePath).put("sha256", hash).put("build_id", BuildConfig.HELPER_BUILD_ID)
    }
}

internal class RootLocalAdbEndpoint : LocalAdbEndpoint {
    private fun <T> exchange(timeoutMs: Int = 20_000, body: (android.net.LocalSocket) -> T): T =
        openRootAbstractSocket("hapaneld-adb-recovery").use { socket ->
            socket.soTimeout = timeoutMs
            body(socket)
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
    /** One absolute send deadline, including a root peer that stops reading midway through a payload. */
    private fun writeRequest(socket: android.net.LocalSocket, bytes: ByteArray) {
        // Wait for POLLOUT under the deadline, then write a chunk small enough to fit the free space POLLOUT promises
        // (an AF_UNIX socket reports writable only while at most a quarter of its send buffer is in use).
        val descriptor = socket.fileDescriptor
        val chunk = (socket.sendBufferSize / 8).coerceIn(1, 1_024)
        val output = socket.outputStream
        val deadline = android.os.SystemClock.elapsedRealtime() + 2_000L
        var sent = 0
        while (sent < bytes.size) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            check(remaining > 0) { "local adb submission timed out" }
            val ready = android.system.StructPollfd().apply { fd = descriptor; events = android.system.OsConstants.POLLOUT.toShort() }
            try {
                if (android.system.Os.poll(arrayOf(ready), remaining.toInt()) <= 0) continue
            } catch (failure: android.system.ErrnoException) {
                if (failure.errno == android.system.OsConstants.EINTR) continue
                throw failure
            }
            val count = minOf(chunk, bytes.size - sent)
            output.write(bytes, sent, count)
            sent += count
        }
    }
    private fun command(text: String, submit: (() -> Unit) -> Boolean = { it(); true }): Boolean? = exchange { socket ->
        if (!submit { writeRequest(socket, (text + "\n").toByteArray()) }) null else line(socket.inputStream) == "OK"
    }

    override fun state(): LocalAdbState? = runCatching {
        exchange { socket ->
            writeRequest(socket, "STATUS\n".toByteArray())
            val fields = line(socket.inputStream).split(' ')
            check(fields.size == 6 && fields[0] == "READY" && fields[1] == "1" && fields[2].matches(Regex("[0-9a-f]{64}")) && fields[3] in listOf("0", "1") && fields[4] in listOf("0", "1") && fields[5] in listOf("0", "1"))
            LocalAdbState(fields[3] == "1", fields[4] == "1", fields[5] == "1", fields[2])
        }
    }.getOrNull()
    override fun arm(nonce: String): Boolean = command("ARM $nonce") == true
    override fun beat(nonce: String): Boolean = command("BEAT $nonce") == true
    override fun recover(nonce: String, submit: (() -> Unit) -> Boolean): Boolean? = command("RECOVER $nonce", submit)
    override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? = exchange(timeoutMs + 5_000) { socket ->
        val input = socket.inputStream
        if (!submit { writeRequest(socket, "SHELL $timeoutMs ${command.size}\n".toByteArray() + command) }) return@exchange null
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
