# Host-native build, unit tests, and fuzz wiring for the hapaneld-helper daemon.
#
# The shipped Android binary is cross-compiled by build.sh (Docker NDK) — it compiles the same
# src/*.c set. This Makefile is the host-side dev/CI surface: a -Werror compile check, the unit
# tests, and the source list the fuzz harness reuses (so neither test nor fuzz can silently drop a
# module). Only a host C compiler is needed.
#
#   make            # -Werror host build of the daemon (compile sanity check)
#   make test       # build + run the unit tests
#   make fuzz       # bounded ASan+UBSan parser/handler smoke (delegates to fuzz/run.sh)
#   make clean

CC      ?= cc
# -Wno-format-truncation: the PERFDUMP /proc walk intentionally builds short paths/lines into fixed
# buffers (pids and comms are small in practice) and snprintf truncates harmlessly; gcc can't prove
# the bound, and the shipped NDK clang build doesn't flag it. Everything else is -Werror-clean.
CFLAGS  ?= -O2 -g -Wall -Wextra -Werror -Wno-format-truncation
LDLIBS  ?= -lpthread

SRC_DIR := src
BUILD   := build

# Capability + transport modules — the SINGLE source list shared by the daemon, the fuzz harness, and
# the unit tests, so coverage can never silently drop a module. main.c (accept loop) and sysexec.c
# (real exec/pipe/thread/reboot) are deliberately NOT here: the fuzz and test builds swap in a stub
# sysexec and never run main, so a valid REBOOT/RELOAD/WATCH/GPIOWATCH can't exec or spawn on the build host.
# The dispatch-only ENSURE fixture and socket/fuzz fixtures additionally wrap its publisher; the
# real replacement driver separately exercises publication and detached recovery processes.
CORE_SRCS := $(addprefix $(SRC_DIR)/, identity.c adb_recovery.c util.c sha256.c led.c screen.c input.c gpio.c sysctl.c zigbee.c wifi.c adb_state.c sysfs_read.c perf.c cht8305.c vi530x.c version.c companion.c guard_maintenance.c guard_replacement.c logcat.c pa_commands.c server.c dispatch.c)
DAEMON_SRCS := $(CORE_SRCS) $(SRC_DIR)/main.c $(SRC_DIR)/sysexec.c
STUB := test/sysexec_stub.c
TEST_BUILD_ID := aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
REPLACEMENT_OLD_BUILD_ID := eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
REPLACEMENT_NEW_BUILD_ID := dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd

.PHONY: pa-allowlist-test actuator-exec-test adb-recovery-test all daemon test test-units peer-auth-test request-timeout-test replacement-identity-test cdprelay-resource-test socket-test-server fuzz contract clean print-core
all: daemon

# Host build of the real daemon — a compile sanity check (-Werror); the shipped binary is build.sh.
daemon: $(BUILD)/hapaneld-helper
$(BUILD)/hapaneld-helper: $(DAEMON_SRCS) | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(DAEMON_SRCS) $(LDLIBS) -o $@

# Unit tests: CORE modules + the sysexec stub (no real exec) + the test drivers. Every test is its own
# run-<name> target and `test` runs them in parallel (`make test TEST_JOBS=1` runs them one at a time).
# Output is grouped per target, so a failing test prints its own log and make names it; -k runs every
# test even after one fails, so one run reports every failure. Most of the wall
# time is the recovery replacement driver waiting on real deadlines, so it is split into shards
# (REPLACEMENT_SHARDS) and listed first, where make starts it first.
#
# Isolation: each test binary is compiled with its own HAPANELD_TEST_TMP (src/test_tmp.h), the directory
# that stands in for /tmp in the fixed guard, companion, install-stage, live-helper and transaction-lock
# fixture paths; run-<name> recreates it empty, root-owned and 1777 like /tmp, before the test starts.
# The directory is derived from this checkout's build path, so two checkouts never share one. The Python
# drivers make their own temporary directories, and every abstract socket name is per test or per pid.
TEST_JOBS ?= 16
UNITS_TMP := /tmp/hapaneld-units-$(shell printf '%s' '$(abspath $(BUILD))' | sha256sum | cut -c1-12)
UNIT_NAME = $(notdir $@)
UNIT_TMP = -DHAPANELD_TEST_TMP='"$(UNITS_TMP)/$(UNIT_NAME)"'
fresh_root = rm -rf -- '$(UNITS_TMP)/$(1)' && install -d -m 0755 '$(UNITS_TMP)' && mkdir -m 1777 '$(UNITS_TMP)/$(1)'

TEST_BINS := $(BUILD)/actuator-exec $(BUILD)/adb-recovery $(BUILD)/uninstall-supervisor $(BUILD)/unit $(BUILD)/hapaneld-helper $(BUILD)/companion-boundary $(BUILD)/guard-maintenance-boundary $(BUILD)/guard-maintenance-gates $(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-new $(BUILD)/sysexec-fd-boundary $(BUILD)/sysexec-platform-wrapper $(BUILD)/peer-auth $(BUILD)/request-timeout $(BUILD)/bounded-exec $(BUILD)/lifecycle-reap $(BUILD)/hapaneld-helper-old $(BUILD)/hapaneld-helper-new $(BUILD)/cdprelay-resource-test $(BUILD)/cdprelay-test $(BUILD)/logcat-fixture $(BUILD)/logcat-boundary

# Recovery replacement driver shards, <leg>:<case>+<case>...:<variant>+<variant>... Each is one invocation of
# test/adb_recovery_replacement.py with its own temporary directory, socket name and linked binaries.
# Together they select every (leg, case, variant) the unsharded driver runs, once each; the
# replacement-shard-check target proves that against the driver's own case and variant lists.
REPLACEMENT_SHARDS := \
	ensure:readiness-faults:wrong ensure:readiness-faults:malformed ensure:readiness-faults:stall \
	ensure:timeout-retry: \
	ensure:unproven-open+foreign-owner: ensure:symlink-custody+fifo-custody: ensure:scan-error+failed-open: \
	ensure:malformed-custody+receipt-sync-fails: ensure:superseded: \
	ensure:locked-fresh+stale: ensure:matching+concurrent: ensure:staggered: \
	ensure:fresh+reaper-failure: ensure:lost-ack+source-pinned: ensure:source-bound: \
	ensure:unsafe-target: ensure:publication-faults: \
	direct:timeout-retry+staggered: direct:locked-fresh+stale+matching+failed-open+unproven-open+foreign-owner: \
	direct:scan-error+concurrent+malformed-custody+symlink-custody+fifo-custody+receipt-sync-fails:
comma := ,
shard_field = $(word $(2),$(subst :, ,$(subst ::,: :,$(1)):))
shard_name = replacement-$(subst :,-,$(patsubst %:,%,$(1)))
REPLACEMENT_RUNS := $(foreach s,$(REPLACEMENT_SHARDS),run-$(call shard_name,$(s)))

UNIT_RUNS := $(REPLACEMENT_RUNS) run-adb-recovery run-uninstall-supervisor run-adb-recovery-retirement \
	run-unit run-lifecycle-reap run-adb-exec run-wifi-exec run-zigbee-role-exec run-actuator-exec \
	run-logcat-boundary run-bounded-exec run-gfxinfo-exec run-socket-framing run-guard-maintenance-boundary \
	run-zigbee-boundary run-sysexec-platform-wrapper run-cdprelay-resource-test run-guard-maintenance-gates \
	run-replacement-lease run-request-timeout run-replacement-identity run-companion-boundary run-version \
	run-sysfs-read-boundary run-stale-replacement-cli run-cdp-state-boundary run-peer-auth \
	run-sysexec-fd-boundary run-wifi-boundary run-gfxinfo-boundary run-adb-ensure-boundary run-adb-state-boundary \
	run-pa-allowlist

.PHONY: replacement-shard-check $(UNIT_RUNS) peer-auth-test request-timeout-test bounded-exec-test actuator-exec-test replacement-identity-test cdprelay-resource-test adb-recovery-test adb-recovery-retirement-test socket-framing-test lifecycle-reap-test
test:
	@started=$$(date +%s); status=0; \
	$(MAKE) --no-print-directory -j$(TEST_JOBS) test-binaries && \
	$(MAKE) --no-print-directory -k -j$(TEST_JOBS) --output-sync=target test-units || status=$$?; \
	echo "helper unit tests: $$(( $$(date +%s) - started )) s, exit $$status"; exit $$status

# With FUZZ_SECONDS > 0 the robustness runs (run-fuzz-*, below) join both phases: their drivers build
# in test-binaries, and the runs come first in test-units because make starts prerequisites in order.
# FUZZ_SECS is FUZZ_SECONDS as a plain decimal, so 00, 035 or +5 cannot reach libFuzzer unnormalized
# (it reads -max_total_time=0 as no limit); anything that is not a whole number stops make.
FUZZ_SECS = $(or $(shell expr '$(or $(FUZZ_SECONDS),0)' + 0 2>/dev/null | grep -Ex '[0-9]+'),$(error FUZZ_SECONDS must be a whole number of seconds, got '$(FUZZ_SECONDS)'))
FUZZ_RUNS = $(if $(filter-out 0,$(FUZZ_SECS)),run-fuzz-parser run-fuzz-others)
test-binaries: $(if $(FUZZ_RUNS),fuzz-binaries)
test-units: $(FUZZ_RUNS) replacement-shard-check $(UNIT_RUNS)

# A binary test: a fresh fixture root, then the binary.
UNIT_BINARY_RUNS := $(filter-out $(REPLACEMENT_RUNS) run-pa-allowlist run-adb-recovery run-adb-recovery-retirement run-replacement-lease run-replacement-identity run-version run-cdprelay-resource-test,$(UNIT_RUNS))
$(UNIT_BINARY_RUNS): run-%: $(BUILD)/%
	@$(call fresh_root,$*)
	$(BUILD)/$*
run-replacement-lease: $(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-new
	@$(call fresh_root,replacement-lease)
	$(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-new
run-version: $(BUILD)/hapaneld-helper
	test "$$($(BUILD)/hapaneld-helper --version)" = "HELPER version=1.17.0 proto=1.17"
run-replacement-identity: $(BUILD)/hapaneld-helper-old $(BUILD)/hapaneld-helper-new
	bash ./test/replacement_identity_test.sh $(BUILD)/hapaneld-helper-old $(BUILD)/hapaneld-helper-new
run-pa-allowlist: $(BUILD)/pa-allowlist-json
	bash ./test/pa_allowlist_check.sh $(BUILD)/pa-allowlist-json
run-cdprelay-resource-test: $(BUILD)/cdprelay-resource-test $(BUILD)/cdprelay-test
	$(BUILD)/cdprelay-resource-test $(BUILD)/cdprelay-test
# adb-recovery keeps the default /tmp fixture root: HelperSocketCompositionTest (Gradle) runs this binary
# from its own BUILD, where no per-test root exists, and no other test here uses the default names.
run-adb-recovery: $(BUILD)/adb-recovery
	$(BUILD)/adb-recovery
run-adb-recovery-retirement: $(BUILD)/adb-recovery-retirement
	$(BUILD)/adb-recovery-retirement

# The shards compile the sources that read none of the driver's per-run defines once, here, and each
# shard links them into its own binaries (a distinct executable inode per shard).
REPLACEMENT_OBJ := $(BUILD)/replacement-obj
REPLACEMENT_SHARED_SRCS := $(filter-out $(SRC_DIR)/adb_recovery.c $(SRC_DIR)/identity.c $(SRC_DIR)/version.c,$(CORE_SRCS)) $(SRC_DIR)/main.c $(SRC_DIR)/sysexec.c
REPLACEMENT_OBJS := $(patsubst $(SRC_DIR)/%.c,$(REPLACEMENT_OBJ)/%.o,$(REPLACEMENT_SHARED_SRCS))
$(REPLACEMENT_OBJ)/%.o: $(SRC_DIR)/%.c $(wildcard $(SRC_DIR)/*.h) $(SRC_DIR)/commands.def
	@mkdir -p $(REPLACEMENT_OBJ)
	cc -O1 -g -Wall -Wextra -Werror -Wno-format-truncation -I$(SRC_DIR) -c $< -o $@
define replacement_shard
run-$(call shard_name,$(1)): $(BUILD)/adb-recovery-replacement $(REPLACEMENT_OBJS)
	RECOVERY_REPLACEMENT_OBJECTS='$(abspath $(REPLACEMENT_OBJ))' RECOVERY_REPLACEMENT_LEGS=$(call shard_field,$(1),1) \
		RECOVERY_REPLACEMENT_CASES=$(subst +,$(comma),$(call shard_field,$(1),2)) $(if $(call shard_field,$(1),3),RECOVERY_REPLACEMENT_VARIANTS=$(subst +,$(comma),$(call shard_field,$(1),3))) \
		$(BUILD)/adb-recovery-replacement
endef
$(foreach s,$(REPLACEMENT_SHARDS),$(eval $(call replacement_shard,$(s))))

# Everything the runs need, built before any test starts so compiling never competes with a test's
# deadlines. The Python drivers compile their few per-run sources at nice 10 for the same reason.
.PHONY: test-binaries
test-binaries: $(patsubst run-%,$(BUILD)/%,$(UNIT_BINARY_RUNS)) $(BUILD)/adb-recovery $(BUILD)/adb-recovery-retirement \
	$(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-new $(BUILD)/hapaneld-helper $(BUILD)/hapaneld-helper-old \
	$(BUILD)/hapaneld-helper-new $(BUILD)/cdprelay-resource-test $(BUILD)/cdprelay-test $(BUILD)/adb-recovery-replacement $(BUILD)/pa-allowlist-json \
	$(REPLACEMENT_OBJS)

replacement-shard-check:
	@python3 test/replacement_shards.py test/adb_recovery_replacement.py $(REPLACEMENT_SHARDS)

# --wrap=read also injects short reads and errors for fixed governor nodes.
# --wrap=open/ioctl lets tests provide a fake evdev node and capture requested exclusive grabs while
# preserving the existing ledjni ioctl assertions; --wrap=write forces deterministic stream edge cases.
$(BUILD)/unit: $(CORE_SRCS) $(STUB) test/unit.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -I$(SRC_DIR) $(CORE_SRCS) $(STUB) test/unit.c $(LDLIBS) -Wl,--wrap=open -Wl,--wrap=ioctl -Wl,--wrap=write -Wl,--wrap=read -Wl,--wrap=__read_chk -o $@

# The typed-command allowlist as JSON, compared with test/pa-typed-allowlist.json by value.
pa-allowlist-test: $(BUILD)/pa-allowlist-json
	bash ./test/pa_allowlist_check.sh $(BUILD)/pa-allowlist-json
$(BUILD)/pa-allowlist-json: $(SRC_DIR)/pa_allowlist.h test/pa_allowlist_json.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/pa_allowlist_json.c -o $@

$(BUILD)/companion-boundary: $(CORE_SRCS) $(STUB) test/companion_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) \
		test/companion_boundary.c $(LDLIBS) -Wl,--wrap=renameat -o $@

$(BUILD)/guard-maintenance-boundary: $(CORE_SRCS) $(STUB) test/guard_boundary_harness.h test/guard_maintenance_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DHAPANELD_TEST_SKIP_SELF_BINDING -DHAPANELD_BUILD_ID='"$(TEST_BUILD_ID)"' -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) \
		test/guard_maintenance_boundary.c $(LDLIBS) -o $@

$(BUILD)/stale-replacement-boundary: $(CORE_SRCS) $(STUB) test/guard_boundary_harness.h test/guard_maintenance_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DHAPANELD_TEST_SKIP_SELF_BINDING -DHAPANELD_TEST_STALE_ONLY -DHAPANELD_BUILD_ID='"$(TEST_BUILD_ID)"' -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) \
		test/guard_maintenance_boundary.c $(LDLIBS) -o $@

$(BUILD)/stale-replacement-cli: $(CORE_SRCS) $(STUB) $(SRC_DIR)/main.c test/guard_boundary_harness.h test/stale_replacement_cli.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DHAPANELD_BUILD_ID='"$(TEST_BUILD_ID)"' -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) \
		test/stale_replacement_cli.c $(LDLIBS) -o $@

$(BUILD)/guard-maintenance-gates: $(CORE_SRCS) $(STUB) test/guard_boundary_harness.h test/guard_maintenance_gates.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DHAPANELD_TEST_SKIP_SELF_BINDING -DHAPANELD_BUILD_ID='"$(TEST_BUILD_ID)"' -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) \
		test/guard_maintenance_gates.c $(LDLIBS) -o $@

$(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-new: UNIT_NAME = replacement-lease
$(BUILD)/replacement-lease-old: $(BUILD)/replacement-lease-new $(CORE_SRCS) $(STUB) test/replacement_lease_process.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DHAPANELD_BUILD_ID='"$(REPLACEMENT_OLD_BUILD_ID)"' \
		-I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/replacement_lease_process.c $(LDLIBS) -o $@

$(BUILD)/replacement-lease-new: $(CORE_SRCS) $(STUB) test/replacement_lease_process.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DHAPANELD_BUILD_ID='"$(REPLACEMENT_NEW_BUILD_ID)"' \
		-I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/replacement_lease_process.c $(LDLIBS) -o $@

$(BUILD)/sysexec-fd-boundary: $(SRC_DIR)/sysexec.c test/sysexec_fd_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(SRC_DIR)/sysexec.c test/sysexec_fd_boundary.c $(LDLIBS) -o $@

$(BUILD)/sysexec-platform-wrapper: $(SRC_DIR)/sysexec.c test/sysexec_platform_wrapper.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(SRC_DIR)/sysexec.c test/sysexec_platform_wrapper.c $(LDLIBS) -Wl,--wrap=execve -o $@

# Peer-credential policy is owned by main.c rather than CORE_SRCS. The white-box test includes that
# production implementation and opens a real listener for credential checks with inert module stubs.
peer-auth-test: run-peer-auth
$(BUILD)/peer-auth: test/peer_auth.c $(SRC_DIR)/main.c $(SRC_DIR)/adb_recovery.c $(SRC_DIR)/version.c $(SRC_DIR)/util.c $(SRC_DIR)/identity.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/peer_auth.c $(SRC_DIR)/adb_recovery.c $(SRC_DIR)/version.c $(SRC_DIR)/util.c $(SRC_DIR)/identity.c $(LDLIBS) -o $@

request-timeout-test: run-request-timeout

# The one test that links the PRODUCTION sysexec.c and executes real children: the stubbed unit suite
# cannot prove that a wedged actuator is killed, reaped and its thread released.
bounded-exec-test: run-bounded-exec
$(BUILD)/bounded-exec: test/bounded_exec.c $(SRC_DIR)/sysexec.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/bounded_exec.c $(SRC_DIR)/sysexec.c $(LDLIBS) -o $@
# Real dispatcher + real bounded exec; only Android executable paths are remapped in the fixture.
actuator-exec-test: run-actuator-exec
$(BUILD)/actuator-exec: $(CORE_SRCS) $(SRC_DIR)/sysexec.c test/actuator_exec.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DHAPANELD_FRAMEWORK_TIMEOUT_MS=200U -DHAPANELD_INSTALL_TIMEOUT_MS=900U -I$(SRC_DIR) -Itest $(CORE_SRCS) $(SRC_DIR)/sysexec.c test/actuator_exec.c $(LDLIBS) -Wl,--wrap=execve -o $@

$(BUILD)/logcat-fixture: test/logcat_fixture.c | $(BUILD)
	$(CC) $(CFLAGS) test/logcat_fixture.c -o $@
$(BUILD)/logcat-boundary: $(CORE_SRCS) $(SRC_DIR)/sysexec.c test/logcat_boundary.c $(BUILD)/logcat-fixture | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_LOGCAT_PATH='"$(abspath $(BUILD)/logcat-fixture)"' -DLOGCAT_DUMP_DEADLINE_MS=500u -I$(SRC_DIR) $(CORE_SRCS) $(SRC_DIR)/sysexec.c test/logcat_boundary.c $(LDLIBS) -o $@
$(BUILD)/request-timeout: test/request_timeout.c $(SRC_DIR)/main.c $(SRC_DIR)/adb_recovery.c $(SRC_DIR)/version.c $(SRC_DIR)/util.c $(SRC_DIR)/identity.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/request_timeout.c $(SRC_DIR)/adb_recovery.c $(SRC_DIR)/version.c $(SRC_DIR)/util.c $(SRC_DIR)/identity.c $(LDLIBS) -o $@

# Start two real daemon binaries on an isolated socket. Their names intentionally share the exact
# 15-byte Linux comm value used on panels while their executable inodes and BUILDIDs differ.
REPLACEMENT_SOCKET := hapaneld-helper-replacement-test
OLD_BUILD_ID := aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
NEW_BUILD_ID := bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
REPLACEMENT_FLAGS := -DSOCK_NAME=\"$(REPLACEMENT_SOCKET)\" -DAPP_DATA=\"/proc/self\" \
	-DREPLACEMENT_RETIRE_ATTEMPTS=40 -DREPLACEMENT_RETIRE_DELAY_MS=10 \
	-DHAPANELD_TEST_ALLOW_STALE_OWNER_RETIREMENT=1
replacement-identity-test: run-replacement-identity
$(BUILD)/hapaneld-helper-old: $(DAEMON_SRCS) | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(REPLACEMENT_FLAGS) -DHAPANELD_BUILD_ID=\"$(OLD_BUILD_ID)\" $(DAEMON_SRCS) $(LDLIBS) -o $@
$(BUILD)/hapaneld-helper-new: $(DAEMON_SRCS) | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(REPLACEMENT_FLAGS) -DHAPANELD_BUILD_ID=\"$(NEW_BUILD_ID)\" $(DAEMON_SRCS) $(LDLIBS) -o $@

# CDP relay resource regression. Tight test-only deadlines keep saturation/reaping/idle coverage fast;
# production uses the conservative defaults compiled into cdprelay.c by Gradle.
cdprelay-resource-test: run-cdprelay-resource-test
$(BUILD)/cdprelay-resource-test: test/cdprelay_resource.c | $(BUILD)
	$(CC) $(CFLAGS) test/cdprelay_resource.c -o $@
$(BUILD)/cdprelay-test: cdprelay.c | $(BUILD)
	$(CC) $(CFLAGS) -DCDPRELAY_MAX_CHILDREN=2 -DCDPRELAY_CONNECT_TIMEOUT_MS=200 \
		-DCDPRELAY_WRITE_TIMEOUT_MS=200 -DCDPRELAY_IDLE_TIMEOUT_MS=300 \
		-DCDPRELAY_MAX_LIFETIME_MS=2000 cdprelay.c -o $@

# Real UNIX-socket composition fixture: production server/dispatch core with the no-exec host stub.
socket-test-server: $(BUILD)/socket-test-server $(BUILD)/adb-recovery
$(BUILD)/socket-test-server: $(CORE_SRCS) $(STUB) test/socket_server.c $(SRC_DIR)/commands.def test/zigbee_paths.h | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST -DHAPANELD_BUILD_ID='"$(TEST_BUILD_ID)"' -DCDP_PROC_ROOT='"$(abspath $(BUILD)/cdp-state-socket-fixture)"' -DSYSFS_READ_CLASS_ROOT='"$(abspath $(BUILD)/sysfs-read-socket-fixture)"' -DZIGBEE_TEST_ROOT='"$(abspath $(BUILD)/zigbee-socket-fixture)"' -include test/zigbee_paths.h -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/socket_server.c $(LDLIBS) -Wl,--wrap=open -Wl,--wrap=adb_recovery_ensure -o $@

# App<->daemon verb-contract cross-check: every verb the app sends must exist in commands.def.
contract:
	./test/contract-check.sh

# Sanitizer smoke: ASan+UBSan over the same CORE_SRCS (the script asks for the list).
fuzz:
	./fuzz/run.sh

# Robustness tests inside `make test`, off unless FUZZ_SECONDS > 0 (CI sets it). The fuzz drivers build
# in the test-binaries phase; two run slots then work beside the unit tests: the parser target gets the
# whole budget, the other slot replays the deterministic parser corpus and then spends the budget on the
# recovery parser (two thirds) and the CDP relay (one third). Every target fails on a sanitizer fault or
# a rule check (fuzz/oracles.c). FUZZ_OUT keeps each target's evolved corpus between runs.
FUZZ_SECONDS ?= 0
FUZZ_OUT ?= $(abspath fuzz/build)
FUZZ_FLAGS ?=
fuzz_env = HAPANELD_FUZZ_OUT='$(FUZZ_OUT)$(2)' HAPANELD_FUZZ_PHASE=$(1)
.PHONY: fuzz-binaries fuzz-build-smoke fuzz-build-parser fuzz-build-recovery fuzz-build-cdprelay run-fuzz-parser run-fuzz-others
fuzz-binaries: fuzz-build-smoke fuzz-build-parser fuzz-build-recovery fuzz-build-cdprelay
# The deterministic replay keeps its own output root: both parser drivers write record seeds at start.
fuzz-build-smoke:
	$(call fuzz_env,build,/smoke) ./fuzz/run.sh --target parser 0 >/dev/null
fuzz-build-parser fuzz-build-recovery fuzz-build-cdprelay: fuzz-build-%:
	$(call fuzz_env,build) ./fuzz/run.sh --target $* --libfuzzer >/dev/null
run-fuzz-parser:
	nice -n 10 env $(call fuzz_env,run) ./fuzz/run.sh --target parser --libfuzzer $(FUZZ_SECS) $(FUZZ_FLAGS)
run-fuzz-others:
	nice -n 10 env $(call fuzz_env,run,/smoke) ./fuzz/run.sh --target parser 0
	nice -n 10 env $(call fuzz_env,run) ./fuzz/run.sh --target recovery --libfuzzer $(call fuzz_share,2) $(FUZZ_FLAGS)
	nice -n 10 env $(call fuzz_env,run) ./fuzz/run.sh --target cdprelay --libfuzzer $(call fuzz_share,1) $(FUZZ_FLAGS)
# $(1) thirds of the budget, at least one second: a share rounded down to 0 would be unbounded.
fuzz_share = $$(( $(FUZZ_SECS) * $(1) / 3 > 0 ? $(FUZZ_SECS) * $(1) / 3 : 1 ))

# Used by fuzz/run.sh to reuse the one source list.
print-core:
	@echo $(CORE_SRCS)

$(BUILD):
	mkdir -p $(BUILD)

clean:
	rm -rf $(BUILD) fuzz/build

$(BUILD)/uninstall-supervisor: $(CORE_SRCS) $(SRC_DIR)/main.c $(SRC_DIR)/sysexec.c test/uninstall_supervisor.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DSOCK_NAME='"hapaneld-helper-uninstall-test"' \
		-DAPP_DATA='"$(UNITS_TMP)/$(UNIT_NAME)/.hapaneld-uninstall-data-test"' -DAPP_USER_DATA='"$(UNITS_TMP)/$(UNIT_NAME)/.hapaneld-uninstall-user-test"' \
		-I$(SRC_DIR) $(CORE_SRCS) $(SRC_DIR)/sysexec.c test/uninstall_supervisor.c $(LDLIBS) -Wl,--wrap=unlinkat -Wl,--wrap=lstat -o $@

$(BUILD)/adb-recovery: test/adb_recovery.c test/adb_recovery_paths.h test/android/log.h $(CORE_SRCS) $(SRC_DIR)/main.c $(STUB) | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST -include test/adb_recovery_paths.h -DHAPANELD_BUILD_ID='"$(TEST_BUILD_ID)"' -I$(SRC_DIR) -Itest test/adb_recovery.c $(filter-out $(SRC_DIR)/adb_recovery.c,$(CORE_SRCS)) $(STUB) $(LDLIBS) -o $@
adb-recovery-test: run-adb-recovery

# Retained independent recovery-engine filesystem retirement under the fixed recovery invocation.
.PHONY: adb-recovery-retirement-test
adb-recovery-retirement-test: run-adb-recovery-retirement
$(BUILD)/adb-recovery-retirement: test/adb_recovery_retirement.py | $(BUILD)
	cp $< $@
	chmod +x $@

# Retained independent recovery-engine replacement through the fixed recovery invocation.
.PHONY: adb-recovery-replacement-test
adb-recovery-replacement-test: $(BUILD)/adb-recovery-replacement
	$(BUILD)/adb-recovery-replacement
$(BUILD)/adb-recovery-replacement: test/adb_recovery_replacement.py test/adb_recovery_replacement_cuts.c | $(BUILD)
	cp $< $@
	chmod +x $@

# Small selections of the same real ordinary-dispatch/recovery driver for fault qualification.
ENSURE_CASES_fresh := fresh
ENSURE_CASES_bound := source-bound
ENSURE_CASES_faults := unsafe-target,publication-faults
ENSURE_CASES_reaper := reaper-failure
ENSURE_CASES_status := readiness-faults
ENSURE_CASES_contender := superseded
ENSURE_CASES_drain := timeout-retry
ENSURE_CASES_receipt := receipt-sync-fails
$(BUILD)/adb-ensure-%: $(BUILD)/adb-recovery-replacement Makefile
	test -n '$(ENSURE_CASES_$*)'
	printf '%s\n' '#!/bin/sh' 'RECOVERY_REPLACEMENT_LEGS=ensure RECOVERY_REPLACEMENT_CASES=$(ENSURE_CASES_$*) exec "$$(dirname "$$0")/adb-recovery-replacement"' > $@
	chmod +x $@

# Real dispatch fixture; path substitution is compile-time and absent from shipped builds.
$(BUILD)/zigbee-boundary: $(CORE_SRCS) $(STUB) test/zigbee_boundary.c test/zigbee_paths.h | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DZIGBEE_TEST_ROOT='"$(abspath $(BUILD)/zigbee-fixture)"' -include test/zigbee_paths.h -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/zigbee_boundary.c $(LDLIBS) -o $@

$(BUILD)/zigbee-role-exec: $(SRC_DIR)/sysexec.c test/zigbee_role_exec.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/zigbee_role_exec.c $(SRC_DIR)/sysexec.c $(LDLIBS) -Wl,--wrap=execve -o $@

$(BUILD)/wifi-boundary: $(CORE_SRCS) $(STUB) test/wifi_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/wifi_boundary.c $(LDLIBS) -Wl,--wrap=sysexec_capture_wifi_status -o $@

$(BUILD)/wifi-exec: $(SRC_DIR)/sysexec.c test/wifi_exec.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/wifi_exec.c $(SRC_DIR)/sysexec.c $(LDLIBS) -Wl,--wrap=execve -o $@

$(BUILD)/adb-state-boundary: $(CORE_SRCS) $(STUB) test/adb_state_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/adb_state_boundary.c $(LDLIBS) -Wl,--wrap=sysexec_capture_adb_properties -Wl,--wrap=open -Wl,--wrap=read -Wl,--wrap=__read_chk -o $@

$(BUILD)/adb-exec: $(SRC_DIR)/sysexec.c test/adb_exec.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/adb_exec.c $(SRC_DIR)/sysexec.c $(LDLIBS) -Wl,--wrap=execve -o $@

# Fixed sysfs paths are substituted only for dispatch fixtures; production remains /sys/class.
$(BUILD)/sysfs-read-boundary: $(CORE_SRCS) $(STUB) test/sysfs_read_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DSYSFS_READ_CLASS_ROOT='"$(abspath $(BUILD)/sysfs-read-fixture)"' -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/sysfs_read_boundary.c $(LDLIBS) -Wl,--wrap=readdir -Wl,--wrap=read -Wl,--wrap=__read_chk -Wl,--wrap=faccessat -Wl,--wrap=openat -Wl,--wrap=open -o $@

$(BUILD)/cdp-state-boundary: $(CORE_SRCS) $(STUB) test/cdp_state_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -DCDP_PROC_ROOT='"$(abspath $(BUILD)/cdp-state-fixture)"' -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/cdp_state_boundary.c $(LDLIBS) -Wl,--wrap=open -Wl,--wrap=openat -Wl,--wrap=read -Wl,--wrap=__read_chk -Wl,--wrap=readdir -Wl,--wrap=fstatat -o $@

# GFXINFO dispatch uses a counted capture seam; execution tests retain the real supervised child.
$(BUILD)/gfxinfo-boundary: $(CORE_SRCS) $(STUB) test/gfxinfo_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/gfxinfo_boundary.c $(LDLIBS) -Wl,--wrap=sysexec_capture_gfxinfo -o $@

$(BUILD)/gfxinfo-exec: $(SRC_DIR)/sysexec.c test/gfxinfo_exec.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/gfxinfo_exec.c $(SRC_DIR)/sysexec.c $(LDLIBS) -Wl,--wrap=execve -o $@

# Ordinary fixed ENSURE wire/argument boundary; the real driver qualifies publication/custody.
$(BUILD)/adb-ensure-boundary: $(CORE_SRCS) test/adb_ensure_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/adb_ensure_boundary.c $(LDLIBS) -Wl,--wrap=adb_recovery_ensure -o $@

# Real stream reader + dispatcher; only recovery execution/handoff is replaced with byte echo.
.PHONY: socket-framing-test
socket-framing-test: run-socket-framing
$(BUILD)/socket-framing: $(CORE_SRCS) $(STUB) test/socket_framing.c $(SRC_DIR)/commands.def | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST $(UNIT_TMP) -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/socket_framing.c $(LDLIBS) -Wl,--wrap=cmd_adb_recovery -Wl,--wrap=read -o $@

# Lifecycle-parent child custody stays thread-free; the forked worker keeps ordinary reaping.
.PHONY: lifecycle-reap-test
lifecycle-reap-test: run-lifecycle-reap
$(BUILD)/lifecycle-reap: test/lifecycle_reap.c $(SRC_DIR)/sysexec.c $(SRC_DIR)/sysexec.h | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/lifecycle_reap.c $(SRC_DIR)/sysexec.c $(LDLIBS) -o $@
