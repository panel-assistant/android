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
  (p/'reopening').touch()
  while (p/'block').exists(): time.sleep(.01)
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
                 'ADB_RECOVERY_SOCKET':sock}
        sources = subprocess.check_output(['make','--no-print-directory','-s','print-core'],cwd=ROOT,text=True).split()
        subprocess.run(['cc','-O1','-g','-Wall','-Wextra','-Werror','-Wno-format-truncation',
                        '-Isrc', *[f'-D{k}="{v}"' for k,v in flags.items()],
                        *sources,'src/main.c','src/sysexec.c','-lpthread','-o',str(binary)],cwd=ROOT,check=True)
        def request(command):
            with socket.socket(socket.AF_UNIX) as s:
                s.settimeout(4)
                s.connect('\0'+sock)
                s.sendall((command+'\n').encode())
                return s.recv(256).decode()
        def ready():
            try: return request('STATUS').startswith('READY')
            except OSError: return False
        for case in ('armed', 'never-armed', 'reappeared', 'replaced'):
            app.mkdir(); user.mkdir(); live.mkdir()
            exe=live/'helper'; shutil.copyfile(binary,exe); exe.chmod(0o700)
            (p/'tcp').write_text('0: 00000000:15B3 00000000:0000 0A\n')
            (p/'tcp6').write_text(''); (p/'init.svc.adbd').write_text('running')
            owner=subprocess.Popen([str(exe),'--adb-recovery'],start_new_session=True)
            try:
                wait_for(ready,'daemon startup')
                children=Path(f'/proc/{owner.pid}/task/{owner.pid}/children')
                guardian=int(children.read_text().split()[0])
                if case!='never-armed':
                    assert request('ARM '+'a'*64)=='OK\n'
                    assert request('RECOVER '+'a'*64)=='OK\n'
                    assert (p/'persist.adb.tcp.port').read_text()=='5555'
                (p/'reopening').unlink(missing_ok=True)
                (p/'block').touch()
                shutil.rmtree(app); shutil.rmtree(user)
                wait_for(lambda:(p/'reopening').exists(),'absence reopens network first')
                assert exe.exists() and owner.poll() is None, 'cleanup preceded network proof'
                if case=='reappeared': app.mkdir()
                if case=='replaced':
                    replacement=live/'replacement'
                    shutil.copyfile(binary,replacement)
                    replacement.replace(exe)
                (p/'block').unlink()
                if case in ('reappeared','replaced'):
                    time.sleep(.5)
                    assert ready() and exe.exists() and live.exists(), 'app return must cancel retirement' if case=='reappeared' else 'replacement executable must survive'
                else:
                    wait_for(lambda:owner.poll() is not None,'owner did not retire')
                    assert owner.returncode==0
                    assert not Path(f'/proc/{guardian}').exists(), 'guardian survived'
                    assert not live.exists(), 'root executable directory survived'
                    assert (p/'init.svc.adbd').read_text()=='running'
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
