import { useSyncExternalStore } from 'react';

export type ColorScheme = 'light' | 'dark';

const STORAGE_KEY = 'ereg-theme';
const media = window.matchMedia('(prefers-color-scheme: dark)');
const listeners = new Set<() => void>();

function storedScheme(): ColorScheme | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === 'light' || v === 'dark' ? v : null;
  } catch {
    return null;
  }
}

function resolve(): ColorScheme {
  return storedScheme() ?? (media.matches ? 'dark' : 'light');
}

function apply() {
  document.documentElement.dataset.theme = resolve();
  listeners.forEach((l) => l());
}

media.addEventListener('change', () => {
  if (!storedScheme()) apply();
});

/**
 * Flips the colour scheme and remembers the choice. Until a viewer picks one,
 * the OS preference decides and keeps deciding as it changes;
 * public/theme-init.js applies the same rule before the bundle loads so the
 * first paint is already in the right scheme.
 */
export function toggleColorScheme() {
  const next: ColorScheme = resolve() === 'dark' ? 'light' : 'dark';
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // Private mode or blocked storage: the toggle still works for this page view.
    document.documentElement.dataset.theme = next;
    listeners.forEach((l) => l());
    return;
  }
  apply();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** The scheme currently on <html data-theme>, for components that cannot read CSS tokens (MUI). */
export function useColorScheme(): ColorScheme {
  return useSyncExternalStore(subscribe, () =>
    document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light',
  );
}

apply();
