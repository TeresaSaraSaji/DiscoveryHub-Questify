import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ServiceStatus } from './shared/service-status';

interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly hint: string;
  /** 16×16 stroke path. Small on purpose — a nav icon is a hint, not a picture. */
  readonly icon: string;
}

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, ServiceStatus],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly nav: readonly NavItem[] = [
    {
      path: '/retention',
      label: 'Retention & Disposition',
      hint: 'Retention policy, sweeps and the ledger — P2.2',
      icon: 'M2 4h12M2 8h12M2 12h7',
    },
    {
      path: '/cases',
      label: 'Case & Hold',
      hint: 'Legal holds and what they have protected — P4, P2.2',
      icon: 'M6 3h4v2h3v8H3V5h3zm0 2h4V4H6z',
    },
    {
      path: '/export-audit',
      label: 'Export & Audit',
      hint: 'Evidence packages and the chain of custody — P5',
      icon: 'M8 2v7m0 0 3-3m-3 3L5 6M3 11v2h10v-2',
    },
  ];
}
