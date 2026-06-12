import js from '@eslint/js';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist/', 'node_modules/', 'public/env.js'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  jsxA11y.flatConfigs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    languageOptions: {
      globals: { ...globals.browser },
    },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
      // autoFocus here is deliberate focus management — moving focus into a
      // just-revealed input after a user action (e.g. opening the reject-reason
      // field), not page-load autofocus, which is what the rule targets.
      'jsx-a11y/no-autofocus': 'off',
      // Form labels nest control and text deeper than the default search depth
      // (label > input + span.radio-body > span.radio-label > text).
      'jsx-a11y/label-has-associated-control': ['error', { depth: 5 }],
    },
  },
  {
    files: ['vite.config.ts'],
    languageOptions: {
      globals: { ...globals.node },
    },
  },
);
