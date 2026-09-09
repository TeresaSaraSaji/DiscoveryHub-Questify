import { httpResource } from '@angular/common/http';
import { Injectable, Signal } from '@angular/core';
import { baseUrl } from './api-config';
import { ArchiveStats, ArchivedMessage, EMPTY_PAGE, Page } from './models';

/**
 * P2 Archive on :8082 — the system of record.
 *
 * Read-only from here. The archive is written by the Kafka consumer and nothing else, and a UI
 * that could POST a message into it would be a second writer to the system of record (NFR-1).
 */
@Injectable({ providedIn: 'root' })
export class ArchiveApi {
  private url(path: string): string {
    return `${baseUrl('p2')}${path}`;
  }

  /** `totalMessages` and `onHold` — the archive's own count of the hold flag P2 mirrors. */
  statsResource() {
    return httpResource<ArchiveStats>(() => this.url('/stats'));
  }

  messagesResource(custodianId: Signal<string>, page: Signal<number>, size = 10) {
    return httpResource<Page<ArchivedMessage>>(
      () => {
        const custodian = custodianId().trim();
        return {
          url: this.url('/messages'),
          params: { page: page(), size, ...(custodian ? { custodianId: custodian } : {}) },
        };
      },
      { defaultValue: EMPTY_PAGE },
    );
  }

  messageResource(messageId: Signal<string>) {
    return httpResource<ArchivedMessage>(() => {
      const id = messageId().trim();
      return id ? this.url(`/messages/${encodeURIComponent(id)}`) : undefined;
    });
  }
}
