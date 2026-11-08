#!/usr/bin/env python3
"""Corpus grammars for production relay arguments and recovery owner frames."""
from pathlib import Path
import sys

target, root = sys.argv[1], Path(sys.argv[2])
out = root / "seeds"
out.mkdir(parents=True, exist_ok=True)
if target == "cdprelay":
    seeds = [p + b"\0" + name for p in (b"1023", b"1024", b"65535", b"65536", b"-1", b"99999999999999999999")
             for name in (b"webview_devtools_remote_1", b"webview_devtools_remote_0", b"other", b"webview_devtools_remote_1;id")]
    tokens = ["1024", "65535", "webview_devtools_remote_", "webview_devtools_remote_1"]
elif target == "recovery":
    nonce = b"a" * 64
    seeds = [b"STATUS\n", b"SHELL 100 3\nabc", b"SHELL 100 3\na\0b", b"SHELL 100 4\nabc",
             b"SHELL 180001 3\nabc", b"SHELL 100 65537\n", b"SHELL 100 3 extra\nabc",
             b"SHELL 100 65536\n" + b"x" * 65536, b"STATUS\r\n", b"STATUS\0\n", b"X" * 160 + b"\n"]
    seeds += [verb + b" " + nonce + b"\n" for verb in (b"ARM", b"BEAT", b"RECOVER")]
    tokens = ["SHELL", "ARM", "BEAT", "RECOVER", "STATUS", "65536", "180000", "a" * 64]
else:
    raise SystemExit("Unknown target: " + target)
for index, data in enumerate(seeds):
    (out / str(index)).write_bytes(data)
(root / "commands.dict").write_text("".join('"' + token + '"\n' for token in tokens))
