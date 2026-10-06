import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import i18next from 'i18next';
import { describe, expect, it } from 'vitest';
import { translateBackendName } from '../i18n/backendNames';
import { parseDefinition, type FormDefinition } from '../forms/schema/definition';
import { InvalidCatalog, parseCatalog } from './catalog';

const PACK = resolve(__dirname, '../../../packs/reference/frontend');
const CORE_LOCALES = resolve(__dirname, '../i18n/locales');
const LANGS = ['en', 'ar'];
const PLURAL = /_(zero|one|two|few|many|other)$/;

function json(path: string): Record<string, unknown> {
  return JSON.parse(readFileSync(path, 'utf-8'));
}

/** Every leaf key of a locale file, dotted. */
function leafKeys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix];
  return Object.entries(value).flatMap(([k, v]) => leafKeys(v, prefix ? `${prefix}.${k}` : k));
}

function packKeys(lang: string, ns: string): Set<string> {
  return new Set(leafKeys(json(resolve(PACK, 'locales', lang, `${ns}.json`))));
}

function coreKeys(lang: string, ns: string): Set<string> {
  return new Set(leafKeys(json(resolve(CORE_LOCALES, lang, `${ns}.json`))));
}

/** Every text key a form definition uses, as `[namespace or null, key]`. */
function textKeys(d: FormDefinition): string[] {
  const keys: (string | undefined)[] = [
    d.intro.edit,
    d.intro.readOnly,
    d.intro.resubmission,
    d.banner?.title,
  ];
  for (const s of d.summary) {
    keys.push(s.label, s.template?.key, s.item, ...Object.values(s.options ?? {}));
  }
  for (const f of d.fields) {
    keys.push(f.label, f.hint, f.placeholder, f.requiredMessage, f.rangeMessage, f.emailMessage);
    keys.push(f.requiredWhenListed?.message, f.source?.label, f.source?.error);
    keys.push(f.file?.dropLabel, f.file?.existingFilename);
    const c = f.contacts;
    if (c) {
      keys.push(c.legend, c.namePlaceholder, c.emailPlaceholder, c.add, c.removeAria);
      keys.push(c.nameMessage, c.emailMessage, c.duplicateMessage, c.notField?.message);
    }
    for (const o of f.options ?? []) keys.push(o.label, o.hint);
    const t = f.table;
    if (t) {
      keys.push(
        t.legend,
        t.add,
        t.removeAria,
        t.requiredMessage,
        t.incompleteMessage,
        t.formatMessage,
      );
      keys.push(...t.columns.map((col) => col.placeholder));
    }
  }
  for (const n of d.notices) keys.push(n.label);
  for (const a of d.actions) keys.push(a.label, a.resubmitLabel, a.workingLabel, a.confirmLabel);
  return keys.filter((k): k is string => !!k);
}

/** A key exists when it is a leaf, or exists as plural forms (`_one`, `_other`). */
function has(keys: Set<string>, key: string): boolean {
  return keys.has(key) || (keys.has(`${key}_one`) && keys.has(`${key}_other`));
}

describe('reference pack', () => {
  const catalog = parseCatalog(json(resolve(PACK, 'catalog.json')));

  it('has every catalog namespace in every language, with the same keys', () => {
    // Plural forms differ per language (Arabic has six), so compare the base keys.
    const base = (lang: string, ns: string) =>
      [...new Set([...packKeys(lang, ns)].map((k) => k.replace(PLURAL, '')))].sort();
    for (const ns of catalog.namespaces) {
      const en = base('en', ns);
      for (const lang of LANGS) {
        expect(base(lang, ns), `${lang}/${ns}.json`).toEqual(en);
      }
    }
  });

  it('has every text a form definition uses, in every language', () => {
    const forms = readdirSync(resolve(PACK, 'forms')).filter((f) => f.endsWith('.json'));
    for (const file of forms) {
      const id = file.replace(/\.json$/, '');
      const d = parseDefinition(json(resolve(PACK, 'forms', file)), id);
      expect(catalog.namespaces, `${id} namespace in the catalog`).toContain(d.i18n);
      for (const lang of LANGS) {
        for (const key of textKeys(d)) {
          const [ns, local] = key.includes(':') ? key.split(':') : [d.i18n, key];
          const keys = ns === d.i18n ? packKeys(lang, ns) : coreKeys(lang, ns);
          expect(has(keys, local), `${id}: ${lang} ${ns}:${local}`).toBe(true);
        }
      }
    }
  });

  it('translates every display name the BPMN models give', () => {
    const processes = resolve(PACK, '../engine/processes');
    const named = /<bpmn:(\w+)\b[^>]*?\sname="([^"]*)"/g;
    const notShown = new Set(['sequenceFlow', 'message', 'signal', 'error', 'textAnnotation']);
    const unescape = (v: string) =>
      v
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&amp;/g, '&');
    const bpmnNames = new Set<string>();
    for (const dir of readdirSync(processes)) {
      for (const file of readdirSync(resolve(processes, dir)).filter((f) => f.endsWith('.bpmn'))) {
        const xml = readFileSync(resolve(processes, dir, file), 'utf-8');
        for (const [, tag, name] of xml.matchAll(named)) {
          if (!notShown.has(tag)) bpmnNames.add(unescape(name));
        }
      }
    }
    expect(bpmnNames.size).toBeGreaterThan(0);
    for (const lang of LANGS) {
      const names = json(resolve(PACK, 'locales', lang, 'names.json'));
      const missing = [...bpmnNames].filter((n) => typeof names[n] !== 'string');
      expect(missing, `${lang}/names.json`).toEqual([]);
    }
  });

  it('describes every catalog service and issuer in the catalog texts', () => {
    for (const lang of LANGS) {
      const keys = packKeys(lang, 'catalog');
      for (const key of Object.keys(catalog.services)) {
        expect(keys.has(`services.${key}.summary`), `${lang} ${key} summary`).toBe(true);
        expect(keys.has(`services.${key}.fee`), `${lang} ${key} fee`).toBe(true);
      }
      for (const id of Object.keys(catalog.issuers)) {
        expect(keys.has(`issuers.${id}.name`), `${lang} issuer ${id}`).toBe(true);
      }
    }
  });
});

describe('catalog format v1 is strict', () => {
  const valid = () => json(resolve(PACK, 'catalog.json'));

  it.each<[string, (c: Record<string, unknown>) => void]>([
    ['an unknown key', (c) => (c.script = 'x')],
    ['another version', (c) => (c.version = 2)],
    ['a namespace that is no id', (c) => (c.namespaces = ['catalog', 'names', '../common'])],
    ['no names namespace', (c) => (c.namespaces = ['catalog'])],
    [
      'an unknown category',
      (c) =>
        ((c.services as Record<string, { category: string }>).vehicleRegistration.category =
          'space'),
    ],
    [
      'an issuer that does not exist',
      (c) =>
        ((c.services as Record<string, { issuer: string }>).vehicleRegistration.issuer = 'nobody'),
    ],
    [
      'an unknown tone',
      (c) =>
        ((c.issuers as Record<string, { tone: string }>)['business-register'].tone = '#ff0000'),
    ],
  ])('refuses %s', (_, change) => {
    const c = valid();
    change(c);
    expect(() => parseCatalog(c)).toThrow(InvalidCatalog);
  });
});

describe('display names from the pack', () => {
  it('translates BPMN names, also with dots and colons, and passes unknown ones through', async () => {
    const i18n = i18next.createInstance();
    await i18n.init({
      lng: 'ar',
      resources: {
        ar: { names: { 'Vehicle Registration': 'تسجيل المركبات', 'Step 1.2: check': 'خطوة' } },
      },
    });
    expect(translateBackendName(i18n.t, 'Vehicle Registration')).toBe('تسجيل المركبات');
    expect(translateBackendName(i18n.t, 'Step 1.2: check')).toBe('خطوة');
    expect(translateBackendName(i18n.t, 'Free text from a reviewer.')).toBe(
      'Free text from a reviewer.',
    );
    expect(translateBackendName(i18n.t, null)).toBe('');
  });
});
