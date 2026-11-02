package io.panelassistant.android.util

import io.panelassistant.android.device.WebViewSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Package facts and the engine bound to this process are separate observations. */
internal data class WebViewObservation(
    val profileId: String,
    val sdk: Int,
    val abis: Set<String>,
    val processId: String,
    val pid: Int,
    val processStartedMs: Long,
    val providerPackage: String?,
    val versionName: String?,
    val versionCode: Long?,
    val signers: Set<String>,
    val installerPackage: String?,
    val playManaged: Boolean?,
    val engineVersion: String?,
    val route: String,
    val boundPackage: String? = null,
    val boundVersionName: String? = null,
    val boundVersionCode: Long? = null,
)

internal data class WebViewApk(
    val pkg: String,
    val versionName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val minSdk: Int,
    val abis: Set<String>,
)

/** Production IO is injected into the same owner the HTTP and native requests call. */
internal interface WebViewUpdateIo {
    val directory: File
    fun observe(): WebViewObservation
    fun inspect(file: File): WebViewApk?
    fun readReceipt(): JSONObject?
    fun writeReceipt(receipt: JSONObject)
    fun download(spec: WebViewSpec, destination: File): Boolean
    fun installedMatches(spec: WebViewSpec): Boolean
    suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome
}

/** One owner-started, pinned, ordinary package replacement. Observing never submits an install. */
internal class WebViewUpdate(
    private val scope: CoroutineScope,
    private val candidate: () -> Pair<String, WebViewSpec>?,
    private val io: WebViewUpdateIo,
    private val requestRebind: () -> Boolean = { false },
) {
    private val lock = Any()
    private var loaded = false
    private var unreadable = false
    private var receipt: JSONObject? = null
    private var active = false
    private var rebindRequested = false

    data class Reply(val status: Int, val body: JSONObject)

    fun observe(): JSONObject = synchronized(lock) {
        val observation = io.observe()
        loadAndReconcile(observation)
        maybeRebind(observation)
        snapshot(observation)
    }

    fun install(requestId: String?, artifactSha256: String?): Reply = synchronized(lock) {
        val observation = io.observe()
        loadAndReconcile(observation)
        if (requestId == null || !REQUEST_ID.matches(requestId) || artifactSha256 == null || !SHA256.matches(artifactSha256)) {
            return@synchronized answer(400, observation, false, "invalid_request")
        }
        receipt?.takeIf { it.optString("request_id") == requestId }?.let {
            return@synchronized if (it.optString("artifact_sha256") == artifactSha256) answer(200, observation, true)
            else answer(409, observation, false, "request_conflict")
        }
        if (active) return@synchronized answer(409, observation, false, "busy")
        if (!scope.isActive) return@synchronized answer(503, observation, false, "service_stopping")
        val selected = candidate()
        val reason = refusal(observation, selected?.second)
        if (reason != null) return@synchronized answer(200, observation, false, reason)
        val spec = requireNotNull(selected).second
        if (artifactSha256 != spec.apkSha256) return@synchronized answer(409, observation, false, "candidate_changed")
        val ticket = InstallProgress.start("WebView") ?: return@synchronized answer(409, observation, false, "busy")
        try {
            save(transaction(requestId, spec, observation.processId, "downloading"))
        } catch (_: Exception) {
            InstallProgress.finish(ticket, "WebView receipt could not be saved")
            return@synchronized answer(503, observation, false, "receipt_unavailable")
        }
        active = true
        val job = scope.launch(Dispatchers.IO) { runInstall(requestId, spec, ticket) }
        // Completion also runs when a cancelled parent prevents the coroutine body from starting.
        job.invokeOnCompletion {
            synchronized(lock) {
                if (active && receipt?.optString("request_id") == requestId) {
                    val pending = receipt
                    if (pending?.optString("state") in setOf("downloading", "verified")) {
                        runCatching { save(transaction(requestId, spec, observation.processId, "deferred", "interrupted")) }
                    }
                    active = false
                }
            }
        }
        InstallProgress.finishOnFailure(ticket, job)
        answer(202, observation, true)
    }

    private fun loadAndReconcile(observation: WebViewObservation) {
        // A replacement HTTP owner in the same process must see the running owner's final receipt.
        if (!loaded || !active) {
            loaded = true
            try {
                receipt = io.readReceipt()
                receipt?.let {
                    require(it.optInt("schema") == 1 && REQUEST_ID.matches(it.getString("request_id")) &&
                        SHA256.matches(it.getString("artifact_sha256")) && it.getString("state") in STATES)
                }
                unreadable = false
            } catch (_: Exception) {
                unreadable = true
                receipt = null
            }
        }
        if (active || (InstallProgress.running && InstallProgress.component == "WebView")) return
        val previous = receipt ?: return
        val state = previous.optString("state")
        // Only an uncertain package submission needs evidence for the same candidate.
        val spec = if (state == "submitted_unknown") {
            candidate()?.second?.takeIf { it.apkSha256 == previous.optString("artifact_sha256") } ?: return
        } else null
        val next = when {
            state == "submitted_unknown" && spec != null && io.installedMatches(spec) -> "installed"
            state == "downloading" || state == "verified" -> "deferred"
            else -> return
        }
        runCatching { save(JSONObject(previous.toString()).put("state", next).put("reason", if (next == "deferred") "interrupted" else JSONObject.NULL)) }
        if (next == "installed" && spec != null) stage(spec).delete()
    }

    private suspend fun runInstall(requestId: String, spec: WebViewSpec, ticket: InstallProgress.Ticket) {
        val completed = stage(spec)
        val partial = File(completed.path + ".part")
        var result = "deferred"
        var reason: String? = "interrupted"
        try {
            io.directory.mkdirs()
            partial.delete()
            // A resumed complete stage is never trusted by presence, filename, or an old receipt.
            if (!validStage(completed, spec)) {
                completed.delete()
                if (!io.download(spec, partial)) { reason = "download_failed"; return }
                if (!validStage(partial, spec)) { result = "rejected"; reason = "artifact_mismatch"; return }
                if (!partial.renameTo(completed)) { reason = "staging_failed"; return }
            }
            synchronized(lock) { save(transaction(requestId, spec, io.observe().processId, "verified")) }
            val outcome = io.install(completed) {
                synchronized(lock) {
                    val latest = io.observe()
                    val refusal = if (candidate()?.second != spec) "candidate_changed" else refusal(latest, spec)
                    if (refusal != null) InstallOutcome.Rejected(refusal)
                    else if (!validStage(completed, spec)) InstallOutcome.Rejected("artifact_mismatch")
                    else try {
                        // Durable before any submission: process/socket loss can never authorize replay.
                        save(transaction(requestId, spec, latest.processId, "submitted_unknown"))
                        null
                    } catch (_: Exception) {
                        InstallOutcome.Retryable("receipt_unavailable")
                    }
                }
            }
            when (outcome) {
                InstallOutcome.Succeeded -> {
                    result = if (io.installedMatches(spec)) "installed" else "submitted_unknown"
                    reason = if (result == "installed") null else "readback_unavailable"
                }
                is InstallOutcome.Rejected -> { result = "rejected"; reason = "install_refused" }
                is InstallOutcome.Retryable -> {
                    result = if (outcome.mayHaveCommitted) "submitted_unknown" else "deferred"
                    reason = if (outcome.mayHaveCommitted) "reply_lost" else "install_unavailable"
                }
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (receipt?.optString("state") == "submitted_unknown") { result = "submitted_unknown"; reason = "reply_lost" }
                else reason = "operation_failed"
            }
        } finally {
            partial.delete()
            synchronized(lock) {
                runCatching { save(transaction(requestId, spec, io.observe().processId, result, reason)) }
                active = false
            }
            if (result == "installed" || result == "rejected") completed.delete()
            InstallProgress.finish(ticket, if (result == "installed") "WebView installed" else "WebView update deferred: ${reason ?: result}")
            synchronized(lock) { maybeRebind(io.observe()) }
        }
    }

    /** Only a durably verified package replacement can request the existing deferred process boundary. */
    private fun maybeRebind(observed: WebViewObservation) {
        if (active || InstallProgress.running || rebindRequested || receipt?.optString("state") != "installed") return
        val spec = candidate()?.second?.takeIf { it.apkSha256 == receipt?.optString("artifact_sha256") } ?: return
        if (boundOld(observed, spec)) rebindRequested = requestRebind()
    }

    private fun boundOld(observed: WebViewObservation, spec: WebViewSpec): Boolean =
        observed.boundPackage == AppInstaller.WEBVIEW_PKG &&
            ((observed.boundVersionCode != null && observed.boundVersionCode < spec.versionCode) ||
                (observed.engineVersion?.let { compareEngine(it, spec.version) }?.let { it < 0 } == true))

    private fun stage(spec: WebViewSpec) = File(io.directory, "${spec.apkSha256}.apk")

    private fun validStage(file: File, spec: WebViewSpec): Boolean = runCatching {
        if (!file.isFile || file.length() != spec.size || AppInstaller.sha256(file) != spec.apkSha256) return@runCatching false
        val apk = io.inspect(file) ?: return@runCatching false
        apk.pkg == AppInstaller.WEBVIEW_PKG && apk.versionName == spec.version && apk.versionCode == spec.versionCode &&
            apk.signers == setOf(spec.certSha256) && apk.minSdk == spec.minSdk && apk.abis == spec.abis
    }.getOrDefault(false)

    private fun save(value: JSONObject) { io.writeReceipt(value); receipt = value }

    private fun refusal(observed: WebViewObservation, spec: WebViewSpec?): String? = when {
        unreadable -> "receipt_unreadable"
        spec == null -> "no_candidate"
        spec.installClass != "same_package_same_signer" -> "unsupported_install_class"
        observed.sdk < spec.minSdk || (spec.maxSdk != null && observed.sdk > spec.maxSdk) -> "unsupported_sdk"
        observed.abis.intersect(spec.abis).isEmpty() -> "unsupported_abi"
        observed.providerPackage != AppInstaller.WEBVIEW_PKG -> "different_provider"
        observed.playManaged == true -> "play_managed"
        observed.playManaged == null -> "installer_unknown"
        observed.signers != setOf(spec.certSha256) -> "signer_mismatch"
        observed.versionCode == null || observed.engineVersion == null -> "version_unknown"
        observed.versionCode > spec.versionCode -> "current"
        compareEngine(observed.engineVersion, spec.version) == null -> "version_unknown"
        compareEngine(observed.engineVersion, spec.version)!! > 0 -> "current"
        observed.versionCode == spec.versionCode && compareEngine(observed.engineVersion, spec.version) == 0 -> "current"
        observed.route == "none" -> "no_install_route"
        else -> null
    }

    private fun answer(status: Int, observed: WebViewObservation, accepted: Boolean, reason: String? = null) =
        Reply(status, snapshot(observed).put("accepted", accepted).also { if (reason != null) it.put("reason", reason) })

    private fun snapshot(observed: WebViewObservation): JSONObject {
        val selected = candidate()
        val spec = selected?.second
        val reason = refusal(observed, spec)
        val transaction = receipt?.let { JSONObject(it.toString()) }
        val progress = InstallProgress.presentationSnapshot()
        // Disk stays uncertain before submission. Only the live shared owner can say it is still working.
        if (transaction?.optString("state") == "submitted_unknown" && progress.running && progress.component == "WebView") {
            transaction.put("state", "installing")
        }
        // Package readback finishes before a deferred process boundary can bind the new engine.
        if (transaction?.optString("state") == "installed" && spec != null &&
            transaction.optString("artifact_sha256") == spec.apkSha256 && boundOld(observed, spec)
        ) {
            transaction.put("state", "installing")
        }
        return JSONObject().put("schema", 1).put("supported", true).put("eligible", reason == null)
            .put("reason", reason ?: JSONObject.NULL).put("profile_id", observed.profileId)
            .put("android_sdk", observed.sdk).put("supported_abis", JSONArray(observed.abis.toList()))
            .put("process", JSONObject().put("id", observed.processId).put("pid", observed.pid).put("started_elapsed_ms", observed.processStartedMs))
            .put("provider", JSONObject().put("package", observed.providerPackage ?: JSONObject.NULL)
                .put("version_name", observed.versionName ?: JSONObject.NULL).put("version_code", observed.versionCode ?: JSONObject.NULL)
                .put("signer_sha256", JSONArray(observed.signers.toList())).put("installer_package", observed.installerPackage ?: JSONObject.NULL)
                .put("play_managed", observed.playManaged ?: JSONObject.NULL).put("engine_version", observed.engineVersion ?: JSONObject.NULL))
            .put("process_provider", JSONObject().put("package", observed.boundPackage ?: JSONObject.NULL)
                .put("version_name", observed.boundVersionName ?: JSONObject.NULL).put("version_code", observed.boundVersionCode ?: JSONObject.NULL)
                .put("source", "process_default_user_agent"))
            .put("candidate", if (spec == null) JSONObject.NULL else JSONObject()
                .put("artifact_id", selected.first).put("package", AppInstaller.WEBVIEW_PKG).put("version_name", spec.version)
                .put("version_code", spec.versionCode).put("engine_version", spec.version).put("sha256", spec.apkSha256)
                .put("signer_sha256", JSONArray(listOf(spec.certSha256))).put("abis", JSONArray(spec.abis.toList()))
                .put("min_sdk", spec.minSdk).put("max_sdk", spec.maxSdk ?: JSONObject.NULL).put("size", spec.size).put("install_class", spec.installClass))
            .put("transaction", transaction ?: JSONObject.NULL)
    }

    private fun transaction(id: String, spec: WebViewSpec, process: String, state: String, reason: String? = null) = JSONObject()
        .put("schema", 1).put("request_id", id).put("artifact_sha256", spec.apkSha256).put("state", state)
        .put("reason", reason ?: JSONObject.NULL).put("process_id", process)

    companion object {
        private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val STATES = setOf("downloading", "verified", "submitted_unknown", "installed", "deferred", "rejected")
        internal fun compareEngine(left: String, right: String): Int? {
            fun parts(raw: String): List<Long>? = raw.split('.').takeIf { it.size == 4 }?.map { it.toLongOrNull() ?: return null }
            val a = parts(left) ?: return null
            val b = parts(right) ?: return null
            for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
            return 0
        }
    }
}
