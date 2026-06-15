import i18n from './index';

/**
 * BCP-47 locales for Intl formatting, per UI language. Arabic uses the Omani
 * locale but pins Western (Latin) digits — the convention on Omani government
 * portals — instead of Eastern Arabic numerals.
 */
const FORMAT_LOCALES: Record<string, string> = {
  en: 'en-GB',
  ar: 'ar-OM-u-nu-latn',
};

export function uiLocale(): string {
  return FORMAT_LOCALES[i18n.resolvedLanguage ?? 'en'] ?? FORMAT_LOCALES.en;
}

export function formatDate(iso: string | Date, opts?: Intl.DateTimeFormatOptions): string {
  const d = typeof iso === 'string' ? new Date(iso) : iso;
  return d.toLocaleDateString(
    uiLocale(),
    opts ?? { day: 'numeric', month: 'short', year: 'numeric' },
  );
}

export function formatDateTime(iso: string | Date, opts?: Intl.DateTimeFormatOptions): string {
  const d = typeof iso === 'string' ? new Date(iso) : iso;
  return d.toLocaleString(
    uiLocale(),
    opts ?? { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' },
  );
}

export function formatCurrency(amount: number, currency = 'EUR'): string {
  return amount.toLocaleString(uiLocale(), { style: 'currency', currency });
}

export function formatNumber(n: number, opts?: Intl.NumberFormatOptions): string {
  return n.toLocaleString(uiLocale(), opts);
}
