#!/usr/bin/env python3
"""Drive the shipped --adb-recovery entry with isolated Android property/filesystem fixtures."""
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]

def wait_for(predicate, message, seconds=6):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(.02)
    raise AssertionError(message)

def run():
    with tempfile.TemporaryDirectory(prefix='adb-retirement-') as temp:
        p = Path(temp)
        app, user, live = p/'app', p/'user', p/'recovery'
        sock = 'adb-retirement-' + str(os.getpid())
        prop = p/'property'
        prop.write_text('''#!/usr/bin/python3
from pathlib import Path
import sys, time
p=Path(__file__).parent
if len(sys.argv)==2:
 print((p/sys.argv[1]).read_text() if (p/sys.argv[1]).exists() else '')
else:
 name,value=sys.argv[1:]
 (p/name).write_text(value)
 if name=='ctl.restart':
  with (p/'restarts').open('a') as log: log.write('restart\\n')
  (p/'reopening').touch()
  while (p/'block').exists(): time.sleep(.01)
  if (p/'fail').exists(): sys.exit(1)
  (p/'init.svc.adbd').write_text('running')
  (p/'tcp').write_text('0: 00000000:15B3 00000000:0000 0A\\n')
 if name=='ctl.stop':
  (p/'init.svc.adbd').write_text('stopped')
  (p/'tcp').write_text('')
''')
        prop.chmod(0o700)
        binary = p/'fixture'
        flags = {'APP_DATA':app, 'APP_USER_DATA':user, 'ADB_RECOVERY_DIRECTORY':live,
                 'ADB_RECOVERY_GETPROP':prop, 'ADB_RECOVERY_SETPROP':prop,
                 'ADB_RECOVERY_TCP':p/'tcp', 'ADB_RECOVERY_TCP6':p/'tcp6',
                 'ADB_RECOVERY_SOCKET':sock, 'ADB_RECOVERY_SHELL':'/bin/sh'}
        sources = subprocess.check_output(['make','--no-print-directory','-s','print-core'],cwd=ROOT,text=True,
                                          env={k: v for k, v in os.environ.items() if k not in ('MAKEFLAGS', 'MFLAGS', 'MAKELEVEL')}).split()
        subprocess.run(['cc','-O1','-g','-Wall','-Wextra','-Werror','-Wno-format-truncation',
                        '-Isrc', *[f'-D{k}="{v}"' for k,v in flags.items()],
                        *sources,'src/main.c','src/sysexec.c','test/adb_recovery_retirement_cuts.c',
                        '-Wl,--wrap=main','-Wl,--wrap=unlinkat','-Wl,--wrap=rmdir','-Wl,--wrap=mkdirat','-lpthread','-o',str(binary)],cwd=ROOT,check=True,preexec_fn=lambda: os.nice(10))
        def request(command):
            with socket.socket(socket.AF_UNIX) as s:
                s.settimeout(4)
                s.connect('\0'+sock)
                s.sendall((command+'\n').encode())
                return s.recv(256).decode()
        def ready():
            try: return request('STATUS').startswith('READY')
            except OSError: return False
        cases = ('armed', 'never-armed', 'reappeared', 'replaced', 'late-return', 'directory-return', 'replacement-race', 'network-fails', 'running-worker', 'already-open', 'foreign-stage', 'return-again', 'retirement-fails')
        for case in os.environ.get('RECOVERY_CASES', ','.join(cases)).split(','):
            app.mkdir(); user.mkdir(); live.mkdir(mode=0o700)
            exe=live/'helper'; shutil.copyfile(binary,exe); exe.chmod(0o700)
            (p/'tcp').write_text('0: 00000000:15B3 00000000:0000 0A\n')
            (p/'tcp6').write_text(''); (p/'init.svc.adbd').write_text('running')
            owner=subprocess.Popen([str(exe),'--adb-recovery'],start_new_session=True,
                                   env={**os.environ, 'RECOVERY_RETIREMENT_CUT':case})
            try:
                wait_for(ready,'daemon startup')
                children=Path(f'/proc/{owner.pid}/task/{owner.pid}/children')
                guardian=int(children.read_text().split()[0])
                if case!='never-armed':
                    assert request('ARM '+'a'*64)=='OK\n'
                    assert request('RECOVER '+'a'*64)=='OK\n'
                    assert (p/'persist.adb.tcp.port').read_text()=='5555'
                worker_socket = None
                if case=='running-worker':
                    command=f'echo $$ > {p}/shell-pid; sleep 30'
                    worker_socket=socket.socket(socket.AF_UNIX)
                    worker_socket.connect('\0'+sock)
                    worker_socket.sendall(f'SHELL 60000 {len(command)}\n{command}'.encode())
                    wait_for(lambda:(p/'shell-pid').exists(),'shell worker did not start')
                if case=='network-fails':
                    (p/'fail').touch()
                    (p/'init.svc.adbd').write_text('stopped')
                    (p/'tcp').write_text('')
                restart_count=lambda:len((p/'restarts').read_text().splitlines()) if (p/'restarts').exists() else 0
                before_restarts=restart_count()
                if case=='foreign-stage': (live/'.stage-foreign').write_text('keep')
                if case in ('already-open','foreign-stage'):
                    shutil.rmtree(app); shutil.rmtree(user)
                    wait_for(lambda:owner.poll() is not None,'already-open retirement must finish')
                    assert restart_count()==before_restarts, 'already-open network ADB must not restart on removal'
                    if case=='foreign-stage':
                        assert (live/'.stage-foreign').read_text()=='keep' and not exe.exists(), 'foreign stage must survive without keeping root owner'
                    else: assert not live.exists()
                    print('PASS',case,flush=True)
                    continue
                (p/'init.svc.adbd').write_text('stopped')
                (p/'tcp').write_text('')
                (p/'reopening').unlink(missing_ok=True)
                (p/'block').touch()
                shutil.rmtree(app); shutil.rmtree(user)
                wait_for(lambda:(p/'reopening').exists(),'absence reopens network first')
                assert exe.exists() and owner.poll() is None, 'cleanup preceded network proof'
                if case=='reappeared': app.mkdir()
                if case=='replaced':
                    replacement=live/'replacement'
                    replacement.write_text('foreign replacement')
                    replacement.replace(exe)
                (p/'block').unlink()
                if case=='network-fails':
                    time.sleep(1.4)
                    assert restart_count()-before_restarts<=2, 'failed retirement retries must be bounded'
                    assert owner.poll() is None and exe.exists(), 'failed network recovery must retain owner and executable'
                    (p/'fail').unlink()
                if case=='retirement-fails':
                    time.sleep(1.4)
                    assert len(Path(str(app)+'.attempts').read_text())<=2, 'retirement retries must be bounded'
                    assert ready() and exe.exists(), 'failed retirement must retain owner and executable'
                    assert restart_count()-before_restarts==1, 'failed retirement must not restart recovered ADB'
                    print('PASS',case,flush=True)
                    continue
                if case in ('reappeared','late-return','directory-return','return-again'):
                    time.sleep(.5)
                    assert ready() and exe.exists() and live.exists(), 'app return must cancel retirement' if case in ('reappeared','late-return','directory-return') else 'replacement executable must survive'
                else:
                    wait_for(lambda:owner.poll() is not None,'owner did not retire')
                    assert owner.returncode==0
                    assert not Path(f'/proc/{guardian}').exists(), 'guardian survived'
                    if case not in ('replaced','replacement-race'):
                        assert not live.exists(), 'root executable directory survived'
                    else:
                        assert exe.exists(), 'replacement executable must survive'
                    assert (p/'init.svc.adbd').read_text()=='running'
                if case=='return-again':
                    shutil.rmtree(app)
                    wait_for(lambda:owner.poll() is not None,'second removal after return must retire restored executable')
                    assert not live.exists(), 'second removal must remove restored executable directory'
                if case!='network-fails':
                    assert restart_count()-before_restarts<=1, 'absence episode must not restart ADB repeatedly'
                if case=='replacement-race': assert exe.read_text()=='replacement', 'new executable was overwritten'
                if case=='running-worker':
                    shell=int((p/'shell-pid').read_text())
                    def stopped():
                        try: return Path(f'/proc/{shell}/stat').read_text().split()[2]=='Z'
                        except FileNotFoundError: return True
                    wait_for(stopped,'retired daemon left shell command running')
                    worker_socket.close()
                print('PASS',case,flush=True)
            finally:
                os.killpg(owner.pid,signal.SIGKILL) if owner.poll() is None else None
                owner.wait()
                (p/'block').unlink(missing_ok=True)
                shutil.rmtree(app,ignore_errors=True); shutil.rmtree(user,ignore_errors=True)
                shutil.rmtree(live,ignore_errors=True)
if __name__=='__main__':
    try:
        run()
    except AssertionError as error:
        print('FAIL:', error, flush=True)
        raise SystemExit(1)
