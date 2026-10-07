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

const PACK_FORMS = resolve(__dirname, '../../../../packs/test/frontend/forms');

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

// What each pack form sends, or which errors it shows, is its spec's Behaviour
// examples, run by src/pack/behaviour.test.ts. Here the renderer itself, on
// the test pack's forms.
describe('the renderer on owner-vehicle', () => {
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

  it('reports every field that needs attention at once, in form order', () => {
    const result = submit({ ...valid(), age: '', pendingIdDocument: null, objectId: '' });
    expect('errors' in result && result.errors.map((e: FieldError) => [e.field, e.key])).toEqual([
      ['age', 'errors.ageRange'],
      ['pendingIdDocument', 'errors.idDocumentRequired'],
      ['objectId', 'errors.vehicleRequired'],
    ]);
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
