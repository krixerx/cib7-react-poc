import { createTheme, type Theme } from '@mui/material/styles';
import type { ColorScheme } from './colorScheme';

/**
 * MUI theme for the one place MUI still renders (the Incidents DataGrid).
 * MUI v5 computes hover and selection shades from hex values, so it cannot
 * take the CSS tokens directly; these hexes repeat `styles/tokens.css` and
 * must change with it. `fontFamily: inherit` keeps MUI text in the page font.
 */
const themes: Record<ColorScheme, Theme> = {
  light: createTheme({
    palette: {
      mode: 'light',
      primary: { main: '#0b57c9' },
      error: { main: '#c4282d' },
      background: { default: '#f5f7fa', paper: '#ffffff' },
      text: { primary: '#0b1b2f', secondary: '#4c5d70' },
      divider: '#dbe3ec',
    },
    typography: { fontFamily: 'inherit' },
    shape: { borderRadius: 10 },
  }),
  dark: createTheme({
    palette: {
      mode: 'dark',
      primary: { main: '#6aa5ff' },
      error: { main: '#ff8a8f' },
      background: { default: '#07101c', paper: '#0e1a2a' },
      text: { primary: '#e7eef7', secondary: '#9aabbf' },
      divider: '#1f3047',
    },
    typography: { fontFamily: 'inherit' },
    shape: { borderRadius: 10 },
  }),
};

export function muiTheme(scheme: ColorScheme): Theme {
  return themes[scheme];
}
