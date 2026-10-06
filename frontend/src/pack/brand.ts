import type { i18n as I18n } from 'i18next';
import { PACK_PATH } from './catalog';

/**
 * The service pack's branding: logo, favicon, the brand's colour and font
 * tokens and its texts (`brand` namespace: portal name and subtitle). The
 * core ships defaults for all of it (styles/tokens.css, the Landmark mark,
 * i18n/locales/<lang>/brand.json), so a pack overrides only what it names.
 *
 * Read once before the first render from `/pack/branding/`, like the catalog,
 * so a rebrand needs no core rebuild. Part of the platform API (brand format
 * v1, token names below). Strict: an unknown key or a value outside its
 * pattern refuses that file as a whole, and the core defaults stay. Colours
 * are hex only and fonts are family names only, so nothing a pack writes can
 * become CSS beyond a value.
 */

/** The tokens a pack may set, per colour scheme. Everything else stays core. */
export const BRAND_COLOR_TOKENS = [
  'primary',
  'primary-hover',
  'primary-ink',
  'primary-soft',
  'primary-soft-border',
  'mesh-1',
  'mesh-2',
  'mesh-3',
  'mesh-4',
  'banner-bg',
  'banner-fg',
  'banner-strong',
] as const;
export type BrandColorToken = (typeof BRAND_COLOR_TOKENS)[number];

export const BRAND_FONT_TOKENS = ['display', 'body'] as const;
export type BrandFontToken = (typeof BRAND_FONT_TOKENS)[number];

export type Scheme = 'light' | 'dark';

export interface Tokens {
  version: 1;
  light: Partial<Record<BrandColorToken, string>>;
  dark: Partial<Record<BrandColorToken, string>>;
  fonts: Partial<Record<BrandFontToken, string>>;
}

export interface Brand {
  version: 1;
  /** Image files in /pack/branding/; `dark` falls back to `light`. */
  logo?: { light: string; dark?: string };
  favicon?: string;
}

export const BRANDING_PATH = `${PACK_PATH}/branding`;

const HEX = /^#[0-9a-fA-F]{6}$/;
const FONT = /^[A-Za-z0-9][A-Za-z0-9 ]{0,39}$/;
const IMAGE = /^[a-z0-9][a-z0-9-]{0,62}\.(svg|png|webp)$/;

/** The core font stacks a brand family is put in front of (styles/tokens.css). */
const FONT_FALLBACK: Record<BrandFontToken, string> = {
  display: "'Segoe UI', system-ui, sans-serif",
  body: "'Segoe UI', system-ui, -apple-system, sans-serif",
};

export class InvalidBrand extends Error {}

function fail(message: string): never {
  throw new InvalidBrand(message);
}

function object(value: unknown, what: string, allowed: readonly string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    fail(`${what} must be an object`);
  }
  const unknownKey = Object.keys(value).find((k) => !allowed.includes(k));
  if (unknownKey) fail(`${what} has an unknown key '${unknownKey}'`);
  return value as Record<string, unknown>;
}

function matching(value: unknown, pattern: RegExp, what: string): string {
  if (typeof value !== 'string' || !pattern.test(value)) fail(`${what} is invalid`);
  return value;
}

/** Checks tokens.json against brand format v1. */
export function parseTokens(raw: unknown): Tokens {
  const root = object(raw, 'tokens', ['$comment', 'version', 'light', 'dark', 'fonts']);
  if (root.version !== 1) fail('version must be 1');
  const colors = (scheme: Scheme) => {
    const out: Partial<Record<BrandColorToken, string>> = {};
    if (root[scheme] === undefined) return out;
    for (const [name, value] of Object.entries(object(root[scheme], scheme, BRAND_COLOR_TOKENS))) {
      out[name as BrandColorToken] = matching(value, HEX, `${scheme}.${name}`);
    }
    return out;
  };
  const fonts: Tokens['fonts'] = {};
  if (root.fonts !== undefined) {
    for (const [name, value] of Object.entries(object(root.fonts, 'fonts', BRAND_FONT_TOKENS))) {
      fonts[name as BrandFontToken] = matching(value, FONT, `fonts.${name}`);
    }
  }
  return { version: 1, light: colors('light'), dark: colors('dark'), fonts };
}

/** Checks brand.json against brand format v1. */
export function parseBrand(raw: unknown): Brand {
  const root = object(raw, 'brand', ['$comment', 'version', 'logo', 'favicon']);
  if (root.version !== 1) fail('version must be 1');
  const brand: Brand = { version: 1 };
  if (root.logo !== undefined) {
    const logo = object(root.logo, 'logo', ['light', 'dark']);
    brand.logo = { light: matching(logo.light, IMAGE, 'logo.light') };
    if (logo.dark !== undefined) brand.logo.dark = matching(logo.dark, IMAGE, 'logo.dark');
  }
  if (root.favicon !== undefined) brand.favicon = matching(root.favicon, IMAGE, 'favicon');
  return brand;
}

/**
 * The CSS that lays a pack's tokens over the core ones. Same selectors as
 * styles/tokens.css, appended after it, so equal specificity and the later
 * rule wins.
 */
export function tokensCss(tokens: Tokens): string {
  const block = (selector: string, decls: string[]) =>
    decls.length ? `${selector} {\n${decls.map((d) => `  ${d};`).join('\n')}\n}\n` : '';
  const colors = (scheme: Scheme) =>
    Object.entries(tokens[scheme]).map(([name, value]) => `--${name}: ${value}`);
  const fonts = Object.entries(tokens.fonts).map(
    ([name, family]) => `--font-${name}: '${family}', ${FONT_FALLBACK[name as BrandFontToken]}`,
  );
  return (
    block(':root', [...colors('light'), ...fonts]) +
    block(":root[data-theme='dark']", colors('dark'))
  );
}

let current: Brand | null = null;

/** The loaded brand.json, or null when the pack has none (core defaults apply). */
export function brand(): Brand | null {
  return current;
}

/** The logo for a colour scheme, as a URL, or null for the core mark. */
export function logoUrl(scheme: Scheme): string | null {
  const logo = current?.logo;
  if (!logo) return null;
  return `${BRANDING_PATH}/${scheme === 'dark' ? (logo.dark ?? logo.light) : logo.light}`;
}

async function fetchJson(path: string): Promise<unknown | null> {
  const res = await fetch(`${BRANDING_PATH}/${path}`, { headers: { Accept: 'application/json' } });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  return res.json();
}

/** Runs one branding step; a failure is logged and leaves the core default. */
async function step(what: string, run: () => Promise<void>): Promise<void> {
  try {
    await run();
  } catch (e) {
    console.error(`Service pack ${what} not applied; using the core default.`, e);
  }
}

/**
 * Reads and applies the pack's branding before the first render. Never
 * throws: each of tokens, brand and texts is applied on its own, and a
 * missing or invalid one keeps the core default.
 */
export async function loadBrand(i18n: I18n, languages: readonly string[]): Promise<void> {
  await Promise.all([
    step('tokens.json', async () => {
      const raw = await fetchJson('tokens.json');
      if (raw === null) return;
      const style = document.createElement('style');
      style.id = 'pack-theme';
      style.textContent = tokensCss(parseTokens(raw));
      document.head.appendChild(style);
    }),
    step('brand.json', async () => {
      const raw = await fetchJson('brand.json');
      if (raw === null) return;
      current = parseBrand(raw);
      if (current.favicon) {
        const link = document.createElement('link');
        link.rel = 'icon';
        link.href = `${BRANDING_PATH}/${current.favicon}`;
        document.head.appendChild(link);
      }
    }),
    ...languages.map((lang) =>
      step(`${lang} brand texts`, async () => {
        const texts = await fetchJson(`locales/${lang}/brand.json`);
        if (texts !== null) i18n.addResourceBundle(lang, 'brand', texts, true, true);
      }),
    ),
  ]);
  const title = () => {
    document.title = i18n.t('brand:name');
  };
  title();
  i18n.on('languageChanged', title);
}
