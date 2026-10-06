// @vitest-environment happy-dom
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  InvalidDefinition,
  isResubmission,
  parseDefinition,
  type FormDefinition,
} from './definition';
import { completion, initialInputs, type Inputs } from './values';

const PACK_FORMS = resolve(__dirname, '../../../../packs/reference/frontend/forms');

function raw(id: string): Record<string, unknown> {
  return JSON.parse(readFileSync(resolve(PACK_FORMS, `${id}.json`), 'utf-8'));
}

function ownerVehicle(): FormDefinition {
  return parseDefinition(raw('owner-vehicle'), 'owner-vehicle');
}

const VIN = 'WP0AB2A91KS123456';

function valid(): Inputs {
  return {
    firstName: 'Bart',
    lastName: 'Simpson',
    age: '30',
    applicantEmail: 'bart@example.com',
    pendingIdDocument: {
      pendingKey: 'pending/bart/1/id.pdf',
      filename: 'id.pdf',
      contentType: 'application/pdf',
      size: 10,
    },
    objectId: VIN,
    additionalOwners: [],
  };
}

function submit(inputs: Inputs) {
  const d = ownerVehicle();
  return completion(d, d.actions[0], inputs);
}

describe('owner-vehicle completes like the former TSX form', () => {
  it('sends every variable with its type', () => {
    expect(
      submit({
        ...valid(),
        additionalOwners: [
          { name: ' Marge ', email: 'marge@example.com' },
          { name: '', email: '' },
        ],
      }),
    ).toEqual({
      variables: {
        firstName: { value: 'Bart', type: 'String' },
        lastName: { value: 'Simpson', type: 'String' },
        age: { value: 30, type: 'Integer' },
        objectId: { value: VIN, type: 'String' },
        applicantEmail: { value: 'bart@example.com', type: 'String' },
        sendBackReason: { value: '', type: 'String' },
        additionalOwners: { value: '[{"name":"Marge","email":"marge@example.com"}]', type: 'Json' },
        pendingIdDocument: {
          value:
            '{"pendingKey":"pending/bart/1/id.pdf","filename":"id.pdf","contentType":"application/pdf"}',
          type: 'Json',
        },
      },
    });
  });

  it('keeps an earlier upload by sending null', () => {
    const d = ownerVehicle();
    const inputs = initialInputs(d, { idDocumentAttachmentId: 'att-1' }, (k) => `T:${k}`);
    expect(inputs.pendingIdDocument).toMatchObject({
      attachmentId: 'att-1',
      filename: 'T:fields.idDocument.existingFilename',
    });
    const result = submit({ ...valid(), pendingIdDocument: inputs.pendingIdDocument });
    expect(result).toMatchObject({
      variables: { pendingIdDocument: { value: null, type: 'Json' } },
    });
  });

  it.each<[string, Partial<Inputs>, string, Record<string, string>?]>([
    ['a missing name', { firstName: ' ' }, 'errors.namesRequired'],
    ['age 0', { age: '0' }, 'errors.ageRange'],
    ['a fractional age', { age: '30.5' }, 'errors.ageRange'],
    ['no age', { age: '' }, 'errors.ageRange'],
    ['an invalid own email', { applicantEmail: 'bart' }, 'errors.invalidEmail'],
    ['no ID document', { pendingIdDocument: null }, 'errors.idDocumentRequired'],
    ['no vehicle', { objectId: '' }, 'errors.vehicleRequired'],
    [
      'a co-owner without a name',
      { additionalOwners: [{ name: '', email: 'm@example.com' }] },
      'errors.coOwnerNameRequired',
    ],
    [
      'a co-owner with a bad email',
      { additionalOwners: [{ name: 'Marge', email: 'marge' }] },
      'errors.coOwnerEmailInvalid',
      { name: 'Marge' },
    ],
    [
      'a repeated co-owner email',
      {
        additionalOwners: [
          { name: 'A', email: 'm@example.com' },
          { name: 'B', email: 'M@example.com' },
        ],
      },
      'errors.duplicateCoOwnerEmail',
      { email: 'm@example.com' },
    ],
    [
      'the own email among co-owners',
      { additionalOwners: [{ name: 'Me', email: 'BART@example.com' }] },
      'errors.applicantEmailInCoOwners',
    ],
    [
      'co-owners without an own email',
      { applicantEmail: '', additionalOwners: [{ name: 'Marge', email: 'm@example.com' }] },
      'errors.applicantEmailRequiredWithCoOwners',
    ],
  ])('refuses %s', (_, change, key, values) => {
    expect(submit({ ...valid(), ...change } as Inputs)).toEqual({
      error: values ? { key, values } : { key },
    });
  });

  it('accepts an empty own email when there are no co-owners', () => {
    expect(submit({ ...valid(), applicantEmail: '' })).toHaveProperty('variables');
  });

  it('marks a resubmission only while the task is open', () => {
    const d = ownerVehicle();
    expect(isResubmission(d, { sendBackReason: 'fix it' }, false)).toBe(true);
    expect(isResubmission(d, { sendBackReason: 'fix it' }, true)).toBe(false);
    expect(isResubmission(d, { sendBackReason: '' }, false)).toBe(false);
  });
});

describe('new elements stay inside format v1', () => {
  function withField(change: (fields: Record<string, unknown>[]) => void) {
    const d = raw('owner-vehicle');
    change(d.fields as Record<string, unknown>[]);
    return () => parseDefinition(d, 'owner-vehicle');
  }

  it.each<[string, (fields: Record<string, unknown>[]) => void]>([
    [
      'an upload category the platform does not know',
      (f) => ((f[4].file as Record<string, unknown>).category = 'anything'),
    ],
    [
      'an accept list beyond PDF/JPEG/PNG',
      (f) => ((f[4].file as Record<string, unknown>).accept = 'text/html'),
    ],
    [
      'an upload limit above 25 MB',
      (f) => ((f[4].file as Record<string, unknown>).maxBytes = 100 * 1024 * 1024),
    ],
    [
      'a registry name that is no id',
      (f) => ((f[5].source as Record<string, unknown>).registry = '../internal'),
    ],
    ['identity on a number field', (f) => (f[2].identity = true)],
    ['a range message without min and max', (f) => delete f[2].min],
    ['source on a text field', (f) => (f[0].source = f[5].source)],
    [
      'requiredWhenListed naming no contacts field',
      (f) => ((f[3].requiredWhenListed as Record<string, unknown>).field = 'age'),
    ],
  ])('refuses %s', (_, change) => {
    expect(withField(change)).toThrow(InvalidDefinition);
  });

  it('refuses a contacts field completed as a String', () => {
    const d = raw('owner-vehicle');
    const complete = (d.actions as { complete: Record<string, { type: string }> }[])[0].complete;
    complete.additionalOwners.type = 'String';
    expect(() => parseDefinition(d, 'owner-vehicle')).toThrow(InvalidDefinition);
  });

  it('refuses a banner without resubmittedWhen', () => {
    const d = raw('owner-vehicle');
    delete d.resubmittedWhen;
    expect(() => parseDefinition(d, 'owner-vehicle')).toThrow(InvalidDefinition);
  });
});
