import { useTranslation } from 'react-i18next';
import BrandMark from './BrandMark';

/** Footer shared by both portals: the brand and the demo-environment note. */
export default function SiteFooter() {
  const { t } = useTranslation();

  return (
    <footer className="site-footer">
      <div className="shell-wrap site-footer-legal">
        <span className="brand">
          <BrandMark />
          <span className="brand-name">{t('brand:name')}</span>
        </span>
        <span>{t('footer.demoNote')}</span>
      </div>
    </footer>
  );
}
