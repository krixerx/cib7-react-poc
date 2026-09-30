// Structured logging for the MCP sidecar: one GELF datagram per log call, sent
// straight to Graylog's GELF UDP input, plus the same line on the console so
// `docker compose logs -f mcp` keeps working.
//
// Hand-rolled rather than pulled from npm because the whole protocol we need is
// "JSON object in a UDP datagram" — a dependency would be more code to audit
// than the 60 lines below, and the sidecar's dependency list is deliberately
// short (it forwards user Bearers, so every package in it is in the trust path).
//
// What it does NOT implement is GELF's UDP chunking protocol, so an oversized
// message would be dropped by Graylog instead of reassembled. Messages are
// therefore truncated to fit one datagram; see MAX_DATAGRAM_BYTES.
//
// Field contract, identical across all five services in this stack (docs/logging.md):
//   service     static, always 'mcp' here
//   level       GELF numeric syslog severity
//   level_name  human-readable level, e.g. INFO
//   user_id     Keycloak username of the caller, when a request is in flight

import { createSocket } from 'node:dgram';
import { hostname } from 'node:os';

const GRAYLOG_HOST = process.env.GRAYLOG_HOST ?? '';
const GRAYLOG_PORT = Number(process.env.GRAYLOG_GELF_UDP_PORT ?? 12201);
const SERVICE_NAME = process.env.LOG_SERVICE_NAME ?? 'mcp';

// 8 KiB is the practical ceiling for a single UDP datagram on a docker bridge
// network; leave headroom for the IP/UDP headers and the JSON envelope itself.
const MAX_DATAGRAM_BYTES = 7000;

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

/** GELF levels are syslog severities, not log4j-style ordinals. */
const SYSLOG_LEVEL: Record<LogLevel, number> = {
  debug: 7,
  info: 6,
  warn: 4,
  error: 3,
};

export interface GelfMessage {
  version: '1.1';
  host: string;
  short_message: string;
  timestamp: number;
  level: number;
  _service: string;
  _level_name: string;
  [additionalField: string]: string | number | boolean | null | undefined;
}

/**
 * Supplies the current request's user id. Set from server.ts, which is the only
 * place that knows about the per-request AsyncLocalStorage — keeping the
 * dependency pointing this way stops the logger and the server from importing
 * each other.
 */
let resolveUserId: () => string | undefined = () => undefined;

export function setUserIdResolver(resolver: () => string | undefined): void {
  resolveUserId = resolver;
}

/** Exported for the unit test: builds the wire payload without sending it. */
export function buildGelfMessage(
  level: LogLevel,
  message: string,
  extra: Record<string, string | number | boolean | null | undefined> = {},
  userId?: string,
): GelfMessage {
  const payload: GelfMessage = {
    version: '1.1',
    host: hostname(),
    short_message: truncate(message, 1000),
    // GELF timestamps are seconds since the epoch, with millisecond precision
    // expressed as a fraction — not milliseconds.
    timestamp: Date.now() / 1000,
    level: SYSLOG_LEVEL[level],
    _service: SERVICE_NAME,
    _level_name: level.toUpperCase(),
  };
  if (userId) {
    payload._user_id = userId;
  }
  for (const [key, value] of Object.entries(extra)) {
    if (value === undefined) continue;
    // Graylog rejects a redefined `id` field outright and ignores unprefixed
    // custom keys, so every caller-supplied field gets the underscore.
    payload[`_${key}`] = value;
  }
  return payload;
}

function truncate(value: string, maxLength: number): string {
  return value.length <= maxLength ? value : `${value.slice(0, maxLength - 1)}…`;
}

// One shared unconnected socket. Unconnected on purpose: a connected UDP socket
// surfaces ICMP "port unreachable" as an error event, which would turn a
// Graylog restart into a crashed sidecar.
const socket = GRAYLOG_HOST ? createSocket('udp4') : undefined;
// Without this the process would refuse to exit while the socket is open.
socket?.unref();
socket?.on('error', (err) => {
  console.error('[gelf] socket error, log shipping degraded:', err.message);
});

function send(payload: GelfMessage): void {
  if (!socket) return;
  let buffer = Buffer.from(JSON.stringify(payload), 'utf8');
  if (buffer.byteLength > MAX_DATAGRAM_BYTES) {
    // Drop the caller's extra fields first, then clamp the message: better a
    // short record in Graylog than a datagram Graylog throws away.
    const trimmed: GelfMessage = {
      ...payload,
      short_message: truncate(payload.short_message, 500),
      _truncated: true,
    };
    buffer = Buffer.from(JSON.stringify(trimmed), 'utf8');
  }
  socket.send(buffer, GRAYLOG_PORT, GRAYLOG_HOST, (err) => {
    if (err) console.error('[gelf] send failed:', err.message);
  });
}

function emit(
  level: LogLevel,
  message: string,
  extra?: Record<string, string | number | boolean | null | undefined>,
): void {
  const userId = resolveUserId();
  const consoleLine = `[${level.toUpperCase()}] ${message}${
    userId ? ` user_id=${userId}` : ''
  }${extra ? ` ${JSON.stringify(extra)}` : ''}`;
  if (level === 'error') console.error(consoleLine);
  else if (level === 'warn') console.warn(consoleLine);
  else console.log(consoleLine);

  send(buildGelfMessage(level, message, extra ?? {}, userId));
}

export const log = {
  debug: (message: string, extra?: Record<string, string | number | boolean | null | undefined>) =>
    emit('debug', message, extra),
  info: (message: string, extra?: Record<string, string | number | boolean | null | undefined>) =>
    emit('info', message, extra),
  warn: (message: string, extra?: Record<string, string | number | boolean | null | undefined>) =>
    emit('warn', message, extra),
  error: (message: string, extra?: Record<string, string | number | boolean | null | undefined>) =>
    emit('error', message, extra),
  /** True when a Graylog target is configured; used for the startup banner. */
  get shipping(): boolean {
    return socket !== undefined;
  },
  get target(): string {
    return socket ? `${GRAYLOG_HOST}:${GRAYLOG_PORT}` : '(console only)';
  },
};
