// Sets <html data-theme> before the bundle loads so the first paint uses the
// viewer's colour scheme. Mirrors src/theme/colorScheme.ts. A file rather than
// an inline script because the CSP allows scripts from 'self' only.
(function () {
  var theme = null;
  try {
    theme = localStorage.getItem('ereg-theme');
  } catch {
    theme = null;
  }
  if (theme !== 'light' && theme !== 'dark') {
    theme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  document.documentElement.dataset.theme = theme;
})();
