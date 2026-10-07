# Log-shipping receiver

A dependency-free Node script that pretends to be your log collector, so you can answer one question without touching your real logging stack: **is this panel actually sending anything, and is what it sends valid?**

It listens on UDP, TCP and HTTP at the same time, prints every record it receives with a full RFC5424 breakdown, and says so loudly when a frame does not parse. That last part is the useful bit — a real collector discards an unparseable frame in silence, which looks exactly like nothing arriving at all.

Nothing is installed and nothing is written to disk. You need Node 18 or newer.

## Prove the receiver works first

```sh
node receiver.mjs --self-test
```

This sends one known-good frame to each of its own listeners and reports `PASS` or `FAIL`. If this fails, the problem is local — a port already in use, or a firewall — and there is no point looking at the panel yet.

## Receive from a panel

Start it, leaving it running:

```sh
node receiver.mjs --udp 5514 --tcp 5514 --http 5515
```

Then point the panel at the machine you just started it on. Either set **Sink host**, **Sink port** and **Protocol** on the panel's Configure tab at `http://<panel>:8888`, or from a shell:

```sh
curl -fsS -X POST "http://<panel>:8888/api/v1/config" \
  --data-urlencode log_ship_enabled=true \
  --data-urlencode log_ship_host=<your-machine> \
  --data-urlencode log_ship_port=5514 \
  --data-urlencode log_ship_protocol=syslog-udp
```

Records should appear within a few seconds. The panel's own view of the same thing is the **Log shipping** row on `http://<panel>:8888/` and in `/diag`.

Ports below 1024 need root, which is why the defaults are 5514/5515. To listen on the real syslog port, run `sudo node receiver.mjs --udp 514`.

## Reading the output

```
2026-07-26 21:14:03.118 UDP 192.0.2.23:41022 214B  user.warning (pri=12 v1)
      ts=2026-07-26T21:14:03.109Z host=panel app=ha-paneld procid=- msgid=- sd=-
      07-26 21:14:03.108  2913  2913 W ha-paneld/mqtt: reconnecting
```

`pri` decodes to a facility and severity — ha-paneld always uses the `user` facility and maps the logcat level onto the severity, so a `W` line arrives as `user.warning`. `host` is the panel's ID. The message keeps the original logcat prefix (timestamp, PID, TID, level, tag), which is why the timestamp appears twice.

A frame that does not parse is reported as `PARSE FAIL` with a hex dump, so you can see exactly what turned up.

## Options

| Flag | Meaning |
| --- | --- |
| `--udp N`, `--no-udp` | UDP syslog port (default 5514) |
| `--tcp N`, `--no-tcp` | TCP syslog port (default 5514) |
| `--http N`, `--no-http` | HTTP NDJSON port (default 5515) |
| `--bind ADDR` | listen address (default `::`, meaning every interface, IPv4 and IPv6) |
| `--json` | one JSON object per record, for piping into something else |
| `--quiet` | counters only |
| `--self-test` | send synthetic frames to our own listeners, then exit |

`Ctrl-C` prints a final count of what arrived and how much of it parsed.

## If nothing arrives

Check these in order, because each one rules out everything below it.

1. `--self-test` passes, so the receiver and the local firewall are fine.
2. The panel can reach this machine at all: `curl http://<panel>:8888/diag` works in the other direction, but that proves nothing about the reverse path. Container and VM networks in particular are often reachable outbound and not inbound.
3. The protocol matches what you are listening on. UDP and TCP are different listeners even on the same port number, and a collector configured for one refuses or ignores the other.
4. The panel's status row says `sending` or `connected` rather than `disconnected (…)`. The reason in brackets names the fault.

UDP is unacknowledged by design: a panel reporting `sending` has genuinely handed the datagrams to the network, and it cannot know whether anything received them. That is what this script is for.
