import { useTranslation } from 'react-i18next';
import { SUPPORTED_LANGS } from '../i18n';

/**
 * EN ⇄ AR toggle in the header. Switching also flips <html dir>, which the
 * stylesheet's logical properties pick up — no separate RTL stylesheet.
 */
export default function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  const current = i18n.resolvedLanguage ?? 'en';

  return (
    <span className="lang-switch" role="group" aria-label={t('language.label')}>
      {SUPPORTED_LANGS.map((lang) => (
        <button
          key={lang}
          type="button"
          className={`lang-switch-btn${current === lang ? ' active' : ''}`}
          // Each label renders in its own script regardless of UI language.
          lang={lang}
          onClick={() => i18n.changeLanguage(lang)}
          aria-pressed={current === lang}
        >
          {t(`language.${lang}`)}
        </button>
      ))}
    </span>
  );
}
