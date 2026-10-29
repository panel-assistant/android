#!/usr/bin/env python3
"""Replace a real incumbent through the production --adb-recovery entry, with isolated Android I/O."""
import os
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
                            '-Wl,--wrap=kill','-Wl,--wrap=fstatat','-Wl,--wrap=fopen','-Wl,--wrap=usleep','-lpthread','-o',str(binary)],cwd=ROOT,check=True)
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
        def launch(executable=None, scan_error=False, hide_listener=False, slow_guardian=False):
            return subprocess.Popen([str(executable or live/'helper'),'--adb-recovery'],start_new_session=True,
                                    env={**os.environ, **({'RECOVERY_REPLACEMENT_SCAN_ERROR':'1'} if scan_error else {}),
                                         **({'RECOVERY_REPLACEMENT_HIDE_LISTENER':'1'} if hide_listener else {}),
                                         **({'RECOVERY_REPLACEMENT_SLOW_GUARDIAN':'1'} if slow_guardian else {})})
        for case in os.environ.get('RECOVERY_REPLACEMENT_CASES','stale,matching,failed-open,unproven-open,foreign-owner,scan-error,concurrent').split(','):
            (p/'tcp').write_text('0: 00000000:15B3 00000000:0000 0A\n')
            (p/'tcp6').write_text(''); (p/'init.svc.adbd').write_text('running')
            incumbent_build=NEW if case=='matching' else OLD
            install(incumbent_build)
            foreign=p/'foreign-helper'
            if case=='foreign-owner':
                shutil.copyfile(binaries[OLD],foreign); foreign.chmod(0o700)
            incumbent=launch(foreign if case=='foreign-owner' else None,slow_guardian=case=='stale'); contenders=[]; shell_socket=None
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
                contenders=[launch(scan_error=case=='scan-error',hide_listener=case=='unproven-open') for _ in range(2 if case=='concurrent' else 1)]
                if case=='matching':
                    wait(lambda: contenders[0].poll() is not None or incumbent.poll() is not None,'matching launch did not finish')
                    assert incumbent.poll() is None and NEW in status(), 'matching daemon replaced'
                    assert not opened(), 'matching launch reopened managed ADB'
                elif case in ('failed-open','unproven-open','foreign-owner'):
                    wait(lambda: contenders[0].poll() is not None or incumbent.poll() is not None,'failed handoff did not finish')
                    assert incumbent.poll() is None and OLD in status(), 'refused handoff killed incumbent'
                    if case=='foreign-owner': assert not opened(), 'foreign owner was sent RECOVER'
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
                for child in contenders+[incumbent]:
                    if child.poll() is None:
                        try: os.killpg(child.pid,signal.SIGKILL)
                        except ProcessLookupError: pass
                    child.wait(timeout=5)
                time.sleep(.25)

if __name__=='__main__':
    try: run()
    except AssertionError as failure:
        print('FAIL:', failure, flush=True)
        raise SystemExit(1)
