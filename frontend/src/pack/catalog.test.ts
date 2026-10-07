import i18next from 'i18next';
import { describe, expect, it } from 'vitest';
import { translateBackendName } from '../i18n/backendNames';
import { InvalidCatalog, parseCatalog } from './catalog';

/** A catalog that keeps format v1; each case below breaks it in one place. */
function valid(): Record<string, unknown> {
  return {
    version: 1,
    namespaces: ['catalog', 'names', 'toy-details'],
    services: { toyRegistration: { category: 'other', issuer: 'toy-authority' } },
    issuers: { 'toy-authority': { tone: 'primary' } },
  };
}

describe('catalog format v1 is strict', () => {
  it('accepts a valid catalog', () => {
    expect(parseCatalog(valid()).services.toyRegistration).toEqual({
      category: 'other',
      issuer: 'toy-authority',
    });
  });

  it.each<[string, (c: Record<string, unknown>) => void]>([
    ['an unknown key', (c) => (c.script = 'x')],
    ['another version', (c) => (c.version = 2)],
    ['a namespace that is no id', (c) => (c.namespaces = ['catalog', 'names', '../common'])],
    ['no names namespace', (c) => (c.namespaces = ['catalog'])],
    [
      'an unknown category',
      (c) =>
        ((c.services as Record<string, { category: string }>).toyRegistration.category = 'space'),
    ],
    [
      'an issuer that does not exist',
      (c) => ((c.services as Record<string, { issuer: string }>).toyRegistration.issuer = 'nobody'),
    ],
    [
      'an unknown tone',
      (c) => ((c.issuers as Record<string, { tone: string }>)['toy-authority'].tone = '#ff0000'),
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
