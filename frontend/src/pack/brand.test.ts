import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { InvalidBrand, parseBrand, parseTokens, tokensCss, type Scheme } from './brand';

const BRANDING = resolve(__dirname, '../../../packs/reference/branding');
const CORE_TOKENS = resolve(__dirname, '../styles/tokens.css');
const CORE_LOCALES = resolve(__dirname, '../i18n/locales');

function json(path: string): Record<string, unknown> {
  return JSON.parse(readFileSync(path, 'utf-8'));
}

/** WCAG 2 contrast ratio of two #rrggbb colours. */
function contrast(a: string, b: string): number {
  const luminance = (hex: string) => {
    const [r, g, b] = [1, 3, 5].map((i) => {
      const c = parseInt(hex.slice(i, i + 2), 16) / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** A core token's value in one scheme, read from styles/tokens.css. */
function coreToken(scheme: Scheme, name: string): string {
  const css = readFileSync(CORE_TOKENS, 'utf-8');
  const start = css.indexOf(scheme === 'light' ? ':root {' : ":root[data-theme='dark'] {");
  const block = css.slice(start, css.indexOf('\n}', start));
  const value = block.match(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6});`))?.[1];
  if (!value) throw new Error(`no --${name} in the ${scheme} block`);
  return value;
}

describe('brand format v1 is strict', () => {
  const tokens = () => json(resolve(BRANDING, 'tokens.json'));
  const brand = () => json(resolve(BRANDING, 'brand.json'));

  it.each<[string, (t: Record<string, Record<string, unknown>>) => void]>([
    ['a core-only token', (t) => (t.light.danger = '#ff0000')],
    ['CSS instead of a hex colour', (t) => (t.light.primary = 'red; } body { display: none')],
    ['a short hex colour', (t) => (t.dark.primary = '#fff')],
    ['a font name with a quote', (t) => (t.fonts.body = "Sora', serif")],
    ['an unknown font token', (t) => (t.fonts.mono = 'Courier')],
    ['another version', (t) => ((t as Record<string, unknown>).version = 2)],
  ])('refuses tokens with %s', (_, change) => {
    const t = tokens() as Record<string, Record<string, unknown>>;
    change(t);
    expect(() => parseTokens(t)).toThrow(InvalidBrand);
  });

  it.each<[string, (b: Record<string, unknown>) => void]>([
    ['a logo outside the branding folder', (b) => (b.logo = { light: '../frontend/catalog.json' })],
    ['a logo that is a URL', (b) => (b.logo = { light: 'https://example.com/logo.svg' })],
    ['a favicon that is no image', (b) => (b.favicon = 'favicon.html')],
    ['an unknown key', (b) => (b.script = 'x')],
  ])('refuses a brand with %s', (_, change) => {
    const b = brand();
    change(b);
    expect(() => parseBrand(b)).toThrow(InvalidBrand);
  });

  it('writes only the tokens it was given, under the core selectors', () => {
    const css = tokensCss(parseTokens({ version: 1, dark: { primary: '#123456' } }));
    expect(css).toBe(":root[data-theme='dark'] {\n  --primary: #123456;\n}\n");
  });

  it('puts a brand font in front of the core fallback stack', () => {
    const css = tokensCss(parseTokens({ version: 1, fonts: { body: 'Inter' } }));
    expect(css).toContain("--font-body: 'Inter', 'Segoe UI', system-ui");
  });
});

describe('reference pack branding', () => {
  const tokens = parseTokens(json(resolve(BRANDING, 'tokens.json')));
  const brand = parseBrand(json(resolve(BRANDING, 'brand.json')));

  it('has every image brand.json names', () => {
    for (const file of [brand.logo?.light, brand.logo?.dark, brand.favicon]) {
      if (file) expect(existsSync(resolve(BRANDING, file)), file).toBe(true);
    }
  });

  it('has the brand texts in every language, with the core keys', () => {
    for (const lang of ['en', 'ar']) {
      const core = Object.keys(json(resolve(CORE_LOCALES, lang, 'brand.json'))).sort();
      const pack = Object.keys(json(resolve(BRANDING, 'locales', lang, 'brand.json'))).sort();
      expect(pack, `${lang}/brand.json`).toEqual(core);
    }
  });

  // WCAG AA for text: 4.5:1. primary-ink is text on a primary button;
  // primary is link and accent text on the page surface.
  it.each<Scheme>(['light', 'dark'])('meets WCAG AA contrast in the %s scheme', (scheme) => {
    const primary = tokens[scheme].primary ?? coreToken(scheme, 'primary');
    const ink = tokens[scheme]['primary-ink'] ?? coreToken(scheme, 'primary-ink');
    expect(contrast(primary, ink)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(primary, coreToken(scheme, 'surface'))).toBeGreaterThanOrEqual(4.5);
  });
});
