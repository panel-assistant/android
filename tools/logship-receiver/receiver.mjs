#!/usr/bin/env node
// A dependency-free syslog / NDJSON receiver for checking ha-paneld's remote log shipping.
//
// Listens on UDP, TCP and HTTP at the same time and prints what arrives, so "the panel says it is
// sending" can be told apart from "the collector is receiving" — and, more usefully, from "the
// collector is receiving bytes it cannot parse". A real collector drops an unparseable frame in
// silence, which looks identical to nothing being sent at all.
//
// Usage:
//   node receiver.mjs                          # udp+tcp 5514, http 5515, all interfaces
//   node receiver.mjs --udp 514                # privileged ports need root
//   node receiver.mjs --udp 5514 --no-tcp --no-http
//   node receiver.mjs --json                   # one JSON object per record, for scripting
//   node receiver.mjs --quiet                  # counters only, refreshed on arrival
//   node receiver.mjs --self-test              # prove the receiver works before blaming the panel
//
// Point a panel at it with:
//   curl -fsS -X POST http://<panel>:8888/api/v1/config \
//     --data-urlencode log_ship_enabled=true \
//     --data-urlencode log_ship_host=<this-host> \
//     --data-urlencode log_ship_port=5514 \
//     --data-urlencode log_ship_protocol=syslog-udp

import dgram from 'node:dgram'
import net from 'node:net'
import http from 'node:http'
import process from 'node:process'
import { pathToFileURL } from 'node:url'

const FACILITIES = [
  'kern', 'user', 'mail', 'daemon', 'auth', 'syslog', 'lpr', 'news', 'uucp', 'cron', 'authpriv',
  'ftp', 'ntp', 'audit', 'alert', 'clock', 'local0', 'local1', 'local2', 'local3', 'local4',
  'local5', 'local6', 'local7',
]
const SEVERITIES = ['emerg', 'alert', 'crit', 'err', 'warning', 'notice', 'info', 'debug']

// RFC5424 §6: <PRI>VERSION SP TIMESTAMP SP HOSTNAME SP APP-NAME SP PROCID SP MSGID SP SD [SP MSG]
// Parse structured data with a cursor rather than a backtracking expression. Besides accepting an
// escaped closing bracket inside an SD element, this keeps hostile or malformed frames linear-time.

function parseArgs (argv) {
  const opts = {
    udp: 5514, tcp: 5514, http: 5515, bind: '::', json: false, quiet: false, selfTest: false,
  }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    const value = () => {
      const next = argv[++i]
      if (next === undefined) fail(`${arg} needs a value`)
      return next
    }
    switch (arg) {
      case '--udp': opts.udp = port(value(), arg); break
      case '--tcp': opts.tcp = port(value(), arg); break
      case '--http': opts.http = port(value(), arg); break
      case '--bind': opts.bind = value(); break
      case '--no-udp': opts.udp = 0; break
      case '--no-tcp': opts.tcp = 0; break
      case '--no-http': opts.http = 0; break
      case '--json': opts.json = true; break
      case '--quiet': opts.quiet = true; break
      case '--self-test': opts.selfTest = true; break
      case '-h': case '--help': usage(); process.exit(0); break
      default: fail(`unknown argument: ${arg}`)
    }
  }
  return opts
}

function port (raw, flag) {
  const n = Number(raw)
  if (!Number.isInteger(n) || n < 1 || n > 65535) fail(`${flag} must be a port between 1 and 65535`)
  return n
}

function fail (message) {
  process.stderr.write(`receiver.mjs: ${terminalSafe(message)}\n`)
  process.exit(2)
}

function usage () {
  process.stdout.write(`ha-paneld log-shipping receiver

  --udp N | --no-udp     UDP syslog port (default 5514)
  --tcp N | --no-tcp     TCP syslog port (default 5514)
  --http N | --no-http   HTTP NDJSON port (default 5515)
  --bind ADDR            listen address (default :: — all interfaces, v4 and v6)
  --json                 one JSON object per record instead of the human breakdown
  --quiet                counters only
  --self-test            send synthetic frames to our own listeners and exit
`)
}

const counts = { udp: 0, tcp: 0, http: 0, parsed: 0, unparsed: 0, overlong: 0 }

// This listens on every interface by default, so a sender that never terminates a frame — a broken
// client, or a stream that is not syslog at all — must not be able to grow memory without limit.
// Both caps are far above any real record: the UDP transport truncates at 1 KiB, and a batched
// NDJSON POST is bounded by the shipper's own queue.
const MAX_FRAME_BYTES = 256 * 1024
const MAX_BODY_BYTES = 8 * 1024 * 1024

// Render controls visibly rather than allowing an unauthenticated LAN sender to move the cursor,
// clear the screen, forge lines, or alter text direction in the operator's terminal.
const TERMINAL_CONTROL = /[\u0000-\u001f\u007f-\u009f]/g
const TERMINAL_DIRECTIONAL = /[\u202a-\u202e\u2066-\u2069]/g

export function terminalSafe (value) {
  return String(value)
    .replace(TERMINAL_CONTROL, (c) => `\\x${c.charCodeAt(0).toString(16).padStart(2, '0')}`)
    .replace(TERMINAL_DIRECTIONAL, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`)
}

function terminalSafeValue (value) {
  if (typeof value === 'string') return terminalSafe(value)
  if (Array.isArray(value)) return value.map(terminalSafeValue)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [terminalSafe(key), terminalSafeValue(item)]))
  }
  return value
}

function stamp () {
  return new Date().toISOString().replace('T', ' ').slice(0, 23)
}

/** Split a raw datagram or stream chunk into an RFC5424 breakdown, or explain why it is not one. */
export function parseSyslog (raw) {
  if (raw[0] !== '<') return { ok: false, raw }

  const priEnd = raw.indexOf('>', 1)
  if (priEnd < 2 || priEnd > 4) return { ok: false, raw }
  const priText = raw.slice(1, priEnd)
  for (const digit of priText) {
    if (digit < '0' || digit > '9') return { ok: false, raw }
  }
  const pri = Number(priText)
  if (pri > 191) return { ok: false, raw, why: `PRI ${pri} is out of range` }

  let cursor = priEnd + 1
  const versionCode = raw.charCodeAt(cursor)
  if (versionCode < 0x30 || versionCode > 0x39 || raw[cursor + 1] !== ' ') {
    return { ok: false, raw }
  }
  const version = versionCode - 0x30
  cursor += 2

  const fields = []
  for (let field = 0; field < 5; field++) {
    const end = raw.indexOf(' ', cursor)
    if (end <= cursor) return { ok: false, raw }
    const value = raw.slice(cursor, end)
    if (/\s/.test(value)) return { ok: false, raw }
    fields.push(value)
    cursor = end + 1
  }

  const structuredDataStart = cursor
  if (raw[cursor] === '-') {
    cursor++
  } else if (raw[cursor] === '[') {
    while (raw[cursor] === '[') {
      cursor++
      let escaped = false
      let closed = false
      while (cursor < raw.length) {
        const char = raw[cursor++]
        if (char === '\n' || char === '\r') return { ok: false, raw }
        if (escaped) {
          escaped = false
        } else if (char === '\\') {
          escaped = true
        } else if (char === ']') {
          closed = true
          break
        }
      }
      if (!closed) return { ok: false, raw }
    }
  } else {
    return { ok: false, raw }
  }

  const structuredData = raw.slice(structuredDataStart, cursor)
  let message = ''
  if (cursor < raw.length) {
    if (raw[cursor] !== ' ') return { ok: false, raw }
    message = raw.slice(cursor + 1)
  }

  return {
    ok: true,
    facility: FACILITIES[pri >> 3] ?? `facility${pri >> 3}`,
    severity: SEVERITIES[pri & 7],
    pri,
    version,
    timestamp: fields[0],
    hostname: fields[1],
    appName: fields[2],
    procId: fields[3],
    msgId: fields[4],
    structuredData,
    message,
  }
}

function hexDump (buffer, limit = 96) {
  const slice = buffer.subarray(0, limit)
  const hex = slice.toString('hex').replace(/(..)/g, '$1 ').trimEnd()
  const printable = Array.from(slice, (b) => (b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : '.')).join('')
  return `${hex}\n      ${printable}${buffer.length > limit ? ` … (${buffer.length} bytes total)` : ''}`
}

export function report (transport, from, buffer, opts) {
  counts[transport]++
  const raw = buffer.toString('utf8')
  const parsed = parseSyslog(raw)
  parsed.ok ? counts.parsed++ : counts.unparsed++

  if (opts.quiet) {
    process.stdout.write(
      `\rudp=${counts.udp} tcp=${counts.tcp} http=${counts.http} ` +
      `parsed=${counts.parsed} unparsed=${counts.unparsed} overlong=${counts.overlong}   `,
    )
    return
  }
  if (opts.json) {
    process.stdout.write(`${JSON.stringify(terminalSafeValue({ transport, from, bytes: buffer.length, ...parsed }))}\n`)
    return
  }
  if (!parsed.ok) {
    // Loud on purpose: this is the failure a real collector performs silently.
    process.stdout.write(
      `${stamp()} ${terminalSafe(transport.toUpperCase())} ${terminalSafe(from)} PARSE FAIL — not an RFC5424 frame` +
      `${parsed.why ? ` (${terminalSafe(parsed.why)})` : ''}\n      ${hexDump(buffer)}\n`,
    )
    return
  }
  process.stdout.write(
    `${stamp()} ${terminalSafe(transport.toUpperCase())} ${terminalSafe(from)} ${buffer.length}B  ` +
    `${parsed.facility}.${parsed.severity} (pri=${parsed.pri} v${parsed.version})\n` +
    `      ts=${terminalSafe(parsed.timestamp)} host=${terminalSafe(parsed.hostname)} app=${terminalSafe(parsed.appName)} ` +
    `procid=${terminalSafe(parsed.procId)} msgid=${terminalSafe(parsed.msgId)} sd=${terminalSafe(parsed.structuredData)}\n` +
    `      ${terminalSafe(parsed.message)}\n`,
  )
}

export function reportNdjson (from, body, opts) {
  for (const line of body.split('\n')) {
    if (!line.trim()) continue
    counts.http++
    let event
    try {
      event = JSON.parse(line)
      counts.parsed++
    } catch (e) {
      counts.unparsed++
      if (!opts.quiet) {
        process.stdout.write(`${stamp()} HTTP ${terminalSafe(from)} PARSE FAIL — not JSON (${terminalSafe(e.message)})\n      ${terminalSafe(line)}\n`)
      }
      continue
    }
    if (opts.quiet) continue
    if (opts.json) {
      process.stdout.write(`${JSON.stringify(terminalSafeValue({ transport: 'http', from, ...event }))}\n`)
    } else {
      process.stdout.write(
        `${stamp()} HTTP ${terminalSafe(from)} ${line.length}B  ts=${terminalSafe(event.timestamp)} ` +
        `host=${terminalSafe(event.host)} app=${terminalSafe(event.app)}\n      ${terminalSafe(event.message)}\n`,
      )
    }
  }
}

function startUdp (opts) {
  return new Promise((resolve, reject) => {
    // udp6 with the default :: bind accepts v4-mapped traffic too, so one socket covers both.
    const type = net.isIPv4(opts.bind) ? 'udp4' : 'udp6'
    const socket = dgram.createSocket({ type, ipv6Only: false })
    socket.on('error', reject)
    socket.on('message', (msg, rinfo) => report('udp', `${rinfo.address}:${rinfo.port}`, msg, opts))
    socket.bind(opts.udp, opts.bind, () => resolve(socket))
  })
}

function startTcp (opts) {
  return new Promise((resolve, reject) => {
    const server = net.createServer((socket) => {
      const from = `${socket.remoteAddress}:${socket.remotePort}`
      let pending = ''
      socket.on('data', (chunk) => {
        pending += chunk.toString('utf8')
        // RFC6587 non-transparent framing: the newline is the frame delimiter, and a chunk can
        // split one frame or carry several.
        const frames = pending.split('\n')
        pending = frames.pop() ?? ''
        for (const frame of frames) {
          if (frame.length) report('tcp', from, Buffer.from(frame, 'utf8'), opts)
        }
        // Nothing delimited the buffer within the cap, so this is not newline-framed syslog.
        // Drop the connection rather than accumulate: keeping it would only defer the same verdict.
        if (Buffer.byteLength(pending, 'utf8') > MAX_FRAME_BYTES) {
          counts.overlong++
          if (!opts.quiet) {
            process.stdout.write(
              `${stamp()} TCP ${terminalSafe(from)} OVERLONG FRAME — no newline within ${MAX_FRAME_BYTES} bytes; ` +
              `closing. Is the sender using newline framing?\n`,
            )
          }
          pending = ''
          socket.destroy()
        }
      })
      socket.on('error', () => socket.destroy())
    })
    server.on('error', reject)
    server.listen(opts.tcp, opts.bind, () => resolve(server))
  })
}

function startHttp (opts) {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const from = `${req.socket.remoteAddress}:${req.socket.remotePort}`
      const chunks = []
      let size = 0
      let refused = false
      req.on('data', (c) => {
        if (refused) return
        size += c.length
        if (size > MAX_BODY_BYTES) {
          // Answer 413 rather than buffering an unbounded body from an unauthenticated LAN peer.
          refused = true
          counts.overlong++
          if (!opts.quiet) {
            process.stdout.write(
              `${stamp()} HTTP ${terminalSafe(from)} BODY TOO LARGE — over ${MAX_BODY_BYTES} bytes; refused 413\n`,
            )
          }
          res.writeHead(413).end()
          req.destroy()
          return
        }
        chunks.push(c)
      })
      req.on('end', () => {
        if (refused) return
        reportNdjson(from, Buffer.concat(chunks).toString('utf8'), opts)
        res.writeHead(204).end()
      })
      req.on('error', () => { refused = true })
    })
    server.on('error', reject)
    server.listen(opts.http, opts.bind, () => resolve(server))
  })
}

/**
 * Send one known-good frame per enabled transport to our own listeners. If this fails, the problem
 * is here or in the local firewall, and there is no point looking at the panel yet.
 */
async function selfTest (opts) {
  const ts = new Date().toISOString()
  const frame = `<14>1 ${ts} self-test ha-paneld - - - receiver self-test`
  const target = '127.0.0.1'

  if (opts.udp) {
    const socket = dgram.createSocket('udp4')
    await new Promise((done) => socket.send(Buffer.from(frame, 'utf8'), opts.udp, target, () => {
      socket.close()
      done()
    }))
  }
  if (opts.tcp) {
    await new Promise((done) => {
      const socket = net.connect(opts.tcp, target, () => socket.end(`${frame}\n`, done))
      socket.on('error', (e) => { process.stderr.write(`self-test tcp: ${terminalSafe(e.message)}\n`); done() })
    })
  }
  if (opts.http) {
    const body = JSON.stringify({ timestamp: ts, host: 'self-test', app: 'ha-paneld', message: 'receiver self-test' })
    await new Promise((done) => {
      const req = http.request(
        { host: target, port: opts.http, method: 'POST', headers: { 'content-type': 'application/x-ndjson' } },
        (res) => { res.resume(); res.on('end', done) },
      )
      req.on('error', (e) => { process.stderr.write(`self-test http: ${terminalSafe(e.message)}\n`); done() })
      req.end(body)
    })
  }

  // Datagrams are asynchronous; give the listeners a moment before judging the result.
  await new Promise((done) => setTimeout(done, 250))
  const expected = (opts.udp ? 1 : 0) + (opts.tcp ? 1 : 0) + (opts.http ? 1 : 0)
  const ok = counts.parsed === expected && counts.unparsed === 0
  process.stdout.write(
    `\nself-test: ${counts.parsed}/${expected} frames received and parsed` +
    `${counts.unparsed ? `, ${counts.unparsed} unparsed` : ''} — ${ok ? 'PASS' : 'FAIL'}\n`,
  )
  return ok
}

async function main () {
  const opts = parseArgs(process.argv.slice(2))
  const servers = []
  try {
    if (opts.udp) servers.push(await startUdp(opts))
    if (opts.tcp) servers.push(await startTcp(opts))
    if (opts.http) servers.push(await startHttp(opts))
  } catch (e) {
    fail(e.code === 'EACCES'
      ? `${e.message} — ports below 1024 need root; try --udp 5514`
      : terminalSafe(e.message))
  }
  if (!servers.length) fail('every transport was disabled; nothing to listen on')

  const listening = [
    opts.udp && `udp/${opts.udp}`,
    opts.tcp && `tcp/${opts.tcp}`,
    opts.http && `http/${opts.http}`,
  ].filter(Boolean).join('  ')
  process.stderr.write(`listening on ${terminalSafe(opts.bind)}  ${listening}\n`)

  if (opts.selfTest) {
    const ok = await selfTest(opts)
    for (const server of servers) server.close()
    process.exit(ok ? 0 : 1)
  }

  const summarise = () => {
    process.stdout.write(
      `\nudp=${counts.udp} tcp=${counts.tcp} http=${counts.http} ` +
      `parsed=${counts.parsed} unparsed=${counts.unparsed} overlong=${counts.overlong}\n`,
    )
    process.exit(0)
  }
  process.on('SIGINT', summarise)
  process.on('SIGTERM', summarise)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
