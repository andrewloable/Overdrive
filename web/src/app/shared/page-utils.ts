import { Signal, WritableSignal } from '@angular/core';

/**
 * Loads a resource into a signal, managing loading/error state.
 *
 * Usage:
 *   loadResource(this.loading, this.error, () => client.fetchData()).then(v => this.data.set(v ?? fallback));
 */
export async function loadResource<T>(
  loading: WritableSignal<boolean>,
  error: WritableSignal<string>,
  fetch: () => Promise<T>,
): Promise<T | null> {
  loading.set(true);
  error.set('');
  try {
    return await fetch();
  } catch (e) {
    error.set(e instanceof Error ? e.message : 'Failed to load');
    return null;
  } finally {
    loading.set(false);
  }
}

/**
 * Show a temporary status message for 2 seconds, then clear it.
 */
export function toast(signal: WritableSignal<string>, msg: string, durationMs = 2000): void {
  signal.set(msg);
  setTimeout(() => signal.set(''), durationMs);
}
