// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest';

// The form modules import the Keycloak singleton (via FileUpload →
// documentsApi and vehicleRegistryApi) whose initialiser needs real
// browser plumbing — stub it; the parsers under test are pure.
vi.mock('../auth/keycloak', () => ({
  keycloak: { authenticated: false },
  ensureFreshToken: vi.fn(),
}));

import {
  ensureCompanySuffix,
  normaliseResidency,
  parseAdditionalFounders,
  parseBoardMembers,
} from './business-details/BusinessDetailsForm';
import { parseAdditionalOwners } from './owner-vehicle/OwnerVehicleForm';

describe('normaliseResidency', () => {
  it('passes through the three valid values', () => {
    expect(normaliseResidency('citizen')).toBe('citizen');
    expect(normaliseResidency('e-resident')).toBe('e-resident');
    expect(normaliseResidency('foreign')).toBe('foreign');
  });

  it('is case-insensitive (history round-trips may change casing)', () => {
    expect(normaliseResidency('Citizen')).toBe('citizen');
    expect(normaliseResidency('E-RESIDENT')).toBe('e-resident');
  });

  it('falls back to citizen for unknown or non-string input', () => {
    expect(normaliseResidency('martian')).toBe('citizen');
    expect(normaliseResidency(42)).toBe('citizen');
    expect(normaliseResidency(null)).toBe('citizen');
    expect(normaliseResidency(undefined)).toBe('citizen');
    expect(normaliseResidency({})).toBe('citizen');
  });
});

describe('parseBoardMembers', () => {
  const member = { firstName: 'Mari', lastName: 'Maasikas', personalCode: '48001010000' };

  it('accepts a real array (Spin Object storage path)', () => {
    expect(parseBoardMembers([member])).toEqual([member]);
  });

  it('accepts a JSON string (Spin Json storage path)', () => {
    expect(parseBoardMembers(JSON.stringify([member]))).toEqual([member]);
  });

  it('blanks missing or mistyped fields instead of leaking undefined into inputs', () => {
    expect(parseBoardMembers([{ firstName: 'Mari', personalCode: 48001010000 }])).toEqual([
      { firstName: 'Mari', lastName: '', personalCode: '' },
    ]);
  });

  it('returns [] for malformed JSON, non-array JSON, and blank strings', () => {
    expect(parseBoardMembers('{not json')).toEqual([]);
    expect(parseBoardMembers('{"firstName":"Mari"}')).toEqual([]);
    expect(parseBoardMembers('   ')).toEqual([]);
    expect(parseBoardMembers(undefined)).toEqual([]);
  });
});

// parseAdditionalFounders and parseAdditionalOwners are the same defensive
// shape over two processes — exercise them with a shared table.
describe.each([
  ['parseAdditionalFounders', parseAdditionalFounders],
  ['parseAdditionalOwners', parseAdditionalOwners],
] as const)('%s', (_name, parse) => {
  it('returns [] for null, undefined, and empty string', () => {
    expect(parse(null)).toEqual([]);
    expect(parse(undefined)).toEqual([]);
    expect(parse('')).toEqual([]);
  });

  it('accepts a real array (Spin Object storage path)', () => {
    expect(parse([{ name: 'Karl', email: 'karl@example.com' }])).toEqual([
      { name: 'Karl', email: 'karl@example.com' },
    ]);
  });

  it('accepts a JSON string (Spin Json storage path)', () => {
    expect(parse('[{"name":"Karl","email":"karl@example.com"}]')).toEqual([
      { name: 'Karl', email: 'karl@example.com' },
    ]);
  });

  it('returns [] for malformed JSON and non-array payloads', () => {
    expect(parse('{oops')).toEqual([]);
    expect(parse('{"name":"Karl"}')).toEqual([]);
    expect(parse(42)).toEqual([]);
  });

  it('drops non-object entries and blanks mistyped fields', () => {
    expect(parse([null, 'karl', 7, { name: 'Karl', email: 99 }, { email: 'x@y.ee' }])).toEqual([
      { name: 'Karl', email: '' },
      { name: '', email: 'x@y.ee' },
    ]);
  });
});

describe('ensureCompanySuffix', () => {
  it('appends OÜ when the legal form is missing', () => {
    expect(ensureCompanySuffix('Acme')).toBe('Acme OÜ');
    expect(ensureCompanySuffix('  Acme  ')).toBe('Acme OÜ');
  });

  it('keeps an empty name empty', () => {
    expect(ensureCompanySuffix('')).toBe('');
    expect(ensureCompanySuffix('   ')).toBe('');
  });

  /**
   * Regression: the old \bOÜ\b check never matched (Ü is outside \w, so the
   * trailing \b fails at a space or end-of-string) and every send-back
   * resubmission appended another " OÜ" to the company name.
   */
  it('does not double the suffix on resubmission round-trips', () => {
    expect(ensureCompanySuffix('Näidis OÜ')).toBe('Näidis OÜ');
    expect(ensureCompanySuffix(ensureCompanySuffix('Näidis'))).toBe('Näidis OÜ');
  });

  it('recognises the legal form case-insensitively and mid-name', () => {
    expect(ensureCompanySuffix('näidis oü')).toBe('näidis oü');
    expect(ensureCompanySuffix('OÜ Vanamoodne')).toBe('OÜ Vanamoodne');
  });

  it('does not treat a letter-run containing oü as the legal form', () => {
    expect(ensureCompanySuffix('Söögikoüld')).toBe('Söögikoüld OÜ');
  });
});
