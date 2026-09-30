// Minimal GELF UDP logger, CommonJS twin of mcp/src/logging/gelf.ts.
//
// Duplicated rather than shared because these two sidecars have no build step
// and no common package — a shared module would mean introducing a workspace
// for 60 lines. The field contract is what must stay in sync, and it is written
// down once in docs/logging.md.
//
// No user_id here: the renderer is called by the bus on behalf of a BPMN service
// task, so there is no Keycloak user behind a request.

const { createSocket } = require('node:dgram');
const { hostname } = require('node:os');

const GRAYLOG_HOST = process.env.GRAYLOG_HOST || '';
const GRAYLOG_PORT = Number(process.env.GRAYLOG_GELF_UDP_PORT || 12201);
const SERVICE_NAME = process.env.LOG_SERVICE_NAME || 'pdf-renderer';

// GELF levels are syslog severities.
const SYSLOG_LEVEL = { debug: 7, info: 6, warn: 4, error: 3 };
// Keep each record inside one datagram — GELF's UDP chunking protocol is not
// implemented here, so an oversized message would be dropped by Graylog.
const MAX_DATAGRAM_BYTES = 7000;

// Unconnected socket: a connected one turns an ICMP "port unreachable" from a
// restarting Graylog into an error event on the process.
const socket = GRAYLOG_HOST ? createSocket('udp4') : null;
if (socket) {
  socket.unref();
  socket.on('error', (err) => console.error('[gelf] socket error:', err.message));
}

function emit(level, message, extra) {
  const line = `[${level.toUpperCase()}] ${message}${extra ? ` ${JSON.stringify(extra)}` : ''}`;
  if (level === 'error') console.error(line);
  else if (level === 'warn') console.warn(line);
  else console.log(line);

  if (!socket) return;

  const payload = {
    version: '1.1',
    host: hostname(),
    short_message: String(message).slice(0, 1000),
    // Seconds since the epoch with a millisecond fraction, not milliseconds.
    timestamp: Date.now() / 1000,
    level: SYSLOG_LEVEL[level],
    _service: SERVICE_NAME,
    _level_name: level.toUpperCase(),
  };
  for (const [key, value] of Object.entries(extra || {})) {
    if (value === undefined) continue;
    // Graylog only keeps custom fields that arrive underscore-prefixed.
    payload[`_${key}`] = value;
  }

  let buffer = Buffer.from(JSON.stringify(payload), 'utf8');
  if (buffer.byteLength > MAX_DATAGRAM_BYTES) {
    payload.short_message = payload.short_message.slice(0, 500);
    payload._truncated = true;
    buffer = Buffer.from(JSON.stringify(payload), 'utf8');
  }
  socket.send(buffer, GRAYLOG_PORT, GRAYLOG_HOST, (err) => {
    if (err) console.error('[gelf] send failed:', err.message);
  });
}

module.exports = {
  log: {
    info: (message, extra) => emit('info', message, extra),
    warn: (message, extra) => emit('warn', message, extra),
    error: (message, extra) => emit('error', message, extra),
    target: socket ? `${GRAYLOG_HOST}:${GRAYLOG_PORT}` : '(console only)',
  },
};
