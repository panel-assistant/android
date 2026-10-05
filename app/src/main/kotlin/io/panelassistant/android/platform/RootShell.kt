package io.panelassistant.android.platform

/**
 * The root-command boundary — a thin seam over [io.panelassistant.android.control.Su] so callers can
 * depend on an interface (and be unit-tested with a fake) instead of the concrete su executor. Production
 * injects the `Su` object; tests inject a fake. Behaviour is unchanged; this only adds a test seam.
 */
/** What one root command attempt found. [NO_LAUNCH] is the only device-level answer. */
enum class RootRunOutcome { RAN_OK, RAN_FAILED, NO_LAUNCH }

interface RootShell {
    /** True if any su form works (a `su true` succeeds). */
    fun available(): Boolean

    /** Run [cmd] as root, waiting for completion; true on exit 0. */
    fun run(cmd: String): Boolean

    /**
     * Run [cmd] as root and report what the attempt itself found.
     *
     * [RootRunOutcome.NO_LAUNCH] means no root process was ever created — the su binary is absent, or it
     * is present and this app may not execute it. Both are properties of the device: the shipped su on
     * at least one supported panel is mode 4750 `root:shell`, so an app outside that group is refused
     * with EACCES rather than ENOENT while the file plainly exists. A root manager that ran the command
     * and returned non-zero is [RootRunOutcome.RAN_FAILED] and can succeed on the next attempt.
     *
     * The default reports only RAN_OK/RAN_FAILED, so no fake or older implementation can claim a
     * capability is structurally absent.
     */
    fun runClassified(cmd: String): RootRunOutcome =
        if (run(cmd)) RootRunOutcome.RAN_OK else RootRunOutcome.RAN_FAILED

    /** Run exactly one command attempt, waiting at most [timeoutMs]. Implementations must not retry
     * [cmd] after an ambiguous timeout. The default preserves test/legacy implementations. */
    fun runSingleAttempt(cmd: String, timeoutMs: Long = 5_000L): Boolean = run(cmd)

    /** Run [cmd] as root and return its stdout, or null on failure / no su. */
    fun runOutput(cmd: String): String?

    /** Bounded diagnostic output on a lane independent of interactive hardware control. Production
     * implementations must enforce [timeoutMs]; the default keeps test/legacy fakes compatible. */
    fun runOutputIsolatedBounded(cmd: String, maxBytes: Long, timeoutMs: Long = 10_000L): String? =
        runOutput(cmd)?.takeIf { it.toByteArray(Charsets.UTF_8).size.toLong() <= maxBytes }

    /** Run [cmd] as root and return its raw stdout bytes (e.g. a screenshot), or null. */
    fun runBytes(cmd: String): ByteArray?

    /** Bounded raw-byte variant. Implementations that can enforce the ceiling while streaming should
     * override it; the default still prevents oversized data from escaping test/legacy fakes. */
    fun runBytesBounded(cmd: String, maxBytes: Long): ByteArray? =
        runBytes(cmd)?.takeIf { it.size.toLong() <= maxBytes }

    /** Submit [cmd] as root without waiting (for commands like `reboot` that kill the process).
     *  Returns true only when a root process was successfully started. */
    fun fireAndForget(cmd: String): Boolean

    /** Typed sysfs boundary for profile-selected hardware nodes. Callers never compose shell text. */
    fun listSysfs(path: String): String? {
        require(safeSysfsPath(path))
        return runOutput("ls $path 2>/dev/null")
    }

    fun readSysfs(path: String): String? {
        require(safeSysfsPath(path))
        return runOutput("cat $path 2>/dev/null")
    }

    fun writeSysfs(path: String, value: String): Boolean {
        require(safeSysfsPath(path))
        require(value.matches(Regex("[A-Za-z0-9_-]{1,32}")))
        return run("printf '%s' '$value' > $path")
    }

    fun prepareOutputGpio(gpio: Int): Boolean {
        require(gpio in 0..4095)
        val dir = "/sys/class/gpio/gpio$gpio"
        return runOutput(
            "{ [ -e $dir ] || printf '%s' '$gpio' > /sys/class/gpio/export 2>/dev/null; } && " +
                "[ -e $dir/direction ] && direction=\$(cat $dir/direction 2>/dev/null) && " +
                "{ [ \"\$direction\" = out ] || printf '%s' out > $dir/direction 2>/dev/null; } && " +
                "[ \"\$(cat $dir/direction 2>/dev/null)\" = out ] && [ -w $dir/value ] && printf ready",
        )?.trim() == "ready"
    }

    /** Read-only counterpart of [prepareOutputGpio]: which of [gpios] are already exported as writable
     * outputs. It never exports a pin, changes a direction or writes a value, and runs on the bounded
     * lane independent of interactive hardware control. Null when the probe itself failed. */
    fun preparedOutputGpios(gpios: Collection<Int>, timeoutMs: Long = 5_000L): Set<Int>? {
        require(gpios.all { it in 0..4095 })
        if (gpios.isEmpty()) return emptySet()
        val probe = gpios.joinToString("; ", postfix = "; true") { gpio ->
            val dir = "/sys/class/gpio/gpio$gpio"
            "{ [ -e $dir/direction ] && [ \"\$(cat $dir/direction 2>/dev/null)\" = out ] && " +
                "[ -w $dir/value ] && echo $gpio; }"
        }
        val out = runOutputIsolatedBounded(probe, maxBytes = gpios.size * 8L, timeoutMs = timeoutMs) ?: return null
        return out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun safeSysfsPath(path: String): Boolean =
        path.startsWith("/sys/") && path.length <= 256 && path.matches(Regex("/[A-Za-z0-9_./-]+"))
}
