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
CORE_SRCS := $(addprefix $(SRC_DIR)/, util.c sha256.c led.c screen.c input.c gpio.c sysctl.c perf.c cht8305.c vi530x.c version.c companion.c guard_maintenance.c server.c dispatch.c)
DAEMON_SRCS := $(CORE_SRCS) $(SRC_DIR)/main.c $(SRC_DIR)/sysexec.c
STUB := test/sysexec_stub.c
TEST_BUILD_ID := aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
REPLACEMENT_OLD_BUILD_ID := eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
REPLACEMENT_NEW_BUILD_ID := dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd

.PHONY: all daemon test test-units test-transaction test-transport peer-auth-test request-timeout-test replacement-identity-test install-security-test standalone-identity-test cdprelay-resource-test socket-test-server fuzz contract clean print-core
all: daemon

# Host build of the real daemon — a compile sanity check (-Werror); the shipped binary is build.sh.
daemon: $(BUILD)/hapaneld-helper
$(BUILD)/hapaneld-helper: $(DAEMON_SRCS) | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(DAEMON_SRCS) $(LDLIBS) -o $@

# Unit tests: CORE modules + the sysexec stub (no real exec) + the test driver. `test` runs the three
# groups one after another, even under -j; CI runs each group as its own job because the two installer
# scripts spend most of their time waiting on the installer's retry intervals.
TEST_BINS := $(BUILD)/unit $(BUILD)/hapaneld-helper $(BUILD)/companion-boundary $(BUILD)/guard-maintenance-boundary $(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-new $(BUILD)/sysexec-fd-boundary $(BUILD)/peer-auth $(BUILD)/request-timeout $(BUILD)/bounded-exec $(BUILD)/hapaneld-helper-old $(BUILD)/hapaneld-helper-new $(BUILD)/cdprelay-resource-test $(BUILD)/cdprelay-test
test:
	$(MAKE) test-units
	$(MAKE) test-transaction
	$(MAKE) test-transport

test-units: $(TEST_BINS)
	$(BUILD)/unit
	test "$$($(BUILD)/hapaneld-helper --version)" = "HELPER version=1.3.0 proto=1.3"
	$(BUILD)/companion-boundary
	$(BUILD)/guard-maintenance-boundary
	$(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-old $(BUILD)/replacement-lease-new
	$(BUILD)/sysexec-fd-boundary
	$(BUILD)/peer-auth
	$(BUILD)/request-timeout
	$(BUILD)/bounded-exec
	bash ./test/replacement_identity_test.sh $(BUILD)/hapaneld-helper-old $(BUILD)/hapaneld-helper-new
	$(BUILD)/cdprelay-resource-test $(BUILD)/cdprelay-test
	bash ./test/root_command_quote_test.sh
	bash ./test/standalone_installer_identity_test.sh
	bash ./test/install_daemon_security_test.sh

# Self-contained: both scripts stage their own mock helper, so neither needs the test binaries.
test-transaction:
	bash ./test/standalone_installer_transaction_test.sh

test-transport:
	bash ./test/direct_root_transport_test.sh

# --wrap=open/ioctl lets tests provide a fake evdev node and capture requested exclusive grabs while
# preserving the existing ledjni ioctl assertions; --wrap=write forces deterministic stream edge cases.
$(BUILD)/unit: $(CORE_SRCS) $(STUB) test/unit.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST -I$(SRC_DIR) $(CORE_SRCS) $(STUB) test/unit.c $(LDLIBS) -Wl,--wrap=open -Wl,--wrap=ioctl -Wl,--wrap=write -o $@

$(BUILD)/companion-boundary: $(CORE_SRCS) $(STUB) test/companion_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) \
		test/companion_boundary.c $(LDLIBS) -Wl,--wrap=renameat -o $@

$(BUILD)/guard-maintenance-boundary: $(CORE_SRCS) $(STUB) test/guard_maintenance_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST -DHAPANELD_TEST_SKIP_SELF_BINDING -DHAPANELD_BUILD_ID='"$(TEST_BUILD_ID)"' -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) \
		test/guard_maintenance_boundary.c $(LDLIBS) -o $@

$(BUILD)/replacement-lease-old: $(CORE_SRCS) $(STUB) test/replacement_lease_process.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST -DHAPANELD_BUILD_ID='"$(REPLACEMENT_OLD_BUILD_ID)"' \
		-I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/replacement_lease_process.c $(LDLIBS) -o $@

$(BUILD)/replacement-lease-new: $(CORE_SRCS) $(STUB) test/replacement_lease_process.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST -DHAPANELD_BUILD_ID='"$(REPLACEMENT_NEW_BUILD_ID)"' \
		-I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/replacement_lease_process.c $(LDLIBS) -o $@

$(BUILD)/sysexec-fd-boundary: $(SRC_DIR)/sysexec.c test/sysexec_fd_boundary.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(SRC_DIR)/sysexec.c test/sysexec_fd_boundary.c $(LDLIBS) -o $@

# Peer-credential policy is owned by main.c rather than CORE_SRCS. The white-box test includes that
# production implementation and supplies inert module stubs; it never opens a socket or executes a verb.
peer-auth-test: $(BUILD)/peer-auth
	$(BUILD)/peer-auth
$(BUILD)/peer-auth: test/peer_auth.c $(SRC_DIR)/main.c $(SRC_DIR)/version.c $(SRC_DIR)/util.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/peer_auth.c $(SRC_DIR)/version.c $(SRC_DIR)/util.c $(LDLIBS) -o $@

request-timeout-test: $(BUILD)/request-timeout
	$(BUILD)/request-timeout

# The one test that links the PRODUCTION sysexec.c and executes real children: the stubbed unit suite
# cannot prove that a wedged actuator is killed, reaped and its thread released.
bounded-exec-test: $(BUILD)/bounded-exec
	$(BUILD)/bounded-exec
$(BUILD)/bounded-exec: test/bounded_exec.c $(SRC_DIR)/sysexec.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/bounded_exec.c $(SRC_DIR)/sysexec.c $(LDLIBS) -o $@
$(BUILD)/request-timeout: test/request_timeout.c $(SRC_DIR)/main.c $(SRC_DIR)/version.c $(SRC_DIR)/util.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) test/request_timeout.c $(SRC_DIR)/version.c $(SRC_DIR)/util.c $(LDLIBS) -o $@

# Start two real daemon binaries on an isolated socket. Their names intentionally share the exact
# 15-byte Linux comm value used on panels while their executable inodes and BUILDIDs differ.
REPLACEMENT_SOCKET := hapaneld-helper-replacement-test
OLD_BUILD_ID := aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
NEW_BUILD_ID := bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
REPLACEMENT_FLAGS := -DSOCK_NAME=\"$(REPLACEMENT_SOCKET)\" -DAPP_DATA=\"/proc/self\" \
	-DREPLACEMENT_RETIRE_ATTEMPTS=40 -DREPLACEMENT_RETIRE_DELAY_MS=10 \
	-DHAPANELD_TEST_ALLOW_STALE_OWNER_RETIREMENT=1
replacement-identity-test: $(BUILD)/hapaneld-helper-old $(BUILD)/hapaneld-helper-new
	bash ./test/replacement_identity_test.sh $(BUILD)/hapaneld-helper-old $(BUILD)/hapaneld-helper-new
$(BUILD)/hapaneld-helper-old: $(DAEMON_SRCS) | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(REPLACEMENT_FLAGS) -DHAPANELD_BUILD_ID=\"$(OLD_BUILD_ID)\" $(DAEMON_SRCS) $(LDLIBS) -o $@
$(BUILD)/hapaneld-helper-new: $(DAEMON_SRCS) | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(REPLACEMENT_FLAGS) -DHAPANELD_BUILD_ID=\"$(NEW_BUILD_ID)\" $(DAEMON_SRCS) $(LDLIBS) -o $@

install-security-test:
	bash ./test/install_daemon_security_test.sh

# Reads the identity out of real compiled ELF artifacts, so it needs them built first.
standalone-identity-test: $(BUILD)/hapaneld-helper-old $(BUILD)/hapaneld-helper-new
	bash ./test/standalone_installer_identity_test.sh

# CDP relay resource regression. Tight test-only deadlines keep saturation/reaping/idle coverage fast;
# production uses the conservative defaults compiled into cdprelay.c by Gradle.
cdprelay-resource-test: $(BUILD)/cdprelay-resource-test $(BUILD)/cdprelay-test
	$(BUILD)/cdprelay-resource-test $(BUILD)/cdprelay-test
$(BUILD)/cdprelay-resource-test: test/cdprelay_resource.c | $(BUILD)
	$(CC) $(CFLAGS) test/cdprelay_resource.c -o $@
$(BUILD)/cdprelay-test: cdprelay.c | $(BUILD)
	$(CC) $(CFLAGS) -DCDPRELAY_MAX_CHILDREN=2 -DCDPRELAY_CONNECT_TIMEOUT_MS=200 \
		-DCDPRELAY_WRITE_TIMEOUT_MS=200 -DCDPRELAY_IDLE_TIMEOUT_MS=300 \
		-DCDPRELAY_MAX_LIFETIME_MS=2000 cdprelay.c -o $@

# Real UNIX-socket composition fixture: production server/dispatch core with the no-exec host stub.
socket-test-server: $(BUILD)/socket-test-server
$(BUILD)/socket-test-server: $(CORE_SRCS) $(STUB) test/socket_server.c | $(BUILD)
	$(CC) $(CFLAGS) -DHAPANELD_TEST -I$(SRC_DIR) -Itest $(CORE_SRCS) $(STUB) test/socket_server.c $(LDLIBS) -o $@

# App<->daemon verb-contract cross-check: every verb the app sends must exist in commands.def.
contract:
	./test/contract-check.sh

# Sanitizer smoke: ASan+UBSan over the same CORE_SRCS (the script asks for the list).
fuzz:
	./fuzz/run.sh

# Used by fuzz/run.sh to reuse the one source list.
print-core:
	@echo $(CORE_SRCS)

$(BUILD):
	mkdir -p $(BUILD)

clean:
	rm -rf $(BUILD) fuzz/build
