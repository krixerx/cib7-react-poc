// Tests for the manifest registry. SERVICES_SPEC_DIR, ENGINE_PROCESSES_DIR and
// CORE_SCHEMA_FILE are read into module-level consts at import time, so the
// fixture directories must exist and the env vars must be stubbed BEFORE the
// module is (dynamically) imported.

import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

type ManifestModule = typeof import('./manifest');

const CORE_ID = 'https://example.test/schemas/core/v1.json';

// The core's shared definitions, as cib7/src/main/resources/schemas/core-v1.json
// holds them: form schemas refer to them by URL, the loader inlines them.
const CORE_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: CORE_ID,
  $defs: {
    nonBlank: { type: 'string', pattern: '\\S' },
    name: { $ref: '#/$defs/nonBlank' },
  },
};

// The engine's form schemas, as the service-builder writes them into the
// pack's engine/processes/<service>/schemas/.
const ENGINE_SCHEMAS: Record<string, unknown> = {
  'start.json': {
    'x-process': 'toyRegistration',
    'x-form': 'start',
    type: 'object',
    properties: {
      firstName: { $ref: `${CORE_ID}#/$defs/name` },
      age: { type: 'integer', minimum: 0, maximum: 130 },
    },
  },
  'toy-details.json': {
    'x-process': 'toyRegistration',
    'x-form': 'toy-details',
    type: 'object',
    properties: {
      toyName: { $ref: `${CORE_ID}#/$defs/nonBlank` },
      fragile: { type: 'boolean' },
      parts: { type: 'array', items: { type: 'string' } },
      spaOnly: { type: 'string' },
    },
    required: ['toyName'],
  },
};

// Mirrors the real manifest shape in a pack's docs/business/services/*/build/mcp-service.json:
// texts and the offered fields only; the rules come from the engine schemas.
const VALID_MANIFEST = {
  version: 2,
  key: 'toyRegistration',
  name: 'Toy Registration',
  description: 'Register a toy with the Toy Authority.',
  audience: 'applicant',
  candidateGroups: ['applicant'],
  initialTask: {
    formKey: 'toy-details',
    audience: 'applicant',
    name: 'Submit toy details',
  },
  start: {
    fields: { firstName: 'Owner first name.', age: 'Owner age in years.' },
  },
  userTasks: [
    {
      formKey: 'toy-details',
      name: 'Submit toy details',
      audience: 'applicant',
      fields: { toyName: 'Name of the toy.', fragile: 'Breaks easily.', parts: 'Loose parts.' },
      notOffered: ['spaOnly'],
    },
  ],
};

// Each of these must be refused at load (logged + skipped) without
// poisoning the registry.
const BROKEN_MANIFESTS: Record<string, unknown> = {
  'no-key': { version: 2, name: 'Broken', description: 'No key field.' },
  'old-version': { ...VALID_MANIFEST, version: 1, key: 'oldToy' },
  'unknown-field': {
    ...VALID_MANIFEST,
    key: 'toyRegistration',
    userTasks: [{ formKey: 'toy-details', fields: { toyName: 'x', colour: 'Not in the schema.' } }],
  },
  'offered-and-not': {
    ...VALID_MANIFEST,
    key: 'toyRegistration',
    userTasks: [
      { formKey: 'toy-details', fields: { toyName: 'x' }, notOffered: ['toyName', 'spaOnly'] },
    ],
  },
  'required-not-offered': {
    ...VALID_MANIFEST,
    key: 'toyRegistration',
    userTasks: [{ formKey: 'toy-details', fields: { fragile: 'x' } }],
  },
};

let specDir: string;
let engineDir: string;
let manifest: ManifestModule;

function writeService(dirName: string, serviceJson: unknown, trainingMd?: string): void {
  const buildDir = join(specDir, dirName, 'build');
  mkdirSync(buildDir, { recursive: true });
  writeFileSync(join(buildDir, 'mcp-service.json'), JSON.stringify(serviceJson, null, 2));
  if (trainingMd !== undefined) {
    writeFileSync(join(buildDir, 'mcp-training.md'), trainingMd);
  }
}

beforeAll(async () => {
  specDir = mkdtempSync(join(tmpdir(), 'cib7-manifest-test-'));
  engineDir = mkdtempSync(join(tmpdir(), 'cib7-engine-test-'));
  const schemas = join(engineDir, 'toy-registration', 'schemas');
  mkdirSync(schemas, { recursive: true });
  for (const [file, schema] of Object.entries(ENGINE_SCHEMAS)) {
    writeFileSync(join(schemas, file), JSON.stringify(schema));
  }
  writeFileSync(join(engineDir, 'core-v1.json'), JSON.stringify(CORE_SCHEMA));

  writeService('toy-registration', VALID_MANIFEST, '# Toy Registration training');
  for (const [dir, broken] of Object.entries(BROKEN_MANIFESTS)) writeService(dir, broken);
  // A service dir without a build/mcp-service.json must be silently skipped.
  mkdirSync(join(specDir, 'empty-service'), { recursive: true });

  vi.stubEnv('SERVICES_SPEC_DIR', specDir);
  vi.stubEnv('ENGINE_PROCESSES_DIR', engineDir);
  vi.stubEnv('CORE_SCHEMA_FILE', join(engineDir, 'core-v1.json'));
  vi.resetModules();
  // The broken fixtures are expected to log loader errors — keep the test
  // output clean.
  vi.spyOn(console, 'error').mockImplementation(() => {});
  manifest = await import('./manifest');
  manifest.loadManifests();
});

afterAll(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  rmSync(specDir, { recursive: true, force: true });
  rmSync(engineDir, { recursive: true, force: true });
});

describe('loadManifests', () => {
  it('loads the valid manifest and skips broken/empty service dirs', () => {
    const registry = manifest.loadManifests();
    expect(Array.from(registry.keys())).toEqual(['toyRegistration']);
    expect(registry.get('toyRegistration')?.manifest.description).toBe(
      'Register a toy with the Toy Authority.',
    );
  });

  it.each([
    'missing required "key" field',
    'manifest version 1, this sidecar reads 2',
    `"colour" is not a field of the engine's form schema`,
    'required field(s) toyName are not offered',
    'toyName both offered and in "notOffered"',
  ])('refuses a manifest: %s', (message) => {
    expect(console.error).toHaveBeenCalledWith(expect.stringContaining(message));
  });

  it('shows the LLM the engine rules, core definitions inlined, with descriptions', () => {
    const schema = manifest.getManifest('toyRegistration')?.manifest.userTasks?.[0].schema;
    expect(schema).toEqual({
      type: 'object',
      properties: {
        toyName: { type: 'string', pattern: '\\S', description: 'Name of the toy.' },
        fragile: { type: 'boolean', description: 'Breaks easily.' },
        parts: { type: 'array', items: { type: 'string' }, description: 'Loose parts.' },
      },
      required: ['toyName'],
    });
  });

  it('inlines nested core references in the start schema', () => {
    const start = manifest.getManifest('toyRegistration')?.manifest.variables as {
      properties: Record<string, unknown>;
    };
    expect(start.properties.firstName).toEqual({
      type: 'string',
      pattern: '\\S',
      description: 'Owner first name.',
    });
  });
});

describe('inlineCoreRefs', () => {
  it('refuses a reference outside the core definitions', () => {
    expect(() =>
      manifest.inlineCoreRefs({ $ref: 'https://elsewhere.test/x.json' }, CORE_SCHEMA),
    ).toThrow(/unresolvable \$ref/);
  });
});

describe('getManifest', () => {
  it('finds a loaded manifest by key, with its training markdown', () => {
    const entry = manifest.getManifest('toyRegistration');
    expect(entry).toBeDefined();
    expect(entry?.manifest.name).toBe('Toy Registration');
    expect(entry?.trainingMd).toContain('Toy Registration training');
    expect(Array.from(entry?.userTasks.keys() ?? [])).toEqual(['toy-details']);
  });

  it('returns undefined for an unknown key', () => {
    expect(manifest.getManifest('doesNotExist')).toBeUndefined();
  });
});

describe('validateVariables', () => {
  it('accepts conforming start variables', () => {
    const result = manifest.validateVariables('toyRegistration', { firstName: 'Lisa', age: 34 });
    expect(result).toEqual({ ok: true, data: { firstName: 'Lisa', age: 34 } });
  });

  it('rejects a value the engine schema refuses', () => {
    const result = manifest.validateVariables('toyRegistration', { firstName: ' ', age: 34 });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.issues.map((i) => i.keyword)).toEqual(['pattern']);
    }
  });

  it('rejects fields the manifest does not offer', () => {
    const result = manifest.validateVariables('toyRegistration', {
      firstName: 'Lisa',
      age: 34,
      smuggled: true,
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.issues.map((i) => i.keyword)).toContain('additionalProperties');
    }
  });

  it('flags an unknown service key', () => {
    const result = manifest.validateVariables('doesNotExist', {});
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.issues[0].message).toContain('Unknown service key');
    }
  });
});

describe('validateTaskVariables', () => {
  it('accepts conforming task variables and resolves the owning service', () => {
    const result = manifest.validateTaskVariables('toy-details', { toyName: 'Teddy' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.serviceKey).toBe('toyRegistration');
      expect(result.data).toEqual({ toyName: 'Teddy' });
      expect(result.task.descriptor.formKey).toBe('toy-details');
    }
  });

  it('rejects a field the engine accepts but the manifest does not offer', () => {
    const result = manifest.validateTaskVariables('toy-details', {
      toyName: 'Teddy',
      spaOnly: 'x',
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.issues).toEqual([
        expect.objectContaining({ path: '/spaOnly', keyword: 'additionalProperties' }),
      ]);
    }
  });

  it('rejects task variables that miss required fields', () => {
    const result = manifest.validateTaskVariables('toy-details', { fragile: true });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.issues).toEqual([
        expect.objectContaining({
          keyword: 'required',
          params: expect.objectContaining({ missingProperty: 'toyName' }),
        }),
      ]);
    }
  });

  it('flags an unknown formKey', () => {
    const result = manifest.validateTaskVariables('no-such-form', {});
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.issues[0].message).toContain('no-such-form');
    }
  });

  describe('partial mode (drafts)', () => {
    it('accepts variables that miss required fields', () => {
      const result = manifest.validateTaskVariables(
        'toy-details',
        { fragile: true },
        {
          partial: true,
        },
      );
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.data).toEqual({ fragile: true });
        expect(result.serviceKey).toBe('toyRegistration');
      }
    });

    it('still rejects wrong types', () => {
      const result = manifest.validateTaskVariables(
        'toy-details',
        { fragile: 'yes' },
        {
          partial: true,
        },
      );
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.issues.map((i) => i.keyword)).toContain('type');
      }
    });

    it('still rejects fields the manifest does not offer', () => {
      const result = manifest.validateTaskVariables(
        'toy-details',
        { smuggled: true },
        {
          partial: true,
        },
      );
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.issues.map((i) => i.keyword)).toContain('additionalProperties');
      }
    });

    it('flags an unknown formKey even in partial mode', () => {
      const result = manifest.validateTaskVariables('no-such-form', {}, { partial: true });
      expect(result.ok).toBe(false);
    });
  });
});

describe('findServiceByFormKey', () => {
  it('maps a formKey back to its service and compiled task', () => {
    const hit = manifest.findServiceByFormKey('toy-details');
    expect(hit?.serviceKey).toBe('toyRegistration');
    expect(hit?.task.descriptor.name).toBe('Submit toy details');
  });

  it('returns undefined for an unknown formKey', () => {
    expect(manifest.findServiceByFormKey('no-such-form')).toBeUndefined();
  });
});
