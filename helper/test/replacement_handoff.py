#!/usr/bin/env python3
"""Build helpers for the real app replacement and stale-owner recovery callers.

HAPANELD_INCUMBENT_SOURCE may name an untouched historical source checkout to qualify
an installed incumbent independently of the candidate. The default old role models the
pre-merge worker-owned listener and occupied-FD3 defect; it has no merged recovery owner.
The candidate uses its real main. This fixture qualifies Guard replacement, while the
separate main-socket composition fixture qualifies integrated recovery custody.
"""
import os
from pathlib import Path
import shutil
import subprocess
import socket
import sys

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise AssertionError('historical incumbent fixture seam changed: ' + old)
    return text.replace(old, new, 1)

if sys.argv[1] == '--request':
    root = Path(sys.argv[2])
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(3)
            client.connect('\0' + (root/'socket').read_text())
            client.sendall((sys.argv[3] + '\n').encode())
            reply = b''
            while not reply.endswith(b'\n'):
                block = client.recv(4096)
                if not block:
                    break
                reply += block
            sys.stdout.buffer.write(reply)
    except (OSError, TimeoutError):
        sys.exit(1)
    sys.exit(0)

root = Path(sys.argv[1]).resolve()
candidate = Path(os.environ.get('HAPANELD_CANDIDATE_SOURCE', Path(__file__).resolve().parents[2]))
incumbent = Path(os.environ.get('HAPANELD_INCUMBENT_SOURCE', candidate))
local = root/'data/local'
local.mkdir(parents=True, exist_ok=True)
local.chmod(0o751)
(root/'dev').mkdir(exist_ok=True)
(root/'dev').chmod(0o755)
(root/'app').mkdir(exist_ok=True)
(root/'boot').write_text('123e4567-e89b-12d3-a456-426614174000')
socket_name = 'handoff-' + root.name
(root/'socket').write_text(socket_name)
for role, source, build in [('old', incumbent, 'b'*64), ('new', candidate, 'a'*64)]:
    helper = root/role
    shutil.copytree(source/'helper/src', helper/'src')
    remap = {
        '#define GUARD_PARENT "/data/local"': f'#define GUARD_PARENT "{local}"',
        '#define GUARD_APP_HELPER_PARENT "/data/local"': f'#define GUARD_APP_HELPER_PARENT "{local}"',
        '#define HELPER_TRANSACTION_LOCK_PARENT "/dev"': f'#define HELPER_TRANSACTION_LOCK_PARENT "{root}/dev"',
        '"/proc/sys/kernel/random/boot_id"': f'"{root}/boot"',
        '"/data/local/hapaneld-helper"': f'"{local}/hapaneld-helper"',
        '"/data/local/.hapaneld-helper.new"': f'"{local}/.hapaneld-helper.new"',
    }
    for path in (helper/'src').glob('*.[ch]'):
        text = path.read_text()
        for old, new in remap.items():
            text = text.replace(old, new)
        # The default fixture preserves the occupied-FD3 defect in the old role. Qualification
        # can instead name an untouched released source tree, with no behavioral substitutions.
        if role == 'old' and 'HAPANELD_INCUMBENT_SOURCE' not in os.environ:
            if path.name == 'guard_replacement.c':
                text = replace_once(text, '!guard_maintenance_supervisor_authoritative() || !nonce ||\n        guard_maintenance_replacement_export_lease() != 0',
                                    '!guard_maintenance_supervisor_authoritative() || !nonce')
                text = replace_once(text, '    /* FD3 can be /dev/null inherited from su.',
                                    '    if (guard_owner_fd != 3 && fcntl(3, F_GETFD) >= 0) return -1;\n    /* FD3 can be /dev/null inherited from su.')
            if path.name == 'main.c':
                text = replace_once(text, 'GUARD_REPLACEMENT_READY) {',
                                    'GUARD_REPLACEMENT_READY || guard_maintenance_replacement_export_lease() != 0) {')
                # Historical SO_PEERCRED identifies the accepting worker, whose parent owns
                # the Guard lease. The merged parent-owned listener is not that topology.
                text = replace_once(text,
                                    '    int listener = open_main_listener(may_retire_replaced);\n'
                                    '    if (listener < 0) return 1;\n',
                                    '    int listener = may_retire_replaced;\n')
                text = replace_once(text, '    int recovery = adb_recovery_owner_init() == 0;',
                                    '    int recovery = 0;')
                # The incumbent stands in for a release without custody retries.
                text = replace_once(text,
                                    '        if (!recovery && executor <= 1 && pending_work == GUARD_WORK_NONE',
                                    '        if (0 && !recovery && executor <= 1 && pending_work == GUARD_WORK_NONE')
                text = replace_once(text,
                                    '    _exit(run_daemon(argc, argv, replacement_nonce, listener));',
                                    '    listener = open_main_listener(listener);\n'
                                    '    if (listener < 0) _exit(1);\n'
                                    '    _exit(run_daemon(argc, argv, replacement_nonce, listener));')
        path.write_text(text)
    command = ['cc', '-O1', '-g', '-Wall', '-Wextra', '-Werror', '-Wno-format-truncation',
               '-DHAPANELD_BUILD_ID="'+build+'"',
               '-DSOCK_NAME="'+socket_name+'"', '-DAPP_DATA="'+str(root/'app')+'"',
               '-I'+str(helper/'src'), *map(str, sorted((helper/'src').glob('*.c'))),
               '-lpthread', '-o', str(root/role/'helper')]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
shutil.copyfile(root/'old/helper', local/'hapaneld-helper')
(local/'hapaneld-helper').chmod(0o700)
shutil.copyfile(root/'new/helper', root/'asset')
(root/'asset').chmod(0o700)
launch = 'exec 3</dev/null; exec "$1" --supervise'
process = subprocess.Popen(['bash', '-c', launch, 'handoff', str(local/'hapaneld-helper')],
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, start_new_session=True)
(root/'incumbent.pid').write_text(str(process.pid))
print('real incumbent started', process.pid, flush=True)
