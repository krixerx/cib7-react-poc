// Tests for the manifest registry. SERVICES_SPEC_DIR is read into a
// module-level const at import time, so the fixture directory must exist and
// the env var must be stubbed BEFORE the module is (dynamically) imported.

import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

type ManifestModule = typeof import('./manifest');

// Mirrors the real manifest shape in docs/business/services/*/build/mcp-service.json,
// trimmed to one user task and a two-field variables schema.
const VALID_MANIFEST = {
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
  variables: {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    type: 'object',
    properties: {
      firstName: { type: 'string', minLength: 1 },
      age: { type: 'integer', minimum: 0, maximum: 130 },
    },
    required: ['firstName', 'age'],
    additionalProperties: false,
  },
  userTasks: [
    {
      formKey: 'toy-details',
      name: 'Submit toy details',
      audience: 'applicant',
      schema: {
        $schema: 'https://json-schema.org/draft/2020-12/schema',
        type: 'object',
        properties: {
          toyName: { type: 'string', minLength: 1 },
          fragile: { type: 'boolean' },
        },
        required: ['toyName'],
        additionalProperties: false,
      },
    },
  ],
};

// Missing the required "key" field — the loader must reject it (logged +
// skipped) without poisoning the registry.
const BROKEN_MANIFEST = {
  name: 'Broken Service',
  description: 'No key field.',
  variables: { type: 'object' },
};

let specDir: string;
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
  writeService('toy-registration', VALID_MANIFEST, '# Toy Registration training');
  writeService('broken-service', BROKEN_MANIFEST);
  // A service dir without a build/mcp-service.json must be silently skipped.
  mkdirSync(join(specDir, 'empty-service'), { recursive: true });

  vi.stubEnv('SERVICES_SPEC_DIR', specDir);
  vi.resetModules();
  // The broken-service fixture is expected to log one loader error — keep the
  // test output clean.
  vi.spyOn(console, 'error').mockImplementation(() => {});
  manifest = await import('./manifest');
  manifest.loadManifests();
});

afterAll(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  rmSync(specDir, { recursive: true, force: true });
});

describe('loadManifests', () => {
  it('loads the valid manifest and skips broken/empty service dirs', () => {
    const registry = manifest.loadManifests();
    expect(Array.from(registry.keys())).toEqual(['toyRegistration']);
  });

  it('reports the broken manifest via console.error', () => {
    expect(console.error).toHaveBeenCalledWith(
      expect.stringContaining('missing required "key" field'),
    );
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

  it('rejects missing required fields with issues', () => {
    const result = manifest.validateVariables('toyRegistration', { firstName: 'Lisa' });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.issues).toEqual([
        expect.objectContaining({
          keyword: 'required',
          params: expect.objectContaining({ missingProperty: 'age' }),
        }),
      ]);
    }
  });

  it('rejects extra properties (additionalProperties: false)', () => {
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
