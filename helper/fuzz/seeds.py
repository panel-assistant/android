#!/usr/bin/env python3
"""Generate deterministic command and transport seeds from the production verb manifest.

Reading the manifest here prevents corpus omissions; this is corpus construction, not an assertion
about source spelling or a substitute for caller-level behavioral checks.
"""
import hashlib
from pathlib import Path
import re
import sys

here = Path(__file__).resolve().parent
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
for previous in out.iterdir():
    if previous.is_file():
        previous.unlink()
verbs = re.findall(r'^COMMAND\((\w+),', (here.parent / 'src/commands.def').read_text(), re.M)
h = '1' * 64
boot = hashlib.sha256(b'hapaneld-guard-boot-v1\0' + b'00000000-0000-0000-0000-000000000000').hexdigest()
pkg = 'io.homeassistant.companion.android.minimal'
args = {
    'RGB': '1 2 3', 'BTN': '5', 'SCREEN': 'ON', 'KEYEVENT': 'SLEEP', 'BLSET': '127',
    'LOGCATDUMP': '300', 'RELOAD': 'com.example.app', 'START': 'com.example.app/.Main',
    'SETHOME': 'com.example.app/.Home', 'APPSTATE': 'com.example.app',
    'STOP': 'com.eWeLinkControlPanel', 'DISABLE': 'com.eWeLinkControlPanel',
    'ENABLE': 'com.eWeLinkControlPanel', 'OVERLAY': 'com.example.app deny',
    'GRANT': 'io.panelassistant.android WRITESETTINGS',
    'INSTALL': '/data/user/0/io.panelassistant.android/files/helper-install-staging/update.apk',
    'INSTALLSTREAM': '3', 'INSTALLSTREAMUPGRADE': '3',
    'INSTALLGC': '/data/user/0/io.panelassistant.android/files/helper-install-staging/update.apk',
    'COMPANIONBACKUP': pkg,
    'COMPANIONRESTORE': f'{pkg} 3 0 0',
    'GUARDPREPARE': f'{h} {boot} {h} 8 {h} 14 2 {h} {h} 900000 2 3 {h}',
    'GUARDDEFINE': f'{h} 1 A 3 {h} 568 1 14 14',
    'GUARDSTREAM': f'{h} 2 A 3 {h}', 'GUARDACTION': f'{h} 6 CAPTURE_BASELINE',
    'GUARDHEALTH': f'{h} 9 {boot} B {h} 569 15 OK 2 {h} {h} PRESENT NA',
    'GUARDREFUSAL': f'{h} 12 {boot} A {h} 568 PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE',
    'GUARDEVIDENCE': h, 'GUARDCANCEL': f'{h} 1', 'GUARDRETIRE': f'APP {h} {h} {h}',
    'WATCH': '/dev/input/event0 1', 'GPIOWATCH': '0', 'DENSITY': '240',
    'FONTSCALE': '1.15', 'BOOTCHIME': 'RESTORE 1 1 1 7 7', 'GOV': 'schedutil',
    'GOVREAD': 'CURRENT', 'RELAYREAD': 'RELAY COUNT',
    'GPIOREAD': '0', 'GFXINFO': 'SELF',
    'ARM': h, 'BEAT': h, 'RECOVER': h, 'SHELL': '100 3',
}
noargs = set('COMPANIONSTATUS ZIGBEECONTAIN OFF LEDPROBE BLPOWER BLREAD SCREENCAP LOGCATCAPS LOGCAT COMPANIONCAPS BUILDID GUARDCAPS GUARDSELF GUARDSTATUS INPUTV2 INPUTV3 WATCHRESET SUBSCRIBE GPIOV1 GPIORESET GPIOSUBSCRIBE REBOOT ZIGBEESTATE ZIGBEEHEALTH ZIGBEEROLE WIFISTATUS ADBSTATE PERFDUMP CHT8305 VI530X VERSION PING CDPSTATE ADBRECOVERYENSURE STATUS'.split())
assert set(verbs) == set(args) | noargs, 'Add a reviewed argument seed/no-argument declaration for new verbs'

def put(name, value):
    (out / name).write_bytes(value if isinstance(value, bytes) else value.encode())

variants = {
    'KEYEVENT': ['WAKEUP'], 'SCREEN': ['OFF'], 'DENSITY': ['', 'reset'],
    'FONTSCALE': ['', 'reset'], 'GOVREAD': ['AVAILABLE'], 'REBOOT': ['RECOVERY'],
    'BOOTCHIME': ['SILENCE'], 'OVERLAY': ['com.example.app allow', 'com.example.app default'],
    'GRANT': ['io.panelassistant.android ' + capability for capability in
              ('NOTIFICATIONS', 'MICROPHONE', 'CAMERA', 'OVERLAY', 'BATTERY', 'ACCESSIBILITY')],
    'COMPANIONRESTORE': [f'{pkg} 3 0 0 EXPECT {h} -'],
    'GUARDRETIRE': [f'TERMINAL {h} 1 {h}'],
    'GUARDACTION': [f'{h} 1 ' + action for action in
                    ('WITHHOLD_PREMIGRATE', 'RESTORE_PREMIGRATE', 'FINALIZE')],
}
summary = ['verb\tseed_count\tclasses']
for verb in verbs:
    valid = verb + (' ' + args[verb] if verb in args else '')
    cases = [('syntax', valid + '\n'), ('missing', verb + '\n'),
             ('extra', valid + ' extra\n'), ('nul', valid.encode() + b'\0ignored\n'),
             ('crlf', valid + '\r\n')]
    for value in ('-1', '0', '1', '65535', '65536', '2147483647', '2147483648',
                  '18446744073709551615', '18446744073709551616', ';|`$()&<>'):
        cases.append(('arg-' + str(len(cases)), verb + ' ' + value + '\n'))
    for length in (511, 512, 513, 1024, 8192):
        cases.append((f'length-{length}', (verb + ' ').ljust(length, 'A') + '\n'))
    if verb == 'SHELL':
        cases += [('body', valid + '\nabc'),
                  ('body-verbs', 'SHELL 100 9\nPING\nOFF\nPING\n'),
                  ('body-nul', b'SHELL 100 3\na\x00b'),
                  ('body-short', 'SHELL 100 4\nabc'),
                  ('bad-timeout', 'SHELL 180001 3\nabc'),
                  ('bad-count', 'SHELL 100 65537\nabc'),
                  ('extra-header-token', 'SHELL 100 3 extra\nabc')]
    cases += [(f'variant-{i}', verb + ' ' + argument + '\n')
              for i, argument in enumerate(variants.get(verb, []))]
    for name, value in cases:
        put(verb + '-' + name, value)
    summary.append(f'{verb}\t{len(cases)}\tsyntax,missing,extra,nul,crlf,numeric,metachar,line-boundary')
# Multi-command seeds get beyond isolated argument rejection into private Guard state parsers.
prepare = 'GUARDPREPARE ' + args['GUARDPREPARE'] + '\n'
put('guard-prepare-define', prepare + 'GUARDDEFINE ' + args['GUARDDEFINE'] + '\nGUARDSTATUS\n')
put('guard-prepare-cancel', prepare + 'GUARDCANCEL ' + args['GUARDCANCEL'] + '\nGUARDSTATUS\n')
put('guard-prepare-stream', prepare + 'GUARDDEFINE ' + args['GUARDDEFINE'] + '\nGUARDSTREAM ' + args['GUARDSTREAM'] + '\nabc\n')
put('recovery-admitted-sequence', f'VERSION\nBUILDID\nARM {h}\nPING\n')
put('recovery-header-control', b'STATUS\x01\nPING\n')
put('recovery-header-overflow-body', 'SHELL ' + '1' * 600 + '\nPING\nOFF\n')
put('transport-reply-flood', 'PING\n' * 1500)
put('transport-overlong-recovery', 'X' * 8192 + '\nPING\n')
put('transport-binary', b'\xff\xfe\x00\x01\r\nRGB 1 2 3\x00OFF\n')
(out.parent / 'corpus-summary.tsv').write_text('\n'.join(summary) + '\n')
# libFuzzer's dictionary keeps structured tokens reachable while mutation optimizes branches.
with (out.parent / 'commands.dict').open('w') as dictionary:
    for token in verbs + ['SELF', 'CURRENT', 'AVAILABLE', 'CAPTURE_BASELINE', 'FINALIZE',
                           'EXPECT', 'TERMINAL', 'RESTORED', 'WRITE_SETTINGS', 'WRITESETTINGS', pkg, h, boot]:
        dictionary.write('"' + token + '"\n')
print(f'Corpus: {len(verbs)} verbs, {sum(1 for _ in out.iterdir())} seeds; {out.parent / "corpus-summary.tsv"}')
