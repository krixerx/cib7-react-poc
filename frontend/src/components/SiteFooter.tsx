import { useTranslation } from 'react-i18next';
import { Landmark } from 'lucide-react';

/**
 * Footer shared by both portals. The support block names the company that
 * builds and supports the demo, with its public contact page; the demo has no
 * helpline of its own.
 */
export default function SiteFooter() {
  const { t } = useTranslation();

  return (
    <footer className="site-footer">
      <div className="shell-wrap site-footer-grid">
        <div className="site-footer-brand">
          <span className="brand">
            <span className="brand-mark" aria-hidden="true">
              <Landmark />
            </span>
            <span className="brand-name">{t('app.brandName')}</span>
          </span>
          <p>{t('footer.about')}</p>
        </div>
        <div>
          <h2 className="site-footer-h">{t('footer.supportTitle')}</h2>
          <p className="site-footer-org">
            <strong>Tulepaak OÜ</strong>
            <span>{t('footer.registryCode', { code: '12065884' })}</span>
            <span>{t('footer.location')}</span>
          </p>
        </div>
        <div>
          <h2 className="site-footer-h">{t('footer.contactTitle')}</h2>
          <ul className="site-footer-links">
            <li>
              <a href="https://www.tulepaak.ee/contact" target="_blank" rel="noopener noreferrer">
                {t('footer.contactForm')}
              </a>
            </li>
            <li>
              <a
                href="https://www.linkedin.com/company/109908586"
                target="_blank"
                rel="noopener noreferrer"
              >
                LinkedIn
              </a>
            </li>
            <li>
              <a
                href="https://ariregister.rik.ee/eng/company/12065884"
                target="_blank"
                rel="noopener noreferrer"
              >
                {t('footer.businessRegister')}
              </a>
            </li>
          </ul>
        </div>
      </div>
      <div className="shell-wrap site-footer-legal">
        <span>{t('footer.demoNote')}</span>
        <span>© {new Date().getFullYear()} Tulepaak OÜ</span>
      </div>
    </footer>
  );
}
