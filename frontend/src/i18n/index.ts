import i18n from 'i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import { initReactI18next } from 'react-i18next';

/**
 * Resources are assembled from ./locales/<lang>/<namespace>.json, so adding
 * a translated screen = dropping two JSON files (en + ar) into locales/.
 * The file name becomes the i18next namespace; components opt in with
 * `useTranslation('<namespace>')`. `common` (app shell, shared actions,
 * categories, backend activity names) is the default namespace.
 */
const files = import.meta.glob('./locales/*/*.json', { eager: true }) as Record<
  string,
  { default: Record<string, unknown> }
>;

const resources: Record<string, Record<string, Record<string, unknown>>> = {};
for (const [path, mod] of Object.entries(files)) {
  const m = path.match(/\.\/locales\/([^/]+)\/([^/]+)\.json$/);
  if (!m) continue;
  const [, lang, ns] = m;
  (resources[lang] ??= {})[ns] = mod.default;
}

export const SUPPORTED_LANGS = ['en', 'ar'] as const;
export type SupportedLang = (typeof SUPPORTED_LANGS)[number];

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources,
    fallbackLng: 'en',
    supportedLngs: [...SUPPORTED_LANGS],
    nonExplicitSupportedLngs: true,
    defaultNS: 'common',
    interpolation: { escapeValue: false },
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
      lookupLocalStorage: 'cib7.lang',
    },
  });

/** Keeps <html lang dir> in sync so CSS logical properties flip for Arabic. */
function applyDocumentDirection() {
  const lang = i18n.resolvedLanguage ?? 'en';
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
}

applyDocumentDirection();
i18n.on('languageChanged', applyDocumentDirection);

export default i18n;
