#!/usr/bin/env python3
"""Qualify retained legacy recovery-engine replacement with isolated Android I/O."""
import os
import ctypes
import fcntl
import hashlib
import json
from pathlib import Path
import re
import shutil
import signal
import socket
import struct
import sys
import subprocess
import tempfile
import threading
import time

FIXTURE_ROOT = Path(__file__).resolve().parents[1]
ROOT = Path(os.environ.get('RECOVERY_REPLACEMENT_SOURCE_ROOT', FIXTURE_ROOT))
OLD = '1' * 64
NEW = '2' * 64
THIRD = '3' * 64
CASE_WAIT = 8
ENSURE_TIMEOUT = 28
DIRECT_CASES = tuple('locked-fresh,stale,matching,failed-open,unproven-open,foreign-owner,scan-error,concurrent,staggered,timeout-retry,malformed-custody,symlink-custody,fifo-custody,receipt-sync-fails'.split(','))
EXTRA_CASES = tuple('source-bound,fresh,source-pinned,lost-ack,unsafe-target,publication-faults,reaper-failure,superseded,readiness-faults'.split(','))
EXTRA_VARIANTS = {
    'source-bound': ('exact', 'overflow'),
    'unsafe-target': ('symlink', 'fifo', 'directory', 'hardlink', 'mode', 'owner', 'directory-symlink'),
    'publication-faults': ('ensure-copy-error', 'ensure-file-sync-error', 'ensure-dir-sync-error', 'ensure-exec-error'),
    'readiness-faults': ('wrong', 'malformed', 'stall'),
}

def selection(environ=os.environ):
    """The (leg, case, variant) runs this invocation makes, in order, from its environment.

    RECOVERY_REPLACEMENT_LEGS and RECOVERY_REPLACEMENT_CASES select legs and cases;
    RECOVERY_REPLACEMENT_VARIANTS selects variants of one ensure-only case. The Makefile
    shards the driver with these, and `--plan` prints the result so a check can prove the
    shards cover every run exactly once."""
    legs = environ.get('RECOVERY_REPLACEMENT_LEGS', 'direct,ensure').split(',')
    assert legs and all(leg in ('direct', 'ensure') for leg in legs), 'unknown replacement fixture leg'
    selected = environ.get('RECOVERY_REPLACEMENT_CASES')
    cases = list(DIRECT_CASES + EXTRA_CASES) if selected is None else selected.split(',')
    assert all(case in DIRECT_CASES + EXTRA_CASES for case in cases), 'unknown replacement fixture case'
    chosen = environ.get('RECOVERY_REPLACEMENT_VARIANTS')
    assert chosen is None or (len(cases) == 1 and cases[0] in EXTRA_VARIANTS and 'ensure' in legs), 'variants select within one ensure-only case'
    runs = [(leg, case, '') for leg in legs for case in cases if case in DIRECT_CASES]
    if 'ensure' in legs:
        for case in (case for case in cases if case in EXTRA_CASES):
            variants = EXTRA_VARIANTS.get(case, ('',))
            if chosen is not None:
                assert all(variant in variants for variant in chosen.split(',')), 'unknown replacement fixture variant for ' + case
                variants = tuple(variant for variant in variants if variant in chosen.split(','))
            runs += [('ensure', case, variant) for variant in variants]
    return legs, cases, runs

WRAPS = ('kill', 'fstatat', 'fopen', 'usleep', 'fsync', 'write', 'renameat', 'execve', 'pthread_create')

# A minimal entry keeps ordinary-host startup away from Guard paths. Both paths run real core:
# the ordinary bounded socket accumulator/dispatch, and the retained independent recovery engine.
ORDINARY_ENTRY = r'''
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/resource.h>
#include <sys/un.h>
#include <unistd.h>
#include "adb_recovery.h"
#include "server.h"
static const char *fake_mode;
static void *fake_serve(void *argument) {
    int fd = (int)(intptr_t)argument;
    char request[512]; size_t used = 0;
    while (used + 1 < sizeof request) {
        ssize_t n = read(fd, request + used, 1);
        if (n != 1) { close(fd); return NULL; }
        if (request[used++] == '\n') break;
    }
    request[used] = 0;
    const char *response = "ERR\n";
    if (!strcmp(request, "STATUS\n")) {
        if (!strcmp(fake_mode, "stall"))
            while (!access(RECOVERY_REPLACEMENT_TEST_ROOT "/fake-stall", F_OK)) poll(NULL, 0, 20);
        if (!strcmp(fake_mode, "wrong"))
            response = "READY 1 1111111111111111111111111111111111111111111111111111111111111111 0 0 0\n";
        else if (!strcmp(fake_mode, "malformed"))
            response = "READY 1 " HAPANELD_BUILD_ID " 0 0 0 extra\n";
        else response = "READY 1 " HAPANELD_BUILD_ID " 0 0 0\n";
    }
    ssize_t sent = write(fd, response, strlen(response));
    if (sent < 0) { close(fd); return NULL; }
    close(fd); return NULL;
}
static void *serve(void *argument) {
    int fd = (int)(intptr_t)argument;
    server_serve(fd, HELPER_CALLER_ROOT);
    close(fd); return NULL;
}
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--adb-recovery")) return adb_recovery_main();
    if (argc == 3 && !strcmp(argv[1], "--fake-recovery")) fake_mode = argv[2];
    else if (argc != 1) return 2;
    if (!fake_mode) {
        int sentinel = open("/dev/null", O_RDONLY);
        if (sentinel < 0 || fcntl(sentinel, F_DUPFD, 128) != 128) return 6;
        close(sentinel);
        struct rlimit limit;
        if (getrlimit(RLIMIT_NOFILE, &limit)) return 7;
        limit.rlim_cur = 64;
        if (setrlimit(RLIMIT_NOFILE, &limit)) return 8;
    }
    signal(SIGPIPE, SIG_IGN);
    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    const char *name = fake_mode ? ADB_RECOVERY_SOCKET : RECOVERY_REPLACEMENT_ORDINARY_SOCKET;
    size_t length = strlen(name);
    if (listener < 0 || length + 1 > sizeof address.sun_path) return 3;
    memcpy(address.sun_path + 1, name, length);
    if (bind(listener, (struct sockaddr *)&address,
             offsetof(struct sockaddr_un, sun_path) + length + 1) || listen(listener, 16)) return 4;
    for (;;) {
        int fd = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
        if (fd < 0) { if (errno == EINTR) continue; return 5; }
        pthread_t thread;
        if (pthread_create(&thread, NULL, fake_mode ? fake_serve : serve, (void *)(intptr_t)fd)) close(fd);
        else pthread_detach(thread);
    }
}
'''

def process_identity(pid):
    """A PID alone never authorizes cleanup; capture its start tick and executing inode."""
    try:
        root = Path('/proc') / str(pid)
        first = (root/'stat').read_text().rsplit(') ', 1)[1].split()
        exe = (root/'exe').stat()
        second = (root/'stat').read_text().rsplit(') ', 1)[1].split()
        if first[19] != second[19]: return None
        return dict(pid=int(pid), start=int(first[19]), sid=int(first[3]),
                    ppid=int(first[1]), state=first[0], uid=root.stat().st_uid,
                    dev=exe.st_dev, ino=exe.st_ino)
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return None

class OwnedProcesses:
    def __init__(self, root):
        self.root, self.identities, self.sessions, self.children = root, {}, {}, []

    def record(self, pid):
        identity = process_identity(pid)
        if identity:
            assert identity['uid'] == 0, 'owned fixture process unexpectedly lost root identity'
            self.identities[(identity['pid'], identity['start'])] = identity
            if identity['sid'] == identity['pid']:
                self.sessions[identity['sid']] = identity['start']
        return identity

    def add(self, child):
        self.children.append(child); self.record(child.pid); return child

    def discover(self):
        # Spawn receipts cover children that fail before a recovery socket can identify them.
        receipt = self.root/'ensure-spawns'
        if receipt.exists():
            for line in receipt.read_text().splitlines():
                fields = line.split()
                if len(fields) != 7 or not all(field.isdecimal() for field in fields):
                    raise AssertionError('malformed detached-child identity receipt: ' + line)
                pid, start, sid, current_dev, current_ino, published_dev, published_ino = map(int, fields)
                identity = process_identity(pid)
                if identity and identity['start'] == start:
                    assert identity['sid'] == sid, 'detached child changed its recorded session'
                    assert (identity['dev'], identity['ino']) in ((current_dev, current_ino), (published_dev, published_ino)), 'detached child executes neither recorded source nor planned published inode'
                    self.record(pid)
        for path in Path('/proc').iterdir():
            if not path.name.isdecimal(): continue
            identity = process_identity(int(path.name))
            if not identity: continue
            start = self.sessions.get(identity['sid'])
            if start is not None and identity['start'] >= start:
                leader = process_identity(identity['sid'])
                if leader and leader['start'] != start: continue
                self.record(identity['pid'])

    def cleanup(self):
        def kill_owned():
            self.discover()
            for identity in list(self.identities.values()):
                current = process_identity(identity['pid'])
                if current and all(current[key] == identity[key] for key in ('start', 'dev', 'ino', 'sid')):
                    try: os.kill(identity['pid'], signal.SIGKILL)
                    except ProcessLookupError: pass
        kill_owned()
        for child in self.children:
            child.wait(timeout=5)
        # Linux subreaper ownership lets this driver reap detached owners and their guardians.
        until = time.monotonic() + 5
        while True:
            kill_owned()
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                break
            if pid: continue
            if time.monotonic() >= until:
                raise AssertionError('owned detached children did not exit and reap')
            time.sleep(.02)
        print('IDENTITIES', json.dumps(list(self.identities.values()), sort_keys=True), flush=True)
        for identity in self.identities.values():
            current = process_identity(identity['pid'])
            assert not current or current['start'] != identity['start'], 'owned process survived fixture cleanup'

class EnsureAttempt:
    """A contender is an ordinary socket operation, not the detached recovery Popen."""
    def __init__(self, operation):
        self.result, self.failure = None, None
        def run():
            try: self.result = operation()
            except BaseException as failure: self.failure = failure
        self.thread = threading.Thread(target=run)
        self.thread.start()

    def poll(self):
        if self.thread.is_alive(): return None
        if self.failure: raise self.failure
        return 0 if self.result == 'ADBRECOVERYENSURE READY\n' else 1

    @property
    def returncode(self): return self.poll()

    def wait(self, timeout=ENSURE_TIMEOUT):
        self.thread.join(timeout)
        assert not self.thread.is_alive(), 'ENSURE exceeded its one bounded operation deadline'
        return self.poll()

def wait(predicate, message, seconds=None):
    if seconds is None: seconds = CASE_WAIT
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(.02)
    raise AssertionError(message)

def run():
    global CASE_WAIT
    assert os.geteuid() == 0, 'recovery replacement fixture requires root peers'
    assert ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) == 0, 'fixture cannot own orphaned recovery children as a subreaper'
    with tempfile.TemporaryDirectory(prefix='adb-replacement-') as temp:
        p = Path(temp)
        app, user, live = p/'app', p/'user', p/'recovery'
        app.mkdir(); user.mkdir(); live.mkdir(mode=0o700)
        sock = 'adb-replacement-' + str(os.getpid())
        prop = p/'property'
        prop.write_text('''#!/usr/bin/python3
from pathlib import Path
import sys
p=Path(__file__).parent
if len(sys.argv)==2:
 print((p/sys.argv[1]).read_text() if (p/sys.argv[1]).exists() else '')
else:
 name,value=sys.argv[1:]
 (p/name).write_text(value)
 if name=='ctl.restart':
  if (p/'fail-open').exists(): sys.exit(1)
  (p/'init.svc.adbd').write_text('running')
  (p/'tcp').write_text('0: 00000000:15B3 00000000:0000 0A\\n')
 if name=='ctl.stop':
  (p/'init.svc.adbd').write_text('stopped')
  (p/'tcp').write_text('')
''')
        prop.chmod(0o700)
        flags = {'APP_DATA':app, 'APP_USER_DATA':user, 'ADB_RECOVERY_DIRECTORY':live,
                 'ADB_RECOVERY_GETPROP':prop, 'ADB_RECOVERY_SETPROP':prop,
                 'ADB_RECOVERY_TCP':p/'tcp', 'ADB_RECOVERY_TCP6':p/'tcp6',
                 'ADB_RECOVERY_SOCKET':sock, 'ADB_RECOVERY_SHELL':'/bin/sh',
                 'RECOVERY_REPLACEMENT_TEST_ROOT':p}
        sources = subprocess.check_output(['make','--no-print-directory','-s','print-core'],cwd=ROOT,text=True,
                                          env={k: v for k, v in os.environ.items() if k not in ('MAKEFLAGS', 'MFLAGS', 'MAKELEVEL')}).split()
        # Only these read the per-run defines below. When the Makefile has compiled every other
        # source once (RECOVERY_REPLACEMENT_OBJECTS), each run links those objects into its own
        # binaries: a fresh inode per run, so concurrent runs never match each other's processes.
        per_run = ('src/adb_recovery.c', 'src/identity.c', 'src/version.c')
        objects = os.environ.get('RECOVERY_REPLACEMENT_OBJECTS')
        if objects:
            assert ROOT == FIXTURE_ROOT, 'prebuilt objects come from the fixture tree only'
            def shared(path):
                if path in per_run: return path
                built = Path(objects)/(Path(path).stem + '.o')
                assert built.is_file(), 'missing prebuilt object ' + str(built)
                return str(built)
            sources = [shared(s) for s in sources]
            linked = [shared('src/sysexec.c')]
            main_source = shared('src/main.c')
        else:
            linked = ['src/sysexec.c']
            main_source = 'src/main.c'
        cuts = str(FIXTURE_ROOT/'test/adb_recovery_replacement_cuts.c')
        # The incumbent starts on a free socket, so current source with an older build
        # exercises the shipped STATUS/RECOVER contract. Exact-base proof may supply its source.
        old_source = Path(os.environ.get('RECOVERY_REPLACEMENT_OLD_SOURCE', ROOT/'src/adb_recovery.c'))
        binaries = {}
        for build, recovery in ((OLD,str(old_source)), (NEW,'src/adb_recovery.c')):
            binary=p/build
            subprocess.run(['cc','-O1','-g','-Wall','-Wextra','-Werror','-Wno-format-truncation',
                            '-Isrc',f'-DHAPANELD_BUILD_ID="{build}"',
                            *[f'-D{k}="{v}"' for k,v in flags.items()],
                            *[recovery if s=='src/adb_recovery.c' else s for s in sources],
                            main_source,*linked,cuts,
                            '-DRECOVERY_REPLACEMENT_STANDALONE_ENTRY','-Wl,--wrap=main',
                            *['-Wl,--wrap=' + name for name in WRAPS],'-lpthread','-o',str(binary)],cwd=ROOT,check=True,preexec_fn=lambda: os.nice(10))
            binaries[build]=binary
        legs, cases, runs = selection()
        ordinary_binaries = {}
        if 'ensure' in legs:
            entry = p/'ordinary_entry.c'; entry.write_text(ORDINARY_ENTRY)
            for build in (NEW, THIRD):
                binary = p/('ordinary-' + build)
                subprocess.run(['cc','-O1','-g','-Wall','-Wextra','-Werror','-Wno-format-truncation',
                                '-Isrc',f'-DHAPANELD_BUILD_ID="{build}"',
                                f'-DRECOVERY_REPLACEMENT_ORDINARY_SOCKET="{sock}-ordinary-{build[0]}"',
                                *[f'-D{k}="{v}"' for k,v in flags.items()], *sources,
                                str(entry),*linked,cuts,
                                *['-Wl,--wrap=' + name for name in WRAPS],
                                '-lpthread','-o',str(binary)],cwd=ROOT,check=True,preexec_fn=lambda: os.nice(10))
                ordinary_binaries[build] = binary
        owned = None
        def line(s, bound, deadline=None):
            output = bytearray()
            while len(output) < bound:
                if deadline is not None:
                    remaining = deadline - time.monotonic()
                    assert remaining > 0, 'fixture transport exhausted its absolute deadline'
                    s.settimeout(remaining)
                byte = s.recv(1)
                assert byte, 'endpoint closed before its complete bounded line'
                output += byte
                if byte == b'\n': return output.decode('ascii')
            raise AssertionError('endpoint exceeded its bounded line')
        def request(command):
            with socket.socket(socket.AF_UNIX) as s:
                s.settimeout(5)
                s.connect('\0'+sock)
                peer_pid, peer_uid, _ = struct.unpack('3i', s.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                assert peer_uid == 0, 'recovery endpoint did not authenticate a root peer'
                owned.record(peer_pid)
                s.sendall((command+'\n').encode())
                return line(s, 256)
        def status():
            try: return request('STATUS')
            except (OSError, AssertionError): return ''
        def opened():
            return (p/'init.svc.adbd').read_text()=='running' and '15B3' in (p/'tcp').read_text()
        def install(build):
            stage=live/'stage'
            shutil.copyfile(binaries[build],stage); stage.chmod(0o700)
            stage.replace(live/'helper')
        def launch_direct(executable=None, scan_error=False, hide_listener=False, slow_guardian=False, block_guardian=False, receipt_sync_error=False):
            return owned.add(subprocess.Popen([str(executable or live/'helper'),'--adb-recovery'],start_new_session=True,
                                    env={**os.environ, **({'RECOVERY_REPLACEMENT_SCAN_ERROR':'1'} if scan_error else {}),
                                         **({'RECOVERY_REPLACEMENT_HIDE_LISTENER':'1'} if hide_listener else {}),
                                         **({'RECOVERY_REPLACEMENT_SLOW_GUARDIAN':'1'} if slow_guardian else {}),
                                         **({'RECOVERY_REPLACEMENT_GUARDIAN_BLOCK':str(p/'guardian-block')} if block_guardian else {}),
                                         **({'RECOVERY_REPLACEMENT_RECEIPT_SYNC_ERROR':'1'} if receipt_sync_error else {})}))
        def ordinary_start(build=NEW, padded_size=None):
            executable = p/('ordinary-live-' + build[0])
            shutil.copyfile(ordinary_binaries[build], executable); executable.chmod(0o700)
            if padded_size is not None:
                assert executable.stat().st_size <= padded_size, 'source-bound fixture executable exceeded its padding bound'
                with executable.open('ab') as stream: stream.truncate(padded_size)
            process = owned.add(subprocess.Popen([str(executable)], start_new_session=True))
            def ready():
                try:
                    with socket.socket(socket.AF_UNIX) as s:
                        s.connect('\0' + sock + '-ordinary-' + build[0]); return True
                except OSError: return False
            wait(ready, 'ordinary fixture did not bind its isolated socket', seconds=5)
            return process, executable
        def ready_proof(build=NEW):
            with socket.socket(socket.AF_UNIX) as s:
                s.settimeout(5); s.connect('\0'+sock)
                peer_pid, uid, _ = struct.unpack('3i', s.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                assert uid == 0, 'READY recovery endpoint peer is not root'
                identity = owned.record(peer_pid)
                assert identity, 'READY recovery endpoint lacks a stable process identity'
                executable = Path('/proc')/str(peer_pid)/'exe'
                reached = os.readlink(executable)
                assert reached in (str(live/'helper'), str(live/'helper') + ' (deleted)'), 'READY recovery endpoint executes outside the fixed canonical recovery path'
                metadata = executable.stat()
                assert metadata.st_uid == metadata.st_gid == 0 and metadata.st_mode & 0o7777 == 0o700 and metadata.st_nlink <= 1, 'READY recovery endpoint executable has unsafe root metadata'
                s.sendall(b'STATUS\n'); response = line(s, 256)
                assert re.fullmatch(r'READY 1 ' + build + r' [01] [01] [01]\n', response), 'ENSURE READY lacks a fresh exact recovery STATUS/build proof'
                checked = process_identity(peer_pid)
                assert checked and all(checked[key] == identity[key] for key in ('pid', 'start', 'dev', 'ino', 'sid')), 'READY recovery endpoint identity changed during fresh STATUS proof'
                return identity, response
        def ordinary_controls():
            # A pending connection is itself an observation; invalid arguments cannot even probe.
            with socket.socket(socket.AF_UNIX) as recovery_probe:
                recovery_probe.bind('\0' + sock); recovery_probe.listen(8); recovery_probe.setblocking(False)
                with socket.socket(socket.AF_UNIX) as s:
                    s.settimeout(ENSURE_TIMEOUT); s.connect('\0' + sock + '-ordinary-' + NEW[0])
                    for command_text, expected in (
                            ('PING', 'OK\n'), ('ADBRECOVERYENSURE extra', 'ERR\n'),
                            ('ADBRECOVERYENSURE /proc/self/exe', 'ERR\n')):
                        s.sendall((command_text + '\n').encode())
                        assert line(s, 256) == expected, 'ordinary ENSURE argument control failed: ' + command_text
                # Recovery verbs terminate their main connection, including a refused request.
                for command_text in ('ARM ' + 'a'*64, 'BEAT ' + 'a'*64, 'RECOVER', 'STATUS', 'SHELL 1 0'):
                    with socket.socket(socket.AF_UNIX) as s:
                        s.settimeout(ENSURE_TIMEOUT); s.connect('\0' + sock + '-ordinary-' + NEW[0])
                        s.sendall((command_text + '\n').encode())
                        assert line(s, 256) == 'ERR\n', 'ordinary admin-endpoint control failed: ' + command_text
                        assert s.recv(1) == b'', 'refused recovery request did not close its connection: ' + command_text
                try: peer, _ = recovery_probe.accept()
                except BlockingIOError: pass
                else:
                    peer.close(); raise AssertionError('invalid ordinary ENSURE arguments probed the recovery endpoint')
            assert not list(live.iterdir()) and not (p/'ensure-spawns').exists(), 'invalid ordinary ENSURE arguments published or spawned recovery'
            print('PASS ensure ordinary-dispatch-controls', flush=True)
        def ensure(build=NEW, lost_ack=False):
            transcript = []; started = time.monotonic()
            try:
                with socket.socket(socket.AF_UNIX) as s:
                    s.settimeout(ENSURE_TIMEOUT); s.connect('\0' + sock + '-ordinary-' + build[0])
                    for command_text in ('VERSION', 'BUILDID'):
                        s.sendall((command_text + '\n').encode()); transcript.append(line(s, 256, started + ENSURE_TIMEOUT))
                    assert re.fullmatch(r'HELPER version=[0-9]+\.[0-9]+\.[0-9]+ proto=1\.[0-9]+\n', transcript[0]), 'ordinary VERSION response malformed'
                    assert transcript[1] == 'BUILDID ' + build + '\n', 'ordinary same-session BUILDID mismatch'
                    s.sendall(b'ADBRECOVERYENSURE\n')
                    if lost_ack: return None
                    response = line(s, 32, started + ENSURE_TIMEOUT); transcript.append(response)
                    assert response in ('ADBRECOVERYENSURE READY\n', 'ADBRECOVERYENSURE UNAVAILABLE\n'), 'ordinary dispatch did not expose the fixed ENSURE result'
                if response == 'ADBRECOVERYENSURE READY\n': ready_proof(build)
                return response
            except socket.timeout as failure:
                raise AssertionError('ENSURE exceeded its one bounded operation deadline') from failure
            finally:
                elapsed = time.monotonic() - started
                print('ENSURE', json.dumps(dict(build=build, elapsed=elapsed, transcript=transcript)), flush=True)
                assert elapsed <= ENSURE_TIMEOUT + .5, 'ENSURE exceeded its one bounded operation deadline'
        def launch_contender(**faults):
            if leg == 'direct': return launch_direct(**faults)
            for key, marker in (('scan_error','scan-error'), ('hide_listener','hide-listener'), ('receipt_sync_error','receipt-sync-error')):
                if faults.get(key): (p/marker).touch()
            return EnsureAttempt(ensure)
        def cleanup(contenders):
            active_failure = sys.exc_info()[0] is not None
            contender_failures = []
            for marker in ('guardian-block', 'ensure-before-exec-block'):
                (p/marker).unlink(missing_ok=True)
            try:
                for contender in contenders:
                    if isinstance(contender, EnsureAttempt):
                        try: contender.wait()
                        except (AssertionError, OSError) as failure: contender_failures.append(failure)
            finally:
                receipts = {}
                for name in ('ensure-spawns', 'ensure-signals', 'ensure-exec-contract', 'ensure-fsync', 'ensure-reaper-refused', 'ensure-before-exec-block.entered'):
                    path = p/name
                    if path.exists(): receipts[name] = path.read_text()
                custody = live/'.replacement-retiring'
                if custody.is_file() and not custody.is_symlink(): receipts['replacement-retiring'] = custody.read_text()
                print('RECEIPTS', json.dumps(receipts), flush=True)
                owned.cleanup()
                for marker in ('ensure-spawns', 'ensure-signals', 'ensure-exec-contract', 'scan-error', 'hide-listener', 'receipt-sync-error',
                               'ensure-copy-error', 'ensure-file-sync-error', 'ensure-dir-sync-error',
                               'ensure-exec-error', 'ensure-before-exec-block.entered', 'ensure-fsync',
                               'ensure-reaper-error', 'ensure-reaper-refused'):
                    (p/marker).unlink(missing_ok=True)
                if contender_failures:
                    print('CLEANUP_FAILURES', json.dumps([str(failure) for failure in contender_failures]), flush=True)
                    if not active_failure: raise contender_failures[0]
        for leg, case in ((leg, case) for leg, case, _ in runs if case in DIRECT_CASES):
            CASE_WAIT = ENSURE_TIMEOUT if leg == 'ensure' else 8
            owned = OwnedProcesses(p)
            ordinary = ordinary_start()[0] if leg == 'ensure' else None
            (p/'tcp').write_text('0: 00000000:15B3 00000000:0000 0A\n')
            (p/'tcp6').write_text(''); (p/'init.svc.adbd').write_text('running')
            incumbent_build=NEW if case=='matching' else OLD
            install(incumbent_build)
            if case in ('locked-fresh', 'malformed-custody', 'symlink-custody', 'fifo-custody'): install(NEW)
            if case=='locked-fresh':
                lock = os.open(live, os.O_RDONLY | os.O_DIRECTORY)
                fcntl.flock(lock, fcntl.LOCK_EX)
                contender = launch_contender()
                try:
                    wait(lambda: contender.poll() is not None or NEW in status(), 'fresh launcher lock wait was not bounded')
                    assert contender.poll() is not None and contender.returncode != 0 and not status(), 'unlocked launcher published successor'
                    print('PASS',leg,case,flush=True)
                finally:
                    os.close(lock)
                    cleanup([contender])
                continue
            if case in ('malformed-custody', 'symlink-custody', 'fifo-custody'):
                receipt = live/'.replacement-retiring'
                foreign_receipt = p/'foreign-receipt'
                foreign_receipt.write_text('unrelated data')
                if case=='symlink-custody': receipt.symlink_to(foreign_receipt)
                elif case=='fifo-custody': os.mkfifo(receipt, 0o600)
                else:
                    receipt.write_text('malformed custody')
                    receipt.chmod(0o600)
                contender = launch_contender()
                try:
                    wait(lambda: contender.poll() is not None or NEW in status() or OLD in status(), 'invalid custody refusal was not bounded')
                    assert contender.poll() is not None and contender.returncode != 0 and not status(), 'invalid custody published successor'
                    assert foreign_receipt.read_text()=='unrelated data', 'invalid custody changed foreign data'
                    assert receipt.is_symlink() if case=='symlink-custody' else receipt.exists(), 'invalid custody was discarded'
                    if case=='malformed-custody': assert receipt.read_text()=='malformed custody', 'malformed custody was changed'
                    print('PASS',leg,case,flush=True)
                finally:
                    cleanup([contender])
                    receipt.unlink()
                    foreign_receipt.unlink()
                continue
            foreign=p/'foreign-helper'
            if case in ('staggered', 'timeout-retry'): (p/'guardian-block').touch()
            incumbent=launch_direct(slow_guardian=case=='stale',block_guardian=case in ('staggered','timeout-retry')); contenders=[]; shell_socket=None
            try:
                wait(lambda: incumbent_build in status(),'incumbent did not start')
                if case == 'foreign-owner':
                    (live/'helper').replace(foreign)
                    assert os.readlink(Path('/proc')/str(incumbent.pid)/'exe') == str(foreign), 'foreign-owner fixture did not move the live incumbent executable path'
                assert request('ARM '+'a'*64)=='OK\n'
                assert not opened(), 'ARM must close ADB under live recovery owner'
                matching_before = ready_proof() if leg == 'ensure' and case == 'matching' else None
                if case=='stale':
                    command=f"echo $$ > '{p/'shell-pid'}'; sleep 5; echo escaped > '{p/'escaped'}'".encode()
                    shell_socket=socket.socket(socket.AF_UNIX)
                    shell_socket.connect('\0'+sock)
                    shell_socket.sendall(f'SHELL 10000 {len(command)}\n'.encode()+command)
                    wait(lambda:(p/'shell-pid').exists(),'old shell never executed')
                children=Path(f'/proc/{incumbent.pid}/task/{incumbent.pid}/children').read_text().split()
                if leg != 'ensure' or case != 'matching': install(NEW)
                if case=='failed-open': (p/'fail-open').touch()
                contenders=[launch_contender(scan_error=case=='scan-error',hide_listener=case=='unproven-open',receipt_sync_error=case=='receipt-sync-fails') for _ in range(2 if case=='concurrent' else 1)]
                if case in ('staggered', 'timeout-retry'):
                    wait(lambda: incumbent.poll() is not None, 'old owner did not exit for staggered handoff')
                    wait(lambda: (p/'guardian-block.entered').exists(), 'old guardian did not enter controlled drain')
                    if case=='timeout-retry':
                        wait(lambda: contenders[0].poll() is not None, 'first drain timeout was not bounded')
                        assert contenders[0].returncode != 0, 'timed-out drain reported success'
                    contenders.append(launch_contender())
                    wait(lambda: contenders[-1].poll() is not None or NEW in status(), 'blocked launcher timeout was not bounded')
                    assert NEW not in status(), 'successor publication bypassed replacement custody'
                    assert contenders[-1].returncode != 0, 'blocked launcher reported success'
                    assert opened(), 'blocked handoff must retain open recovery'
                    (p/'guardian-block').unlink()
                    if case=='timeout-retry' or leg == 'ensure': contenders.append(launch_contender())
                    wait(lambda: NEW in status(), 'retry did not publish after old guardian drained')
                    assert not (live/'.replacement-retiring').exists(), 'successful retry left drain custody behind'
                if case=='matching':
                    wait(lambda: contenders[0].poll() is not None or incumbent.poll() is not None,'matching launch did not finish')
                    assert incumbent.poll() is None and NEW in status(), 'matching daemon replaced'
                    assert not opened(), 'matching launch reopened managed ADB'
                    if leg == 'ensure':
                        assert contenders[0].wait() == 0, 'matching ENSURE did not report readiness'
                        matching_after = ready_proof()
                        assert all(matching_after[0][key] == matching_before[0][key] for key in ('pid', 'start', 'dev', 'ino', 'sid')) and matching_after[1] == matching_before[1], 'matching ENSURE changed owner identity or managed/hold/security status'
                elif case in ('failed-open','unproven-open','foreign-owner','receipt-sync-fails'):
                    wait(lambda: contenders[0].poll() is not None or incumbent.poll() is not None,'failed handoff did not finish')
                    assert incumbent.poll() is None and OLD in status(), 'refused handoff killed incumbent'
                    if case=='foreign-owner': assert not opened(), 'foreign owner was sent RECOVER'
                    if case=='receipt-sync-fails': assert opened(), 'failed custody persistence must leave recovery open'
                    if case=='unproven-open': assert opened(), 'RECOVER must establish actual route while probe refuses publication'
                    if case=='failed-open':
                        (p/'fail-open').unlink()
                        wait(opened,'incumbent recovery stopped after failed handoff')
                elif case=='scan-error':
                    wait(lambda: contenders[0].poll() is not None or NEW in status(),'unknown old custody did not refuse handoff')
                    assert contenders[0].returncode != 0 and not status(), 'unknown old custody published successor'
                    assert opened(), 'refused publication must preserve open ADB'
                else:
                    wait(lambda: NEW in status(),'stale daemon was not replaced')
                    wait(lambda: incumbent.poll() is not None,'old socket owner survived')
                    assert opened(), 'unarmed successor must leave ADB open'
                    assert request('ARM '+'b'*64)=='OK\n'
                    assert not opened(), 'new owner must re-establish recovery lease'
                    assert request('BEAT '+'b'*64)=='OK\n'
                    if leg == 'direct':
                        wait(lambda:sum(c.poll() is None for c in contenders)==1,'exactly one new daemon must remain')
                    else:
                        assert any(c.wait() == 0 for c in contenders), 'replacement ENSURE never reported a freshly verified READY endpoint'
                        ready_proof()
                    for pid in children:
                        stat=Path(f'/proc/{pid}/stat')
                        assert not stat.exists() or stat.read_text().split(') ',1)[1].startswith('Z'), 'old guardian/worker survived handoff'
                    if case=='stale':
                        shell_pid=(p/'shell-pid').read_text().strip()
                        def shell_gone():
                            stat=Path(f'/proc/{shell_pid}/stat')
                            return not stat.exists() or stat.read_text().split(') ',1)[1].startswith('Z')
                        wait(shell_gone,'old shell descendant survived replacement')
                        assert not (p/'escaped').exists(), 'old shell performed a post-handoff effect'
                print('PASS',leg,case,flush=True)
            finally:
                if shell_socket is not None: shell_socket.close()
                (p/'fail-open').unlink(missing_ok=True)
                (p/'guardian-block').unlink(missing_ok=True)
                (p/'guardian-block.entered').unlink(missing_ok=True)
                cleanup(contenders)
                # Independent cases must not inherit a deliberately refused drain's receipt.
                # Retry custody is exercised and preserved within the timeout-retry case.
                (live/'.replacement-retiring').unlink(missing_ok=True)

        def reset_live():
            if live.is_symlink(): live.unlink()
            elif live.exists(): shutil.rmtree(live)
            live.mkdir(mode=0o700)
            (p/'tcp').write_text('0: 00000000:15B3 00000000:0000 0A\n')
            (p/'tcp6').write_text(''); (p/'init.svc.adbd').write_text('running')

        def same_identity(first, second):
            return first and second and all(first[key] == second[key] for key in ('pid', 'start', 'dev', 'ino', 'sid'))

        def socket_inodes(pid):
            result = set()
            for fd in (Path('/proc')/str(pid)/'fd').iterdir():
                try:
                    target = os.readlink(fd)
                    if target.startswith('socket:['): result.add(target)
                except FileNotFoundError: pass
            return result

        if 'ensure' not in legs: return
        CASE_WAIT = ENSURE_TIMEOUT
        for case in dict.fromkeys(case for _, case, _ in runs if case in EXTRA_CASES):
            variants = tuple(variant for _, selected_case, variant in runs if selected_case == case)
            for variant in variants:
                reset_live(); owned = OwnedProcesses(p); attempts = []
                ordinary, ordinary_path = ordinary_start(padded_size=(8 * 1024 * 1024 + (variant == 'overflow')) if case == 'source-bound' else None)
                try:
                    if case == 'source-bound':
                        if variant == 'exact':
                            assert ensure() == 'ADBRECOVERYENSURE READY\n', 'ENSURE rejected the exact 8MiB executing source bound'
                            identity, _ = ready_proof()
                            assert (live/'helper').read_bytes() == ordinary_path.read_bytes() and (live/'helper').stat().st_size == 8 * 1024 * 1024, 'ENSURE truncated the exact-bound executing source publication'
                        else:
                            install(OLD); incumbent = launch_direct()
                            wait(lambda: OLD in status(), 'source-overflow incumbent did not start')
                            before = owned.record(incumbent.pid)
                            assert request('ARM ' + 'a'*64) == 'OK\n' and not opened(), 'source-overflow incumbent did not close ADB'
                            assert ensure() == 'ADBRECOVERYENSURE UNAVAILABLE\n', 'ENSURE admitted an executing source larger than 8MiB'
                            assert incumbent.poll() is None and same_identity(before, owned.record(incumbent.pid)) and OLD in status() and not opened(), 'overbound source launch changed incumbent identity or recovery intent'
                            assert not (p/'ensure-spawns').exists() and not any(path.name.startswith('.ensure-') for path in live.iterdir()), 'overbound source launch spawned or leaked a private publication stage'
                    elif case in ('fresh', 'source-pinned', 'lost-ack'):
                        if case == 'fresh':
                            ordinary_controls(); live.rmdir()
                        source = process_identity(ordinary.pid)
                        ordinary_sockets = socket_inodes(ordinary.pid)
                        assert (Path('/proc')/str(ordinary.pid)/'fd/128').exists(), 'ordinary high-FD sentinel control was not inherited above the lowered limit'
                        if case == 'source-pinned':
                            replacement = p/'replacement-ordinary'
                            shutil.copyfile(ordinary_binaries[THIRD], replacement); replacement.chmod(0o700)
                            replacement.replace(ordinary_path)
                            assert ordinary_path.read_bytes() != ordinary_binaries[NEW].read_bytes(), 'distinct-build source-path replacement control did not change bytes'
                        if case == 'lost-ack':
                            ensure(lost_ack=True)
                            wait(lambda: NEW in status(), 'lost ENSURE acknowledgement prevented recovery readiness')
                            before, _ = ready_proof()
                        assert ensure() == 'ADBRECOVERYENSURE READY\n', 'fresh ordinary ENSURE did not establish a verified recovery owner'
                        identity, _ = ready_proof()
                        if case == 'lost-ack':
                            assert same_identity(before, identity), 'ENSURE retry replaced an already-ready owner after lost acknowledgement'
                        metadata = (live/'helper').stat()
                        assert (identity['dev'], identity['ino']) == (metadata.st_dev, metadata.st_ino), 'isolated fresh ENSURE endpoint did not execute the current durable published inode'
                        assert metadata.st_uid == metadata.st_gid == 0 and metadata.st_mode & 0o7777 == 0o700 and metadata.st_nlink == 1, 'ENSURE published unsafe durable executable metadata'
                        assert (live/'helper').read_bytes() == ordinary_binaries[NEW].read_bytes(), 'ENSURE did not publish the executing ordinary source bytes'
                        sync = (p/'ensure-fsync').read_text().splitlines()
                        assert all(kind in sync for kind in ('FILE_OK', 'RENAME', 'DIR_AFTER_OK')), 'ENSURE READY omitted a required successful publication durability step'
                        assert sync.index('FILE_OK') < sync.index('RENAME') < sync.index('DIR_AFTER_OK'), 'ENSURE publication durability order was not file then rename then directory'
                        if case == 'fresh':
                            assert 'DIR_PRE_OK' in sync and 'PARENT_OK' in sync, 'new recovery directory was not durably synced with its parent'
                        assert (identity['dev'], identity['ino']) != (source['dev'], source['ino']), 'ENSURE executed the ordinary inode instead of its durable published copy'
                        assert identity['sid'] == identity['pid'] and identity['sid'] != source['sid'], 'ENSURE recovery did not detach from the ordinary session'
                        assert not (socket_inodes(identity['pid']) & ordinary_sockets), 'recovery inherited an ordinary listener or connection descriptor'
                        contracts = (p/'ensure-exec-contract').read_text().splitlines()
                        assert f"{identity['pid']} VALID" in contracts and all(row.endswith(' VALID') for row in contracts), 'ENSURE exec contract rejected fixed argv environment read-only pin or descriptor custody'
                        process_root = Path('/proc')/str(identity['pid'])
                        assert (process_root/'cmdline').read_bytes() == b'hapaneld-helper\0--adb-recovery\0', 'ENSURE recovery exec arguments were not exactly the fixed recovery invocation'
                        expected_environment = {b'PATH=/system/bin:/vendor/bin', b'ANDROID_ROOT=/system', b'ANDROID_DATA=/data', b'LANG=C', b'LC_ALL=C'}
                        environment = (process_root/'environ').read_bytes().split(b'\0')
                        assert environment[-1] == b'' and len(environment) == 6 and set(environment[:-1]) == expected_environment, 'ENSURE recovery exec environment was not exactly the fixed clean five variables'
                        assert not (process_root/'fd/128').exists(), 'ENSURE recovery inherited a descriptor above the lowered soft limit'
                        ordinary.terminate(); ordinary.wait(timeout=5)
                        assert same_identity(identity, ready_proof()[0]), 'ordinary helper exit terminated or replaced the independent recovery owner'
                        with socket.socket(socket.AF_UNIX) as s:
                            try: s.connect('\0' + sock + '-ordinary-' + NEW[0])
                            except OSError: pass
                            else: raise AssertionError('detached recovery kept the ordinary socket listener alive')
                        print('PUBLICATION', json.dumps(dict(case=case, sha256=hashlib.sha256((live/'helper').read_bytes()).hexdigest(), identity=identity)), flush=True)
                    elif case in ('unsafe-target', 'publication-faults'):
                        install(OLD); incumbent = launch_direct()
                        wait(lambda: OLD in status(), 'fault-case incumbent did not start')
                        before = owned.record(incumbent.pid)
                        assert request('ARM ' + 'a'*64) == 'OK\n' and not opened(), 'fault-case incumbent did not close ADB'
                        foreign = p/'foreign-target'; foreign.write_bytes(b'unrelated executable bytes'); foreign.chmod(0o700)
                        if case == 'unsafe-target':
                            (live/'helper').unlink()
                            if variant == 'symlink': (live/'helper').symlink_to(foreign)
                            elif variant == 'fifo': os.mkfifo(live/'helper', 0o700)
                            elif variant == 'directory': (live/'helper').mkdir(mode=0o700)
                            elif variant == 'hardlink': os.link(foreign, live/'helper')
                            elif variant in ('mode', 'owner'):
                                shutil.copyfile(foreign, live/'helper'); (live/'helper').chmod(0o600 if variant == 'mode' else 0o700)
                                if variant == 'owner': os.chown(live/'helper', 12345, 12345)
                            else:
                                live.rmdir()
                                foreign_directory = p/'foreign-directory'; foreign_directory.mkdir(mode=0o700)
                                (foreign_directory/'helper').write_bytes(b'unrelated directory helper')
                                live.symlink_to(foreign_directory, target_is_directory=True)
                        else: (p/variant).touch()
                        if case == 'publication-faults' and variant == 'ensure-exec-error':
                            for attempt_index in range(3):
                                prior_spawns = len((p/'ensure-spawns').read_text().splitlines()) if (p/'ensure-spawns').exists() else 0
                                assert ensure() == 'ADBRECOVERYENSURE UNAVAILABLE\n', 'failed exec did not return bounded ENSURE UNAVAILABLE'
                                assert len((p/'ensure-spawns').read_text().splitlines()) == prior_spawns + 1, 'failed exec exhausted detached child slots instead of reaping and permitting another launch'
                        else:
                            assert ensure() == 'ADBRECOVERYENSURE UNAVAILABLE\n', 'unsafe or failed publication falsely reported ENSURE READY'
                        assert incumbent.poll() is None and OLD in status() and same_identity(before, owned.record(incumbent.pid)), 'failed ENSURE publication signalled or replaced its incumbent'
                        assert not opened(), 'failed ENSURE launch sent RECOVER to the incumbent'
                        assert foreign.read_bytes() == b'unrelated executable bytes', 'failed ENSURE changed unrelated target bytes'
                        if case == 'unsafe-target' and variant == 'directory-symlink':
                            assert (p/'foreign-directory'/'helper').read_bytes() == b'unrelated directory helper', 'ENSURE followed a foreign recovery-directory symlink'
                        if case == 'publication-faults':
                            assert (live/'helper').is_file(), 'post-rename ENSURE failure removed its canonical published executable'
                            published = (live/'helper').stat()
                            if variant in ('ensure-dir-sync-error', 'ensure-exec-error'):
                                assert (live/'helper').read_bytes() == ordinary_binaries[NEW].read_bytes(), 'post-rename failure removed the valid canonical publication'
                            else:
                                assert (published.st_dev, published.st_ino) == (before['dev'], before['ino']), 'pre-rename failure replaced the incumbent canonical inode'
                            assert not any(path.name.startswith('.ensure-') for path in live.iterdir()), 'failed publication leaked its private stage'
                            (p/variant).unlink()
                            assert ensure() == 'ADBRECOVERYENSURE READY\n', 'ENSURE retry did not converge after publication fault cleared'
                    elif case == 'reaper-failure':
                        (p/'ensure-reaper-error').touch()
                        assert ensure() == 'ADBRECOVERYENSURE UNAVAILABLE\n', 'failed reaper reservation falsely reported ENSURE READY'
                        assert (p/'ensure-reaper-refused').read_text() == 'REAPER\n', 'reaper-resource failure seam did not execute'
                        assert not (p/'ensure-spawns').exists() and not list(live.iterdir()), 'reaper failure spawned or published without child ownership'
                        (p/'ensure-reaper-error').unlink()
                        assert ensure() == 'ADBRECOVERYENSURE READY\n', 'ENSURE retry failed after reaper resources became available'
                    elif case == 'readiness-faults':
                        shutil.copyfile(ordinary_binaries[NEW], live/'helper'); (live/'helper').chmod(0o700)
                        if variant == 'stall': (p/'fake-stall').touch()
                        fake = owned.add(subprocess.Popen([str(live/'helper'), '--fake-recovery', variant], start_new_session=True))
                        def fake_ready():
                            try:
                                with socket.socket(socket.AF_UNIX) as s:
                                    s.connect('\0' + sock)
                                    pid, uid, _ = struct.unpack('3i', s.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                                    return pid == fake.pid and uid == 0
                            except OSError: return False
                        wait(fake_ready, 'canonical fault peer did not bind', seconds=5)
                        before = owned.record(fake.pid)
                        started = time.monotonic(); attempt = EnsureAttempt(ensure); attempts.append(attempt)
                        if variant == 'stall':
                            wait(lambda: (p/'ensure-spawns').exists(), 'initial stalled STATUS consumed the operation budget before publication/spawn', seconds=3)
                            assert time.monotonic() - started < 3.5 and attempt.poll() is None, 'initial STATUS probe did not leave bounded time for launch/readiness'
                        assert attempt.wait() != 0, 'failed or unavailable recovery STATUS falsely reported ENSURE READY'
                        assert fake.poll() is None and same_identity(before, owned.record(fake.pid)), 'failed readiness proof signalled its unproven incumbent'
                        (p/'fake-stall').unlink(missing_ok=True)
                        fake.terminate(); fake.wait(timeout=5)
                        assert ensure() == 'ADBRECOVERYENSURE READY\n', 'ENSURE retry did not establish real recovery after invalid STATUS peer left'
                        matched = ready_proof()[0]
                        assert ensure() == 'ADBRECOVERYENSURE READY\n' and same_identity(matched, ready_proof()[0]), 'fresh matching retry replaced the verified recovery owner'
                    elif case == 'superseded':
                        block = p/'ensure-before-exec-block'; block.touch()
                        first = EnsureAttempt(ensure); attempts.append(first)
                        wait(lambda: (p/'ensure-before-exec-block.entered').exists(), 'stale contender never reached the publication-to-exec barrier')
                        ordinary_start(THIRD)
                        # The cut blocks only NEW; the different executing build can publish and win.
                        assert ensure(THIRD) == 'ADBRECOVERYENSURE READY\n', 'newer internally published contender did not become ready'
                        winner, winner_status = ready_proof(THIRD)
                        block.unlink(); first.wait()
                        assert same_identity(winner, ready_proof(THIRD)[0]), 'superseded pinned child replaced the newer recovery owner'
                        assert request('STATUS') == winner_status, 'superseded pinned child changed newer managed/hold/security state'
                        assert not (live/'.replacement-retiring').exists(), 'superseded child mutated replacement custody'
                    print('PASS', 'ensure', case, variant, flush=True)
                finally:
                    for marker in ('ensure-copy-error', 'ensure-file-sync-error', 'ensure-dir-sync-error', 'ensure-exec-error'):
                        (p/marker).unlink(missing_ok=True)
                    (p/'fake-stall').unlink(missing_ok=True)
                    cleanup(attempts)

if __name__=='__main__':
    if sys.argv[1:] == ['--plan']:
        for run_selected in selection()[2]: print(' '.join(field or '-' for field in run_selected))
        raise SystemExit(0)
    try: run()
    except AssertionError as failure:
        print('FAIL:', failure, flush=True)
        raise SystemExit(1)
