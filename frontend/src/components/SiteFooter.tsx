import { useTranslation } from 'react-i18next';
import { Landmark } from 'lucide-react';

/** Footer shared by both portals: the brand and the demo-environment note. */
export default function SiteFooter() {
  const { t } = useTranslation();

  return (
    <footer className="site-footer">
      <div className="shell-wrap site-footer-legal">
        <span className="brand">
          <span className="brand-mark" aria-hidden="true">
            <Landmark />
          </span>
          <span className="brand-name">{t('app.brandName')}</span>
        </span>
        <span>{t('footer.demoNote')}</span>
      </div>
    </footer>
  );
}
