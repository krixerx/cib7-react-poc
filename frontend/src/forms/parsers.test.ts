// @vitest-environment happy-dom
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseDefinition, type FormDefinition } from './schema/definition';
import { initialInputs, withSuffix } from './schema/values';

/**
 * The defensive parsing the former TSX forms did on process variables, now done
 * by the schema renderer for every form: history round-trips hand back Spin
 * Json either as a real array or as a JSON string, casing may change, and
 * mistyped entries must not leak `undefined` into inputs.
 */
function pack(id: string): FormDefinition {
  return parseDefinition(
    JSON.parse(
      readFileSync(
        resolve(__dirname, `../../../packs/reference/frontend/forms/${id}.json`),
        'utf-8',
      ),
    ),
    id,
  );
}

const businessDetails = pack('business-details');
const ownerVehicle = pack('owner-vehicle');
const start = (definition: FormDefinition, variable: string, value: unknown) =>
  initialInputs(definition, { [variable]: value }, (k) => k)[variable];

describe('residency (radio)', () => {
  const residency = (value: unknown) => start(businessDetails, 'applicantResidency', value);

  it('passes through the three valid values', () => {
    expect(residency('citizen')).toBe('citizen');
    expect(residency('e-resident')).toBe('e-resident');
    expect(residency('foreign')).toBe('foreign');
  });

  it('is case-insensitive (history round-trips may change casing)', () => {
    expect(residency('Citizen')).toBe('citizen');
    expect(residency('E-RESIDENT')).toBe('e-resident');
  });

  it('falls back to citizen for unknown or non-string input', () => {
    for (const value of ['martian', 42, null, undefined, {}]) {
      expect(residency(value)).toBe('citizen');
    }
  });
});

describe('board members (rows)', () => {
  const member = { firstName: 'Mari', lastName: 'Maasikas', personalCode: '48001010000' };
  const empty = { firstName: '', lastName: '', personalCode: '' };
  const members = (value: unknown) => start(businessDetails, 'boardMembers', value);

  it('accepts a real array and a JSON string', () => {
    expect(members([member])).toEqual([member]);
    expect(members(JSON.stringify([member]))).toEqual([member]);
  });

  it('blanks missing or mistyped columns instead of leaking undefined into inputs', () => {
    expect(members([{ firstName: 'Mari', personalCode: 48001010000 }])).toEqual([
      { firstName: 'Mari', lastName: '', personalCode: '' },
    ]);
  });

  it('starts with one empty row for malformed, non-array or blank values', () => {
    for (const value of ['{not json', '{"firstName":"Mari"}', '   ', undefined]) {
      expect(members(value)).toEqual([empty]);
    }
  });
});

// Co-founders and co-owners are the same contact rows over two processes.
describe.each([
  ['co-founders', businessDetails, 'additionalFounders'],
  ['co-owners', ownerVehicle, 'additionalOwners'],
] as const)('%s (contacts)', (_name, definition, variable) => {
  const parse = (value: unknown) => start(definition, variable, value);

  it('returns [] for null, undefined, and empty string', () => {
    expect(parse(null)).toEqual([]);
    expect(parse(undefined)).toEqual([]);
    expect(parse('')).toEqual([]);
  });

  it('accepts a real array and a JSON string', () => {
    const karl = [{ name: 'Karl', email: 'karl@example.com' }];
    expect(parse(karl)).toEqual(karl);
    expect(parse(JSON.stringify(karl))).toEqual(karl);
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

describe('company name suffix', () => {
  const company = (value: string) => withSuffix(value.trim(), 'OÜ');

  it('appends OÜ when the legal form is missing', () => {
    expect(company('Acme')).toBe('Acme OÜ');
    expect(company('  Acme  ')).toBe('Acme OÜ');
  });

  it('keeps an empty name empty', () => {
    expect(company('')).toBe('');
    expect(company('   ')).toBe('');
  });

  /**
   * Regression: a \bOÜ\b check never matched (Ü is outside \w, so the trailing
   * \b fails at a space or end-of-string) and every send-back resubmission
   * appended another " OÜ" to the company name.
   */
  it('does not double the suffix on resubmission round-trips', () => {
    expect(company('Näidis OÜ')).toBe('Näidis OÜ');
    expect(company(company('Näidis'))).toBe('Näidis OÜ');
  });

  it('recognises the legal form case-insensitively and mid-name', () => {
    expect(company('näidis oü')).toBe('näidis oü');
    expect(company('OÜ Vanamoodne')).toBe('OÜ Vanamoodne');
  });

  it('does not treat a letter-run containing oü as the legal form', () => {
    expect(company('Söögikoüld')).toBe('Söögikoüld OÜ');
  });
});
