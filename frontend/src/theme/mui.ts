import { createTheme } from '@mui/material/styles';

/**
 * App-level MUI theme. MUI complements TEDI where TEDI has no component
 * (complex data tables, and later calendar/wizard), so the palette is mapped
 * to the portal brand tokens from styles.css rather than MUI's default blue.
 * `fontFamily: inherit` keeps MUI text in the surrounding page font instead
 * of pulling in Roboto a second way.
 */
export const muiTheme = createTheme({
  palette: {
    primary: { main: '#005c4c' },
    error: { main: '#dc2626' },
  },
  typography: { fontFamily: 'inherit' },
});
