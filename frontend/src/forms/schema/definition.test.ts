import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  completion,
  InvalidDefinition,
  parseDefinition,
  revealedFields,
  shown,
  type FormDefinition,
} from './definition';

const PACK_FORMS = resolve(__dirname, '../../../../packs/reference/frontend/forms');

function packDefinition(id: string): unknown {
  return JSON.parse(readFileSync(resolve(PACK_FORMS, `${id}.json`), 'utf-8'));
}

function vehicleReview(): FormDefinition {
  return parseDefinition(packDefinition('vehicle-review'), 'vehicle-review');
}

describe('reference pack definitions', () => {
  it('every definition in the pack parses as format v1', () => {
    const files = readdirSync(PACK_FORMS).filter((f) => f.endsWith('.json'));
    expect(files.length).toBeGreaterThan(0);
    for (const file of files) {
      const id = file.replace(/\.json$/, '');
      expect(() => parseDefinition(packDefinition(id), id), file).not.toThrow();
    }
  });
});

describe('vehicle-review behaves like the former TSX form', () => {
  it('approve completes with the decision only', () => {
    const d = vehicleReview();
    const approve = d.actions.find((a) => a.id === 'approve')!;
    expect(revealedFields(d, 'approve')).toEqual([]);
    expect(completion(d, approve, { sendBackReason: '' })).toEqual({
      variables: { decision: { value: 'approve', type: 'String' } },
    });
  });

  it('send back needs a reason and sends it trimmed', () => {
    const d = vehicleReview();
    const sendBack = d.actions.find((a) => a.id === 'sendback')!;
    expect(revealedFields(d, 'sendback').map((f) => f.name)).toEqual(['sendBackReason']);
    expect(completion(d, sendBack, { sendBackReason: '   ' })).toEqual({
      missing: 'errors.reasonRequired',
    });
    expect(completion(d, sendBack, { sendBackReason: '  ID unreadable ' })).toEqual({
      variables: {
        decision: { value: 'sendback', type: 'String' },
        sendBackReason: { value: 'ID unreadable', type: 'String' },
      },
    });
  });

  it('shows the decision only on a finished case and the previous reason only while editing', () => {
    const d = vehicleReview();
    const decision = d.summary.find((s) => s.variable === 'decision')!;
    expect(shown(decision.show, true)).toBe(true);
    expect(shown(decision.show, false)).toBe(false);
    expect(shown(d.notices[0].show, false)).toBe(true);
    expect(shown(d.notices[0].show, true)).toBe(false);
  });
});

describe('definitions outside format v1 are refused whole', () => {
  const base = () => structuredClone(packDefinition('vehicle-review')) as Record<string, unknown>;

  it.each([
    ['an unknown top-level key', (d: Record<string, unknown>) => (d.script = 'alert(1)')],
    ['another version', (d: Record<string, unknown>) => (d.version = 2)],
    [
      'an unknown field type',
      (d: Record<string, unknown>) => ((d.fields as { type: string }[])[0].type = 'html'),
    ],
    [
      'an action without completion target',
      (d: Record<string, unknown>) =>
        ((d.actions as { complete: Record<string, unknown> }[])[0].complete.decision = {
          type: 'String',
        }),
    ],
    [
      'a completion from a display field',
      (d: Record<string, unknown>) =>
        ((d.actions as { complete: Record<string, unknown> }[])[0].complete.price = {
          field: 'price',
          type: 'Double',
        }),
    ],
    [
      'a field revealed by no action',
      (d: Record<string, unknown>) =>
        ((d.fields as { revealedBy?: string }[])[1].revealedBy = 'nope'),
    ],
    [
      'a variable name that is no identifier',
      (d: Record<string, unknown>) =>
        ((d.summary as { variable: string }[])[0].variable = '__proto__.x'),
    ],
  ])('refuses %s', (_, mutate) => {
    const d = base();
    mutate(d);
    expect(() => parseDefinition(d, 'vehicle-review')).toThrow(InvalidDefinition);
  });

  it('refuses a definition served for another form id', () => {
    expect(() => parseDefinition(packDefinition('vehicle-review'), 'owner-vehicle')).toThrow(
      InvalidDefinition,
    );
  });
});
