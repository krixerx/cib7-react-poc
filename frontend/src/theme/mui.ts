import { createTheme, type Theme } from '@mui/material/styles';
import type { ColorScheme } from './colorScheme';

/**
 * MUI theme for the one place MUI still renders (the Incidents DataGrid).
 * MUI v5 computes hover and selection shades from concrete colour values, so
 * it cannot take `var(--primary)`; instead the theme reads the tokens'
 * resolved values from <html> for the scheme that is on it. That picks up a
 * service pack's brand colours too, with no hex repeated here.
 * `fontFamily: inherit` keeps MUI text in the page font.
 */
const PALETTE = {
  primary: '--primary',
  error: '--danger',
  backgroundDefault: '--bg',
  backgroundPaper: '--surface',
  textPrimary: '--text',
  textSecondary: '--muted',
  divider: '--border',
} as const;

const cache = new Map<ColorScheme, Theme>();

/** The token's value now on <html>, or undefined when no style sheet sets it (tests). */
function token(name: string): string | undefined {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || undefined;
}

/**
 * The theme for `scheme`. Call it while that scheme is on <html data-theme>
 * (useColorScheme() reads exactly that), so the values read are that
 * scheme's; the result is kept per scheme because the pack's tokens are
 * applied once, before the first render.
 */
export function muiTheme(scheme: ColorScheme): Theme {
  const cached = cache.get(scheme);
  if (cached) return cached;
  const v = Object.fromEntries(
    Object.entries(PALETTE).map(([key, name]) => [key, token(name)]),
  ) as Record<keyof typeof PALETTE, string | undefined>;
  const theme = createTheme({
    palette: {
      mode: scheme,
      ...(v.primary && { primary: { main: v.primary } }),
      ...(v.error && { error: { main: v.error } }),
      background: {
        ...(v.backgroundDefault && { default: v.backgroundDefault }),
        ...(v.backgroundPaper && { paper: v.backgroundPaper }),
      },
      text: {
        ...(v.textPrimary && { primary: v.textPrimary }),
        ...(v.textSecondary && { secondary: v.textSecondary }),
      },
      ...(v.divider && { divider: v.divider }),
    },
    typography: { fontFamily: 'inherit' },
    shape: { borderRadius: 10 },
  });
  cache.set(scheme, theme);
  return theme;
}
