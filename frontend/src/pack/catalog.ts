import type { i18n as I18n } from 'i18next';

/**
 * The service pack's catalog: which services the pack ships, their category
 * and the authority that bills them, plus the text namespaces the pack
 * provides. The SPA itself holds nothing service-specific; it reads
 * `/pack/catalog.json` once at startup and adds the pack's texts
 * (`/pack/locales/<lang>/<namespace>.json`) to i18next before the first render.
 *
 * Part of the platform API (catalog format v1). Strict like the form
 * definitions: an unknown key or value refuses the whole catalog, and the SPA
 * then runs without pack texts rather than with half of them.
 */
export interface Catalog {
  version: 1;
  /** i18n namespaces under /pack/locales/<lang>/; `catalog` and `names` are always among them. */
  namespaces: string[];
  services: Record<string, { category: CategoryId; issuer: string }>;
  issuers: Record<string, { tone: Tone }>;
}

/** The life-event categories of the services page (core; see services/categories.ts). */
export const CATEGORY_IDS = [
  'business',
  'family',
  'property',
  'travel',
  'social',
  'other',
] as const;
export type CategoryId = (typeof CATEGORY_IDS)[number];

/** Colour of an issuer's payment header, from the design tokens. */
export const TONES = ['primary', 'ok'] as const;
export type Tone = (typeof TONES)[number];

/** Where nginx (and the Vite dev server) serve the pack. */
export const PACK_PATH = '/pack';

const ID = /^[a-z][a-z0-9-]{0,62}$/;
const KEY = /^[A-Za-z][A-Za-z0-9_]{0,62}$/;
const REQUIRED_NAMESPACES = ['catalog', 'names'];

export class InvalidCatalog extends Error {}

function fail(message: string): never {
  throw new InvalidCatalog(message);
}

function object(value: unknown, what: string, allowed?: string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    fail(`${what} must be an object`);
  if (allowed) {
    const unknownKey = Object.keys(value).find((k) => !allowed.includes(k));
    if (unknownKey) fail(`${what} has an unknown key '${unknownKey}'`);
  }
  return value as Record<string, unknown>;
}

function member<T extends string>(value: unknown, allowed: readonly T[], what: string): T {
  if (!allowed.includes(value as T)) fail(`${what} must be one of ${allowed.join(', ')}`);
  return value as T;
}

/** Checks a fetched catalog against format v1. */
export function parseCatalog(raw: unknown): Catalog {
  const root = object(raw, 'catalog', ['$comment', 'version', 'namespaces', 'services', 'issuers']);
  if (root.version !== 1) fail('version must be 1');
  if (!Array.isArray(root.namespaces)) fail('namespaces must be a list');
  const namespaces = root.namespaces.map((ns, i) => {
    if (typeof ns !== 'string' || !ID.test(ns)) fail(`namespaces[${i}] is invalid`);
    return ns;
  });
  for (const ns of REQUIRED_NAMESPACES) {
    if (!namespaces.includes(ns)) fail(`namespaces must include '${ns}'`);
  }
  const issuers: Catalog['issuers'] = {};
  for (const [id, value] of Object.entries(object(root.issuers, 'issuers'))) {
    if (!ID.test(id)) fail(`issuer id '${id}' is invalid`);
    const issuer = object(value, `issuers.${id}`, ['tone']);
    issuers[id] = { tone: member(issuer.tone, TONES, `issuers.${id}.tone`) };
  }
  const services: Catalog['services'] = {};
  for (const [key, value] of Object.entries(object(root.services, 'services'))) {
    if (!KEY.test(key)) fail(`service key '${key}' is invalid`);
    const service = object(value, `services.${key}`, ['category', 'issuer']);
    const issuer = service.issuer;
    if (typeof issuer !== 'string' || !issuers[issuer])
      fail(`services.${key}.issuer names no issuer`);
    services[key] = {
      category: member(service.category, CATEGORY_IDS, `services.${key}.category`),
      issuer,
    };
  }
  return { version: 1, namespaces, services, issuers };
}

let current: Catalog | null = null;

/** The loaded catalog, or null when the pack could not be read. */
export function catalog(): Catalog | null {
  return current;
}

/** A service's category; services the pack does not list fall under `other`. */
export function categoryOf(processDefinitionKey: string): CategoryId {
  return current?.services[processDefinitionKey]?.category ?? 'other';
}

/** The issuer that bills a service, with its tone, if the pack lists the service. */
export function issuerOf(processDefinitionKey: string): { id: string; tone: Tone } | null {
  const id = current?.services[processDefinitionKey]?.issuer;
  return id && current ? { id, tone: current.issuers[id].tone } : null;
}

/**
 * Reads the catalog and every pack namespace in every supported language into
 * i18next. Never throws: a missing or invalid pack is logged, and the SPA runs
 * on core texts only (pack text keys then show as keys, which is visible).
 */
export async function loadPack(i18n: I18n, languages: readonly string[]): Promise<void> {
  try {
    const res = await fetch(`${PACK_PATH}/catalog.json`, {
      headers: { Accept: 'application/json' },
    });
    if (!res.ok) throw new Error(`catalog.json: ${res.status}`);
    current = parseCatalog(await res.json());
    const bundles = current.namespaces.flatMap((ns) => languages.map((lang) => ({ ns, lang })));
    await Promise.all(
      bundles.map(async ({ ns, lang }) => {
        const r = await fetch(`${PACK_PATH}/locales/${lang}/${ns}.json`, {
          headers: { Accept: 'application/json' },
        });
        if (!r.ok) {
          console.warn(`Service pack has no ${lang}/${ns}.json (${r.status})`);
          return;
        }
        i18n.addResourceBundle(lang, ns, await r.json(), true, true);
      }),
    );
  } catch (e) {
    current = null;
    console.error('Service pack not loaded; running on core texts only.', e);
  }
}
