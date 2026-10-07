// The pack's manifests (PACK_DIR, else the core test pack) load against the pack's own engine schemas
// and the core's shared definitions. A manifest the loader refuses only logs
// at runtime and drops the service from the agent's view, so this is where a
// drift between a manifest and its forms shows up. PACK_DIR points at another
// pack (scripts/pack-check.sh).

import { join, resolve } from 'node:path';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

const pack = resolve(process.env.PACK_DIR ?? join(__dirname, '../../../packs/test'));
const specs = join(pack, 'docs/business/services');

let manifest: typeof import('./manifest');
const errors: string[] = [];

beforeAll(async () => {
  vi.stubEnv('SERVICES_SPEC_DIR', specs);
  vi.stubEnv('ENGINE_PROCESSES_DIR', join(pack, 'engine/processes'));
  vi.stubEnv(
    'CORE_SCHEMA_FILE',
    join(__dirname, '../../../cib7/src/main/resources/schemas/core-v1.json'),
  );
  vi.resetModules();
  vi.spyOn(console, 'error').mockImplementation((m: string) => void errors.push(m));
  vi.spyOn(console, 'warn').mockImplementation((m: string) => void errors.push(m));
  manifest = await import('./manifest');
  manifest.loadManifests();
});

afterAll(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe(`the pack's MCP manifests (${pack})`, () => {
  it('all load', () => {
    expect(errors).toEqual([]);
    const withManifest = readdirSync(specs).filter((d) =>
      existsSync(join(specs, d, 'build/mcp-service.json')),
    );
    expect(manifest.listManifests()).toHaveLength(withManifest.length);
  });

  it('cover every user task form the engine has a schema for', () => {
    const processes = join(pack, 'engine/processes');
    for (const service of readdirSync(processes)) {
      const dir = join(processes, service, 'schemas');
      if (!existsSync(dir)) continue;
      for (const file of readdirSync(dir).filter((f) => f.endsWith('.json'))) {
        const schema = JSON.parse(readFileSync(join(dir, file), 'utf8')) as Record<string, string>;
        if (schema['x-form'] === 'start') continue;
        expect(
          manifest.getManifest(schema['x-process'])?.userTasks.has(schema['x-form']),
          `${service}/schemas/${file} has no userTasks entry in its MCP manifest`,
        ).toBe(true);
      }
    }
  });
});
