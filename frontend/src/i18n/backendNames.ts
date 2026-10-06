import type { TFunction } from 'i18next';

/**
 * The engine returns English display names (process, task and activity names
 * baked into the BPMN models). The frontend cannot ask the engine for Arabic,
 * so the service pack ships a `names` namespace that maps each English name
 * to its translation (`/pack/locales/<lang>/names.json`, generated from the
 * BPMN `name=` attributes). Unknown names (e.g. free text from civil servants)
 * fall through untouched.
 *
 * The English name is the key itself, so key and namespace separators are
 * switched off for the lookup: a name may contain dots or colons.
 */
export function translateBackendName(t: TFunction, name: string | null | undefined): string {
  if (!name) return '';
  return t(name, { ns: 'names', keySeparator: false, nsSeparator: false, defaultValue: name });
}
