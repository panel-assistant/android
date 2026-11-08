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
    fun arm(nonce: String, submit: (() -> Unit) -> Boolean): Boolean {
        var armed = false
        return submit { armed = arm(nonce) } && armed
    }
    fun beat(nonce: String, submit: (() -> Unit) -> Boolean): Boolean {
        var renewed = false
        return submit { renewed = beat(nonce) } && renewed
    }
    fun recover(nonce: String, submit: (() -> Unit) -> Boolean = { it(); true }): Boolean?
    fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject?
    /** One typed helper command; null when [submit] declined to send it. */
    fun command(verb: String, args: List<String>, submit: (() -> Unit) -> Boolean): LocalAdbCommandReply? =
        error("typed commands unsupported")
}

/** The helper's answer to a typed command: its fields, a refusal code, or a held lock. */
internal sealed interface LocalAdbCommandReply {
    data class Fields(val fields: Map<String, String>) : LocalAdbCommandReply
    data class Refused(val code: String) : LocalAdbCommandReply
    data object Busy : LocalAdbCommandReply
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
    private val leaseDiagnostics: LocalAdbLeaseDiagnostics = LocalAdbLeaseDiagnostics(),
) : PanelAssistantLocalAdb {
    private val generation = AtomicLong()
    private val effects = Mutex()
    private val shells = Mutex()
    @Volatile private var nonce = ""
    @Volatile private var granted = false
    @Volatile private var compatibleHistory = false

    // A structural su refusal need not hide a compatible running ordinary helper. This short read-only
    // capability check runs per PA hello; the eventual action independently admits its carrying socket.
    override fun offered(): Boolean = !rootKnownUnusable() || endpoint.state() != null || daemon.supportsAdbRecovery()

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
            var leaseOperation = "state"
            try { effects.withLock {
                if (generation.get() != current) return@withLock
                if (!granted) {
                    if (!hardened() && endpoint.state() != null) endpoint.recover(fresh) { write ->
                        RemoteDebugSecurityTransitionGate.withLock {
                            generation.get() == current && !hardened() && transition { write(); true }
                        }
                    }
                    publish()
                    return@withLock
                }
                val state = currentDaemon()
                if (generation.get() == current && state != null && healthy()) {
                    leaseOperation = "arm"
                    endpoint.arm(fresh) { write ->
                        RemoteDebugSecurityTransitionGate.withLock {
                            generation.get() == current && transition { write(); true }
                        }
                    }.also { if (it) leaseDiagnostics.arm() else leaseDiagnostics.skip("arm_false", "arm") }
                    leaseOperation = "publish"
                    publish()
                    onClosed()
                } else if (state == null) leaseDiagnostics.skip("state_unavailable", "startup")
                  else if (generation.get() == current) leaseDiagnostics.skip("unhealthy", "startup")
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) {
                  if (leaseOperation == "state" || leaseOperation == "arm") leaseDiagnostics.skip("socket_error", leaseOperation)
                  publish()
              }
            if ((granted || (retainExistingLease && hardened())) && generation.get() == current) {
                while (scope.isActive && generation.get() == current) {
                    delay(30_000)
                    if (!healthy()) { leaseDiagnostics.skip("unhealthy"); continue }
                    val waitingSince = leaseDiagnostics.now()
                    leaseOperation = "state"
                    try { effects.withLock {
                        leaseDiagnostics.lockWait(waitingSince)
                        if (generation.get() == current) {
                            val state = if (granted) currentDaemon() else endpoint.state()
                            if (state != null && !state.managed && !state.recoveryHold && granted) {
                                leaseOperation = "arm"
                                endpoint.arm(fresh) { write ->
                                    RemoteDebugSecurityTransitionGate.withLock {
                                        generation.get() == current && transition { write(); true }
                                    }
                                }.also { if (it) leaseDiagnostics.arm() else leaseDiagnostics.skip("arm_false", "arm") }
                                leaseOperation = "publish"
                                publish()
                                onClosed()
                            }
                            else if (state != null) {
                                leaseOperation = "beat"
                                if (endpoint.beat(fresh) { write -> RemoteDebugSecurityTransitionGate.withLock {
                                    if (generation.get() == current && nonce == fresh) { write(); true } else false
                                } }) leaseDiagnostics.beat() else leaseDiagnostics.skip("beat_false", "beat")
                            } else leaseDiagnostics.skip("state_unavailable")
                            leaseOperation = "publish"
                            publish()
                        }
                    } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                      catch (_: Exception) {
                          if (leaseOperation != "publish") leaseDiagnostics.skip("socket_error", leaseOperation)
                          publish()
                      }
                }
            }
        }
    }

    /** Main recovery becomes available only after the canonical helper passes capability admission. */
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
        // A held helper lock clears by itself, so Panel Assistant may send the same command again.
        fun busy() = PanelAssistantManageReply("failed", "busy", JSONObject().put("code", "busy"))
        val (current, requestNonce) = RemoteDebugSecurityTransitionGate.withLock { generation.get() to nonce }
        val command = if (op == "adb.shell") (args.opt("command") as? String)?.toByteArray(Charsets.UTF_8) else null
        val rawTimeout = args.opt("timeout_ms")
        val timeout = if (rawTimeout is Int || rawTimeout is Long) (rawTimeout as Number).toLong() else null
        if (op == "adb.shell" && (command == null || command.isEmpty() || command.size > 65_536 || command.contains(0) || timeout == null || timeout !in 1..180_000)) return refused()
        val typed = if (op == "adb.cmd") typedCommand(args) ?: return refused() else null
        fun currentRequest() = granted && generation.get() == current && nonce == requestNonce && admit()
        if (!currentRequest()) return refused("expired")
        if (op != "adb.shell" && op != "adb.cmd" && args.length() != 0 &&
            !(op == "adb.ready" && args.length() == 1 && args.opt("include_build_id") is Boolean)) return refused()
        return withContext(dispatcher) {
            if (op == "adb.shell" || op == "adb.recover" || typed != null) {
                fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                // A typed approval binds the verb and its exact arguments, never only the op.
                val payload = if (typed != null) "$op:$requestNonce:${typed.first}:${sha256(typed.second.joinToString("\n").toByteArray(Charsets.US_ASCII))}"
                    else "$op:$requestNonce:$timeout:${sha256(command ?: ByteArray(0))}"
                var approval: io.panelassistant.android.http.DeferredSessionApproval? = null
                var approvalEpoch = 0L
                try {
                    while (true) {
                        var needsApproval = false
                        var refusal = "expired"
                        // Shell execution has its own serial queue. Approval waits and long shell
                        // replies must not hold healthy-app heartbeat or recovery behind this gate.
                        val reply = (if (op == "adb.recover") effects else shells).withLock {
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
                                if (typed != null) {
                                    when (val answer = endpoint.command(typed.first, typed.second, submit)) {
                                        is LocalAdbCommandReply.Fields -> PanelAssistantManageReply("applied", null,
                                            JSONObject().put("ok", true).put("fields", JSONObject(answer.fields as Map<*, *>)))
                                        LocalAdbCommandReply.Busy -> busy()
                                        is LocalAdbCommandReply.Refused ->
                                            if (answer.code == "busy") busy()
                                            else PanelAssistantManageReply("refused", "failed", JSONObject().put("code", answer.code))
                                        null -> if (needsApproval) null else refused(refusal)
                                    }
                                } else if (op == "adb.shell") {
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

    /** The grammar only: allowlist membership of each argument is the helper's single definition. */
    private fun typedCommand(args: JSONObject): Pair<String, List<String>>? {
        if (args.length() != 2) return null
        val verb = args.opt("verb") as? String ?: return null
        val array = args.opt("args") as? org.json.JSONArray ?: return null
        if (verb !in TYPED_COMMAND_TIMEOUT_MS || array.length() > 3) return null
        val values = (0 until array.length()).map { array.opt(it) as? String ?: return null }
        if (values.any { value -> value.isEmpty() || value.length > 159 || value.any { it.code !in 0x21..0x7e } }) return null
        // The helper reads one header line of at most 159 bytes.
        if ((listOf(verb) + values).joinToString(" ").length > 159) return null
        return verb to values
    }

    companion object {
        /** Fixed per-verb helper budgets; Panel Assistant never chooses them. HOME waits on the chooser. */
        val TYPED_COMMAND_TIMEOUT_MS: Map<String, Int> = mapOf(
            "PA_IDENTITY" to 15_000,
            "PA_REPAIR" to 30_000,
            "PA_HOME" to 60_000,
            "PA_ADMIN" to 20_000,
            "PA_VENDOR" to 30_000,
            "PA_RESTORE" to 30_000,
            "PA_OBSERVE" to 20_000,
            "PA_HANDBACK" to 20_000,
        )

        /** What the on-panel approval prompt says each typed command will do. */
        val TYPED_COMMAND_SUMMARY: Map<String, String> = mapOf(
            "PA_IDENTITY" to "Read the panel's identity for Panel Assistant",
            "PA_REPAIR" to "Repair the panel app's permissions",
            "PA_HOME" to "Make the panel app the home screen",
            "PA_ADMIN" to "Make the panel app a device administrator",
            "PA_VENDOR" to "Apply the panel maker's settings for the panel app",
            "PA_RESTORE" to "Restore the panel's saved settings",
            "PA_OBSERVE" to "Read the panel's app and home screen state",
            "PA_HANDBACK" to "Give the home screen back before removal",
        )

        /** The verb of a typed command's approval payload, or null for any other payload. */
        fun approvalSummary(payload: String): String {
            val fields = payload.split(':')
            return fields.takeIf { it.size == 4 && it[0] == "adb.cmd" }?.let { TYPED_COMMAND_SUMMARY[it[2]] }
                ?: "Run Panel Assistant maintenance"
        }

        fun create(context: Context, scope: CoroutineScope, config: Config, daemon: Daemon = HelperClient, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO): LocalAdbRuntime {
            val staging = LocalAdbAsset(context)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val leaseDiagnostics = LocalAdbLeaseDiagnostics()
            LocalAdbLeaseDiagnosticHolder.install(leaseDiagnostics)
            return LocalAdbRuntime(scope, RootLocalAdbEndpoint(daemon), { config.hardenedSecurityEnabled }, staging::prepare, {
                daemon.supportsAdbRecovery()
            }, dispatcher = dispatcher, authorize = { payload, pending, admitted ->
                io.panelassistant.android.http.awaitSessionApproval(
                    io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                    payload, approvalSummary(payload), io.panelassistant.android.security.LocalApprovalBroker.instance, pending, admitted = admitted,
                )
            }, onClosed = { io.panelassistant.android.control.AdbController(context, config).resealManagedHardened() }, healthy = {
                kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { acknowledgement ->
                        main.post { if (acknowledgement.isActive) acknowledgement.resume(true) }
                    }
                } == true
            }, rootKnownUnusable = { Su.rootKnownUnusable() }, daemon = daemon, leaseDiagnostics = leaseDiagnostics)
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

internal class RootLocalAdbEndpoint(private val daemon: Daemon = HelperClient) : LocalAdbEndpoint {
    private fun exchange(header: String, payload: ByteArray = byteArrayOf(), timeoutMs: Int = 20_000,
                         submit: (() -> Unit) -> Boolean = { it(); true }): java.io.InputStream? =
        daemon.adbRecoveryExchange(header, payload, timeoutMs, submit)?.inputStream()
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
    private fun command(text: String, submit: (() -> Unit) -> Boolean = { it(); true }): Boolean? =
        exchange(text, submit = submit)?.let { line(it) == "OK" }

    override fun state(): LocalAdbState? = runCatching {
        exchange("STATUS")?.let { input ->
            val fields = line(input).split(' ')
            check(fields.size == 6 && fields[0] == "READY" && fields[1] == "1" && fields[2].matches(Regex("[0-9a-f]{64}")) && fields[3] in listOf("0", "1") && fields[4] in listOf("0", "1") && fields[5] in listOf("0", "1"))
            LocalAdbState(fields[3] == "1", fields[4] == "1", fields[5] == "1", fields[2])
        }
    }.getOrNull()
    override fun arm(nonce: String): Boolean = command("ARM $nonce") == true
    override fun beat(nonce: String): Boolean = command("BEAT $nonce") == true
    override fun arm(nonce: String, submit: (() -> Unit) -> Boolean): Boolean = command("ARM $nonce", submit) == true
    override fun beat(nonce: String, submit: (() -> Unit) -> Boolean): Boolean = command("BEAT $nonce", submit) == true
    override fun recover(nonce: String, submit: (() -> Unit) -> Boolean): Boolean? = command("RECOVER $nonce", submit)
    override fun command(verb: String, args: List<String>, submit: (() -> Unit) -> Boolean): LocalAdbCommandReply? {
        val budget = requireNotNull(LocalAdbRuntime.TYPED_COMMAND_TIMEOUT_MS[verb])
        val reply = daemon.adbRecoveryExchange((listOf(verb) + args).joinToString(" "), byteArrayOf(), budget + 5_000, submit) ?: return null
        val end = reply.indexOf(10)
        check(end > 0)
        val head = String(reply, 0, end, Charsets.US_ASCII)
        if (head == "BUSY" && reply.size == end + 1) return LocalAdbCommandReply.Busy
        Regex("ERR ([a-z][a-z-]{0,31})").matchEntire(head)?.let { check(reply.size == end + 1); return LocalAdbCommandReply.Refused(it.groupValues[1]) }
        val count = Regex("OK (0|[1-9][0-9]{0,3})").matchEntire(head)?.groupValues?.get(1)?.toInt()
        check(count != null && count <= 4096 && reply.size == end + 1 + count) { "invalid typed command reply" }
        val body = String(reply, end + 1, count, Charsets.US_ASCII)
        check(body.isEmpty() || body.endsWith("\n"))
        val fields = LinkedHashMap<String, String>()
        body.split('\n').dropLast(1).forEach { entry ->
            val match = Regex("([A-Za-z][A-Za-z0-9_.]{0,63})=([\\x20-\\x3c\\x3e-\\x7e]{0,255})").matchEntire(entry)
            check(match != null && fields.put(match.groupValues[1], match.groupValues[2]) == null) { "invalid typed command field" }
        }
        return LocalAdbCommandReply.Fields(fields)
    }
    override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? = exchange("SHELL $timeoutMs ${command.size}", command, timeoutMs + 5_000, submit)?.let { input ->
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
