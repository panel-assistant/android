package io.github.maxlyth.hapaneld.http

import java.util.Locale

/** Read-only process classification. A runit/pm2 supervisor alone does not mean the bridge is running. */
internal object TermuxBridgeProbe {
    enum class State { NOT_APPLICABLE, RUNNING, ABSENT, UNKNOWN }

    fun diagnosticLine(state: State): String? =
        if (state == State.NOT_APPLICABLE) null
        else "[panel-zigbee-bridge] state=${state.name.lowercase(Locale.ROOT)}"

    private data class Process(val pid: Int, val command: String)

    private fun processes(ps: String): List<Process> = ps.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        val pid = parts.firstOrNull()?.toIntOrNull()
        if (pid == null || parts.size != 2) null else Process(pid, parts[1])
    }.toList()

    fun candidatePids(ps: String): List<Int> = processes(ps).filter { process ->
        val executable = process.command.substringBefore(' ').substringAfterLast('/').lowercase(Locale.ROOT)
        executable == "node" || executable == "zigbee2mqtt" || executable == "zigbee2cube"
    }.map { it.pid }

    /** The production route collector; the supplied commands are the same privileged seams used by the server. */
    fun collect(
        termuxUid: Result<Int?>,
        routes: () -> Pair<Boolean, Boolean>,
        rootRun: (String, Long, Long) -> String?,
        helperRun: (String) -> String?,
    ): State {
        if (termuxUid.isFailure) return State.UNKNOWN
        val uid = termuxUid.getOrNull() ?: return State.NOT_APPLICABLE
        val (rootReady, helperReady) = routes()
        val ps = if (rootReady) rootRun("ps -A -o PID=,ARGS=", 128L * 1024L, 3_000L) else null
        val candidates = ps?.let(::candidatePids).orEmpty()
        val details = if (candidates.isNotEmpty() && candidates.size <= 32) {
            val pids = candidates.joinToString(" ")
            rootRun(
                "for p in $pids; do u=\$(stat -c %u /proc/\$p 2>/dev/null); " +
                    "c=\$(readlink /proc/\$p/cwd 2>/dev/null); printf '%s\\t%s\\t%s\\n' \"\$p\" \"\$u\" \"\$c\"; done",
                8L * 1024L, 3_000L,
            )
        } else null
        val dump = if (ps.isNullOrBlank() && helperReady) helperRun("PERFDUMP BRIDGE $uid") else null
        return observe(true, uid, dump, ps, details)
    }

    /** Details lines are PID, numeric UID, and /proc/PID/cwd from the same privileged route. */
    fun observe(termuxInstalled: Boolean, termuxUid: Int, helperDump: String?, rootPs: String?, rootDetails: String?): State {
        if (!termuxInstalled) return State.NOT_APPLICABLE
        if (rootPs != null && processes(rootPs).isNotEmpty()) {
            val candidates = processes(rootPs).filter { it.pid in candidatePids(rootPs) }
            if (candidates.isEmpty()) return State.ABSENT
            val details = rootDetails?.lineSequence()?.mapNotNull { line ->
                val columns = line.split('\t', limit = 3)
                val pid = columns.firstOrNull()?.toIntOrNull()
                if (columns.size != 3 || pid == null) null else pid to (columns[1].toIntOrNull() to columns[2])
            }?.toMap().orEmpty()
            var uncertain = false
            for (candidate in candidates) {
                val detail = details[candidate.pid]
                if (detail == null || detail.first == null) { uncertain = true; continue }
                if (detail.first == termuxUid) {
                    when (isBridgeCommand(candidate.command, detail.second)) {
                        State.RUNNING -> return State.RUNNING
                        State.UNKNOWN -> uncertain = true
                        else -> Unit
                    }
                }
            }
            return if (uncertain) State.UNKNOWN else State.ABSENT
        }
        if (helperDump == null || !helperDump.contains("@PROC\n") || !helperDump.contains("@END")) return State.UNKNOWN
        // Old helpers do not know the UID/cwd-aware marker and cannot prove a negative.
        return when (helperDump.lineSequence().firstOrNull { it.startsWith("@BRIDGE ") }
            ?.removePrefix("@BRIDGE ")?.trim()) {
            "1" -> State.RUNNING
            "0" -> State.ABSENT
            else -> State.UNKNOWN
        }
    }

    private fun isBridgeCommand(command: String, cwd: String): State {
        val args = command.split(Regex("\\s+"))
        val executable = args.firstOrNull()?.substringAfterLast('/')?.lowercase(Locale.ROOT) ?: return State.ABSENT
        if (executable == "zigbee2mqtt" || executable == "zigbee2cube") return State.RUNNING
        if (executable != "node") return State.ABSENT
        val script = args.getOrNull(1)?.lowercase(Locale.ROOT) ?: return State.ABSENT
        if (script.substringAfterLast('/') != "index.js") return State.ABSENT
        val entrypoints = listOf("/zigbee2mqtt/index.js", "/zigbee2cube/index.js", "/05cube-ts/dist/index.js")
        if (entrypoints.any(script::endsWith)) return State.RUNNING
        if (script.startsWith('/')) return State.ABSENT
        if (cwd.isBlank()) return State.UNKNOWN
        val directory = cwd.lowercase(Locale.ROOT).trimEnd('/')
        return if ((script == "index.js" && listOf("/zigbee2mqtt", "/zigbee2cube", "/05cube-ts/dist").any(directory::endsWith)) ||
            (script == "dist/index.js" && directory.endsWith("/05cube-ts"))) State.RUNNING else State.ABSENT
    }
}
