// @vitest-environment happy-dom
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseDefinition } from '../forms/schema/definition';
import { completion, initialInputs, type Inputs } from '../forms/schema/values';

/**
 * Runs every form spec's behaviour examples through the portal's form renderer
 * (docs/platform-api.md, "Service examples"): what a form sends, or which
 * errors it shows, is pack data, tested with the pack.
 *
 * A spec `forms/<id>.md` whose form the pack defines has a `## Behaviour
 * examples` section: the
 * inputs a user leaves the form with in a `json` code block, then a table
 * `| Action | Change | Result |`. A change replaces input fields; the result
 * is the expected completion variables (a JSON object of values, a subset:
 * Json variables compare parsed) or the error keys in form order
 * (`errors.a, errors.b`). The action `(initial)` instead fills the form from
 * the task's variables (the change) and compares the result with the inputs
 * it starts with.
 */

/** The pack under test: PACK_DIR (scripts/pack-check.sh), else the core test pack (packs/test). */
const PACK_ROOT = process.env.PACK_DIR ?? resolve(__dirname, '../../../packs/test');
const SPECS = resolve(PACK_ROOT, 'docs/business/services');
const FORMS = resolve(PACK_ROOT, 'frontend/forms');
const HEADING = 'Behaviour examples';

interface Example {
  action: string;
  change: Record<string, unknown>;
  result: string;
}

/** The section's JSON block and table, or null when the spec has none. */
function section(md: string): { inputs: Inputs; examples: Example[] } | null {
  const lines = md.replace(/\r\n/g, '\n').split('\n');
  const start = lines.findIndex(
    (l) => /^#{2,3}\s+/.test(l) && l.replace(/^#+\s+/, '').trim() === HEADING,
  );
  if (start < 0) return null;
  const body: string[] = [];
  for (let i = start + 1; i < lines.length && !/^#{2,3}\s+/.test(lines[i]); i++)
    body.push(lines[i]);
  const open = body.findIndex((l) => l.trim().startsWith('```'));
  const close = body.findIndex((l, i) => i > open && l.trim().startsWith('```'));
  const inputs = JSON.parse(body.slice(open + 1, close).join('\n')) as Inputs;
  const rows = body.slice(close + 1).filter((l) => l.trim().startsWith('|'));
  const cells = (l: string) =>
    l
      .trim()
      .slice(1, -1)
      .split('|')
      .map((c) => c.trim().replace(/^`(.*)`$/, '$1'));
  const header = cells(rows[0]);
  const examples = rows.slice(2).map((r) => {
    const c = cells(r);
    const at = (name: string) => c[header.indexOf(name)] ?? '';
    return {
      action: at('Action'),
      change: JSON.parse(at('Change') || '{}') as Record<string, unknown>,
      result: at('Result'),
    };
  });
  return { inputs, examples };
}

/** Every form spec of the pack with its definition id, as [label, spec path, form id]. */
function formSpecs(): [string, string, string][] {
  if (!existsSync(SPECS)) return [];
  return readdirSync(SPECS)
    .filter((s) => existsSync(resolve(SPECS, s, 'forms')))
    .flatMap((s) =>
      readdirSync(resolve(SPECS, s, 'forms'))
        .filter((f) => f.endsWith('.md'))
        .map((f): [string, string, string] => [
          `${s}/forms/${f}`,
          resolve(SPECS, s, 'forms', f),
          f.replace(/\.md$/, ''),
        ]),
    );
}

/** A completion's variables as plain values, Json ones parsed. */
function values(variables: Record<string, { value: unknown; type: string }>) {
  return Object.fromEntries(
    Object.entries(variables).map(([k, v]) => [
      k,
      v.type === 'Json' && typeof v.value === 'string' ? JSON.parse(v.value) : v.value,
    ]),
  );
}

// Every form the pack defines (frontend/forms/<id>.json) needs its examples.
const specs = formSpecs().filter(([, , id]) => existsSync(resolve(FORMS, `${id}.json`)));

describe.skipIf(specs.length === 0)('form behaviour examples', () => {
  it.each(specs)('%s', (_label, path, id) => {
    const found = section(readFileSync(path, 'utf-8'));
    expect(found, `${HEADING} section`).not.toBeNull();
    const { inputs, examples } = found!;
    const definition = parseDefinition(
      JSON.parse(readFileSync(resolve(FORMS, `${id}.json`), 'utf-8')),
      id,
    );
    expect(examples.length, 'a behaviour examples table').toBeGreaterThan(0);
    examples.forEach((example, i) => {
      const row = `row ${i + 1} (${example.action})`;
      const expected = example.result.trim();
      if (example.action === '(initial)') {
        const start = initialInputs(definition, example.change, (k) => k);
        expect(start, row).toMatchObject(JSON.parse(expected) as Inputs);
        return;
      }
      const action = definition.actions.find((a) => a.id === example.action);
      expect(action, `${row}: no action ${example.action}`).toBeDefined();
      const result = completion(definition, action!, { ...inputs, ...example.change } as Inputs);
      if (expected.startsWith('{')) {
        expect('variables' in result ? values(result.variables) : result, row).toMatchObject(
          JSON.parse(expected) as Record<string, unknown>,
        );
      } else {
        const keys = expected.split(',').map((k) => k.trim());
        expect('errors' in result ? result.errors.map((e) => e.key) : result, row).toEqual(keys);
      }
    });
  });
});
