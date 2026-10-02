import { useTranslation } from 'react-i18next';
import { Moon, Sun } from 'lucide-react';
import { toggleColorScheme, useColorScheme } from '../theme/colorScheme';

/** Light/dark switch in the header; the icon shows the scheme it switches to. */
export default function ThemeToggle() {
  const { t } = useTranslation();
  const scheme = useColorScheme();
  const label = scheme === 'dark' ? t('theme.toLight') : t('theme.toDark');

  return (
    <button
      type="button"
      className="icon-btn"
      onClick={toggleColorScheme}
      aria-label={label}
      title={label}
    >
      {scheme === 'dark' ? <Sun aria-hidden="true" /> : <Moon aria-hidden="true" />}
    </button>
  );
}
