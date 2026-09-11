import { DestroyRef, Injectable } from '@angular/core';

const KEY = 'discoveryhub:cases-changed';

/**
 * "A case was opened" — told to the *other* tabs of this browser.
 *
 * Opening a matter now lands it in a second tab, which leaves the tab that was showing the case
 * list holding a list that is one row out of date and no way to know it. There is no server push
 * in this system to fix that with: P4 is plain REST, every read in this UI is a `httpResource`
 * the panel issues for itself, and polling the case list on a timer to catch an event the browser
 * already knows about would be a poor trade.
 *
 * `localStorage` is the notification channel because its `storage` event has exactly the right
 * shape: it fires in every *same-origin tab except the one that wrote*, which is precisely the
 * set of tabs that need to reload. `BroadcastChannel` would do as well; `localStorage` is chosen
 * because it degrades to doing nothing rather than throwing where it is unavailable (a Safari
 * private window denies writes), and a missed list refresh must never break opening a case.
 *
 * The value written is a timestamp and nothing else. It is a nudge to re-read, not a payload —
 * the receiving tab reloads its resources and gets the truth from P4, so two tabs cannot disagree
 * about what a case contains.
 */
@Injectable({ providedIn: 'root' })
export class CaseBroadcast {
  /** Tell other tabs that the case list has changed under them. */
  announce(): void {
    try {
      localStorage.setItem(KEY, String(Date.now()));
    } catch {
      // Storage denied or full. The other tab keeps its stale list until someone reloads it,
      // which is a worse list, not a broken one.
    }
  }

  /**
   * Run `handler` whenever another tab announces, until `destroyRef` fires.
   *
   * The lifetime is a parameter rather than an `inject(DestroyRef)` in here, so that this works
   * the same wherever it is called from: a listener on `window` that outlives its component
   * leaks, and that is not a thing to leave depending on whether the caller happened to be in an
   * injection context.
   */
  listen(destroyRef: DestroyRef, handler: () => void): void {
    const onStorage = (event: StorageEvent) => {
      // Null key is `localStorage.clear()`, which says nothing about cases.
      if (event.key === KEY) {
        handler();
      }
    };
    window.addEventListener('storage', onStorage);
    destroyRef.onDestroy(() => window.removeEventListener('storage', onStorage));
  }
}
