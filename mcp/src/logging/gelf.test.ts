// Guards the wire contract of the GELF payload. The fields asserted here are
// what Graylog searches and dashboards are built on, and getting one of them
// wrong fails silently — Graylog accepts the datagram and the field is simply
// missing, so only a test catches it.

import { describe, expect, it } from 'vitest';
import { buildGelfMessage } from './gelf.js';

describe('buildGelfMessage', () => {
  it('sets the GELF envelope Graylog requires', () => {
    const message = buildGelfMessage('info', 'started');

    expect(message.version).toBe('1.1');
    expect(message.short_message).toBe('started');
    expect(typeof message.host).toBe('string');
    // Seconds, not milliseconds: a ms value lands in the year 57000 and Graylog
    // silently files the message under a nonsense timestamp.
    expect(message.timestamp).toBeLessThan(Date.now());
  });

  it('maps levels to syslog severities and a readable name', () => {
    expect(buildGelfMessage('debug', 'x').level).toBe(7);
    expect(buildGelfMessage('info', 'x').level).toBe(6);
    expect(buildGelfMessage('warn', 'x').level).toBe(4);
    expect(buildGelfMessage('error', 'x').level).toBe(3);
    expect(buildGelfMessage('error', 'x')._level_name).toBe('ERROR');
  });

  it('always carries the service name', () => {
    expect(buildGelfMessage('info', 'x')._service).toBe('mcp');
  });

  it('carries user_id only when a user is known', () => {
    expect(buildGelfMessage('info', 'x', {}, 'bart')._user_id).toBe('bart');
    expect(buildGelfMessage('info', 'x')._user_id).toBeUndefined();
  });

  it('underscore-prefixes caller fields and drops undefined ones', () => {
    const message = buildGelfMessage('info', 'x', { tool: 'start_process', missing: undefined });

    expect(message._tool).toBe('start_process');
    expect('_missing' in message).toBe(false);
  });

  it('truncates an over-long short_message', () => {
    const message = buildGelfMessage('info', 'a'.repeat(2000));

    expect(message.short_message.length).toBe(1000);
    expect(message.short_message.endsWith('…')).toBe(true);
  });
});
