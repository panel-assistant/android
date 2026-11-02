#!/usr/bin/env python3
"""Replace a real incumbent through the production --adb-recovery entry, with isolated Android I/O."""
import os
import fcntl
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
OLD = '1' * 64
NEW = '2' * 64

def wait(predicate, message, seconds=8):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(.02)
    raise AssertionError(message)

def run():
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
                 'ADB_RECOVERY_SOCKET':sock, 'ADB_RECOVERY_SHELL':'/bin/sh'}
        sources = subprocess.check_output(['make','--no-print-directory','-s','print-core'],cwd=ROOT,text=True).split()
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
                            'src/main.c','src/sysexec.c','test/adb_recovery_replacement_cuts.c',
                            '-Wl,--wrap=kill','-Wl,--wrap=fstatat','-Wl,--wrap=fopen','-Wl,--wrap=usleep','-Wl,--wrap=fsync','-lpthread','-o',str(binary)],cwd=ROOT,check=True)
            binaries[build]=binary
        def request(command):
            with socket.socket(socket.AF_UNIX) as s:
                s.settimeout(5)
                s.connect('\0'+sock)
                s.sendall((command+'\n').encode())
                return s.recv(256).decode()
        def status():
            try: return request('STATUS')
            except OSError: return ''
        def opened():
            return (p/'init.svc.adbd').read_text()=='running' and '15B3' in (p/'tcp').read_text()
        def install(build):
            stage=live/'stage'
            shutil.copyfile(binaries[build],stage); stage.chmod(0o700)
            stage.replace(live/'helper')
        def launch(executable=None, scan_error=False, hide_listener=False, slow_guardian=False, block_guardian=False, receipt_sync_error=False):
            return subprocess.Popen([str(executable or live/'helper'),'--adb-recovery'],start_new_session=True,
                                    env={**os.environ, **({'RECOVERY_REPLACEMENT_SCAN_ERROR':'1'} if scan_error else {}),
                                         **({'RECOVERY_REPLACEMENT_HIDE_LISTENER':'1'} if hide_listener else {}),
                                         **({'RECOVERY_REPLACEMENT_SLOW_GUARDIAN':'1'} if slow_guardian else {}),
                                         **({'RECOVERY_REPLACEMENT_GUARDIAN_BLOCK':str(p/'guardian-block')} if block_guardian else {}),
                                         **({'RECOVERY_REPLACEMENT_RECEIPT_SYNC_ERROR':'1'} if receipt_sync_error else {})})
        for case in os.environ.get('RECOVERY_REPLACEMENT_CASES','locked-fresh,stale,matching,failed-open,unproven-open,foreign-owner,scan-error,concurrent,staggered,timeout-retry,malformed-custody,symlink-custody,fifo-custody,receipt-sync-fails').split(','):
            (p/'tcp').write_text('0: 00000000:15B3 00000000:0000 0A\n')
            (p/'tcp6').write_text(''); (p/'init.svc.adbd').write_text('running')
            incumbent_build=NEW if case=='matching' else OLD
            install(incumbent_build)
            if case in ('locked-fresh', 'malformed-custody', 'symlink-custody', 'fifo-custody'): install(NEW)
            if case=='locked-fresh':
                lock = os.open(live, os.O_RDONLY | os.O_DIRECTORY)
                fcntl.flock(lock, fcntl.LOCK_EX)
                contender = launch()
                try:
                    wait(lambda: contender.poll() is not None or NEW in status(), 'fresh launcher lock wait was not bounded')
                    assert contender.poll() is not None and contender.returncode != 0 and not status(), 'unlocked launcher published successor'
                    print('PASS',case,flush=True)
                finally:
                    os.close(lock)
                    if contender.poll() is None: os.killpg(contender.pid,signal.SIGKILL)
                    contender.wait(timeout=5)
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
                contender = launch()
                try:
                    wait(lambda: contender.poll() is not None or NEW in status() or OLD in status(), 'invalid custody refusal was not bounded')
                    assert contender.poll() is not None and contender.returncode != 0 and not status(), 'invalid custody published successor'
                    assert foreign_receipt.read_text()=='unrelated data', 'invalid custody changed foreign data'
                    assert receipt.is_symlink() if case=='symlink-custody' else receipt.exists(), 'invalid custody was discarded'
                    if case=='malformed-custody': assert receipt.read_text()=='malformed custody', 'malformed custody was changed'
                    print('PASS',case,flush=True)
                finally:
                    if contender.poll() is None: os.killpg(contender.pid,signal.SIGKILL)
                    contender.wait(timeout=5)
                    receipt.unlink()
                    foreign_receipt.unlink()
                continue
            foreign=p/'foreign-helper'
            if case=='foreign-owner':
                shutil.copyfile(binaries[OLD],foreign); foreign.chmod(0o700)
            if case in ('staggered', 'timeout-retry'): (p/'guardian-block').touch()
            incumbent=launch(foreign if case=='foreign-owner' else None,slow_guardian=case=='stale',block_guardian=case in ('staggered','timeout-retry')); contenders=[]; shell_socket=None
            try:
                wait(lambda: incumbent_build in status(),'incumbent did not start')
                assert request('ARM '+'a'*64)=='OK\n'
                assert not opened(), 'ARM must close ADB under live recovery owner'
                if case=='stale':
                    command=f"echo $$ > '{p/'shell-pid'}'; sleep 5; echo escaped > '{p/'escaped'}'".encode()
                    shell_socket=socket.socket(socket.AF_UNIX)
                    shell_socket.connect('\0'+sock)
                    shell_socket.sendall(f'SHELL 10000 {len(command)}\n'.encode()+command)
                    wait(lambda:(p/'shell-pid').exists(),'old shell never executed')
                children=Path(f'/proc/{incumbent.pid}/task/{incumbent.pid}/children').read_text().split()
                install(NEW)
                if case=='failed-open': (p/'fail-open').touch()
                contenders=[launch(scan_error=case=='scan-error',hide_listener=case=='unproven-open',receipt_sync_error=case=='receipt-sync-fails') for _ in range(2 if case=='concurrent' else 1)]
                if case in ('staggered', 'timeout-retry'):
                    wait(lambda: incumbent.poll() is not None, 'old owner did not exit for staggered handoff')
                    wait(lambda: (p/'guardian-block.entered').exists(), 'old guardian did not enter controlled drain')
                    if case=='timeout-retry':
                        wait(lambda: contenders[0].poll() is not None, 'first drain timeout was not bounded')
                        assert contenders[0].returncode != 0, 'timed-out drain reported success'
                    contenders.append(launch())
                    wait(lambda: contenders[-1].poll() is not None or NEW in status(), 'blocked launcher timeout was not bounded')
                    assert NEW not in status(), 'successor publication bypassed replacement custody'
                    assert contenders[-1].returncode != 0, 'blocked launcher reported success'
                    assert opened(), 'blocked handoff must retain open recovery'
                    (p/'guardian-block').unlink()
                    if case=='timeout-retry': contenders.append(launch())
                    wait(lambda: NEW in status(), 'retry did not publish after old guardian drained')
                    assert not (live/'.replacement-retiring').exists(), 'successful retry left drain custody behind'
                if case=='matching':
                    wait(lambda: contenders[0].poll() is not None or incumbent.poll() is not None,'matching launch did not finish')
                    assert incumbent.poll() is None and NEW in status(), 'matching daemon replaced'
                    assert not opened(), 'matching launch reopened managed ADB'
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
                    wait(lambda:sum(c.poll() is None for c in contenders)==1,'exactly one new daemon must remain')
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
                print('PASS',case,flush=True)
            finally:
                if shell_socket is not None: shell_socket.close()
                (p/'fail-open').unlink(missing_ok=True)
                (p/'guardian-block').unlink(missing_ok=True)
                (p/'guardian-block.entered').unlink(missing_ok=True)
                for child in contenders+[incumbent]:
                    # A dead owner can still have a live guardian in its isolated group.
                    try: os.killpg(child.pid,signal.SIGKILL)
                    except ProcessLookupError: pass
                    child.wait(timeout=5)
                time.sleep(.25)
                # Independent cases must not inherit a deliberately refused drain's receipt.
                # Retry custody is exercised and preserved within the timeout-retry case.
                (live/'.replacement-retiring').unlink(missing_ok=True)

if __name__=='__main__':
    try: run()
    except AssertionError as failure:
        print('FAIL:', failure, flush=True)
        raise SystemExit(1)
