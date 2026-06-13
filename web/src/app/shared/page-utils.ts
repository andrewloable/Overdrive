import { Signal, WritableSignal } from '@angular/core';

/**
 * Show a temporary status message for 2 seconds, then clear it.
 */
export function toast(signal: WritableSignal<string>, msg: string, durationMs = 2000): void {
  signal.set(msg);
  setTimeout(() => signal.set(''), durationMs);
}
