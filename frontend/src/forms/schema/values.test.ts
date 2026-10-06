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
import { completion, initialInputs, type FieldError, type Inputs } from './values';

const PACK_FORMS = resolve(__dirname, '../../../../packs/reference/frontend/forms');

function raw(id: string): Record<string, unknown> {
  return JSON.parse(readFileSync(resolve(PACK_FORMS, `${id}.json`), 'utf-8'));
}

function ownerVehicle(): FormDefinition {
  return parseDefinition(raw('owner-vehicle'), 'owner-vehicle');
}

const VIN = 'WP0AB2A91KS123456';

/** The messages of a refused submit, without the field names. */
function messages(result: ReturnType<typeof completion>) {
  return 'errors' in result
    ? result.errors.map((e) => (e.values ? { key: e.key, values: e.values } : { key: e.key }))
    : result;
}

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
    expect(messages(submit({ ...valid(), ...change } as Inputs))).toEqual([
      values ? { key, values } : { key },
    ]);
  });

  it('reports every field that needs attention at once, in form order', () => {
    const result = submit({ ...valid(), age: '', pendingIdDocument: null, objectId: '' });
    expect('errors' in result && result.errors.map((e: FieldError) => [e.field, e.key])).toEqual([
      ['age', 'errors.ageRange'],
      ['pendingIdDocument', 'errors.idDocumentRequired'],
      ['objectId', 'errors.vehicleRequired'],
    ]);
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
      // Whether the pack declares the category is the pack tests' check (src/pack/pack.test.ts).
      'an upload category that is no lowercase-kebab name',
      (f) => ((f[4].file as Record<string, unknown>).category = '../generated certificate'),
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

describe('business-details completes like the former TSX form', () => {
  const definition = () => parseDefinition(raw('business-details'), 'business-details');
  const member = { firstName: 'Bart', lastName: 'Simpson', personalCode: '39001010000' };

  function inputs(): Inputs {
    const start = initialInputs(definition(), {}, (k) => k);
    return {
      ...start,
      companyName: '  Näidis ',
      boardMembers: [member, { firstName: '', lastName: '', personalCode: '' }],
      applicantFirstName: 'Bart',
      applicantLastName: 'Simpson',
      applicantAge: '40',
      applicantEmail: '',
      pendingAoaDocument: {
        pendingKey: 'pending/bart/1/aoa.pdf',
        filename: 'aoa.pdf',
        contentType: 'application/pdf',
        size: 10,
      },
    };
  }

  function submit(change: Partial<Inputs> = {}) {
    const d = definition();
    return completion(d, d.actions[0], { ...inputs(), ...change } as Inputs);
  }

  it('starts with one empty board row, share capital 2500 and citizen', () => {
    const start = initialInputs(definition(), {}, (k) => k);
    expect(start.boardMembers).toEqual([{ firstName: '', lastName: '', personalCode: '' }]);
    expect(start.shareCapital).toBe('2500');
    expect(start.applicantResidency).toBe('citizen');
  });

  it('sends the suffixed name, cleaned rows and a decimal share capital', () => {
    const result = submit({ shareCapital: '2500.5', applicantResidency: 'e-resident' });
    expect(result).toMatchObject({
      variables: {
        companyName: { value: 'Näidis OÜ', type: 'String' },
        boardMembers: { value: JSON.stringify([member]), type: 'Json' },
        shareCapital: { value: 2500.5, type: 'Double' },
        applicantAge: { value: 40, type: 'Integer' },
        applicantResidency: { value: 'e-resident', type: 'String' },
        sendBackReason: { value: '', type: 'String' },
        additionalFounders: { value: '[]', type: 'Json' },
      },
    });
  });

  it.each<[string, Partial<Inputs>, string, Record<string, string>?]>([
    ['an empty company name', { companyName: '  ' }, 'errors.companyNameRequired'],
    [
      'no board member',
      { boardMembers: [{ firstName: '', lastName: '', personalCode: '' }] },
      'errors.boardMemberRequired',
    ],
    [
      'an incomplete board member',
      { boardMembers: [{ ...member, lastName: '' }] },
      'errors.boardMemberIncomplete',
    ],
    [
      'a 10-digit personal code',
      { boardMembers: [{ ...member, personalCode: '3900101000' }] },
      'errors.personalCodeFormat',
      { ...member, personalCode: '3900101000' },
    ],
    ['share capital below 2500', { shareCapital: '2499.99' }, 'errors.shareCapitalMin'],
    ['age 131', { applicantAge: '131' }, 'errors.applicantAgeRange'],
    ['no articles', { pendingAoaDocument: null }, 'errors.aoaRequired'],
  ])('refuses %s', (_, change, key, values) => {
    expect(messages(submit(change))).toEqual([values ? { key, values } : { key }]);
  });
});

describe('business elements stay inside format v1', () => {
  function withField(index: number, change: (field: Record<string, unknown>) => void) {
    const d = raw('business-details');
    change((d.fields as Record<string, unknown>[])[index]);
    return () => parseDefinition(d, 'business-details');
  }

  it.each<[string, number, (field: Record<string, unknown>) => void]>([
    ['a suffix with markup', 0, (f) => (f.suffix = '<b>OÜ</b>')],
    ['a suffix on a number field', 3, (f) => (f.suffix = 'OÜ')],
    ['rows without columns', 1, (f) => ((f.table as Record<string, unknown>).columns = [])],
    [
      'an unknown column format',
      1,
      (f) => ((f.table as { columns: Record<string, unknown>[] }).columns[2].format = 'regex'),
    ],
    ['a radio with one choice', 7, (f) => (f.options as unknown[]).splice(1)],
    ['a radio default outside its options', 7, (f) => (f.default = 'martian')],
    ['decimal on a text field', 0, (f) => (f.decimal = true)],
    ['a default on a file field', 2, (f) => (f.default = 'x')],
    [
      'a fixed length of 0',
      1,
      (f) => ((f.table as { columns: Record<string, unknown>[] }).columns[2].fixedLength = 0),
    ],
    ['a fixed length on a number field', 3, (f) => (f.fixedLength = 4)],
  ])('refuses %s', (_, index, change) => {
    expect(withField(index, change)).toThrow(InvalidDefinition);
  });
});
