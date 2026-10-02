import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Landmark } from 'lucide-react';
import OfficialBanner from './OfficialBanner';
import LanguageSwitcher from './LanguageSwitcher';
import ThemeToggle from './ThemeToggle';
import SiteFooter from './SiteFooter';

/**
 * Frame for the pages people open from an email link without signing in
 * (co-owner confirmation, founder signature, payment). They carry the same
 * official banner, brand bar and footer as the portal, so a recipient can
 * tell the link leads to the real service, and the language and theme
 * switches, so an Arabic-speaking recipient can switch before reading.
 * There is no navigation: the capability token in the URL grants access to
 * this one page only.
 */
export default function PublicFrame({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="app app-public">
      <OfficialBanner />
      <div className="app-mesh" aria-hidden="true">
        <i />
        <i />
        <i />
        <i />
      </div>
      <header className="app-header">
        <div className="shell-wrap">
          <div className="app-bar glass">
            <span className="brand">
              <span className="brand-mark" aria-hidden="true">
                <Landmark />
              </span>
              <span className="brand-text">
                <span className="brand-name">{t('app.brandName')}</span>
                <span className="brand-sub">{t('app.brandSub')}</span>
              </span>
            </span>
            <div className="app-bar-end">
              <LanguageSwitcher />
              <ThemeToggle />
            </div>
          </div>
        </div>
      </header>
      <main className="app-main" id="main">
        {children}
      </main>
      <SiteFooter />
    </div>
  );
}
