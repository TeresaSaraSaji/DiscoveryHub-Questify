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
      path: '/home',
      label: 'Home',
      hint: 'What is here, and what is happening to it',
      icon: 'M2 7l6-4.5L14 7v6.5H2z',
    },
    {
      path: '/search',
      label: 'Search',
      hint: 'Full-text across the corpus — P3',
      icon: 'M7 2.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9zM10.5 10.5 14 14',
    },
    {
      path: '/cases',
      label: 'Cases & Holds',
      hint: 'Matters, evidence and legal holds — P4',
      icon: 'M6 3h4v2h3v8H3V5h3z',
    },
    {
      path: '/retention',
      label: 'Retention & Disposition',
      hint: 'Retention policy, sweeps and the ledger — P2.2',
      icon: 'M2 4h12M2 8h12M2 12h7',
    },
    {
      path: '/exports',
      label: 'Exports',
      hint: 'Evidence packages — P5',
      icon: 'M8 2v7m0 0 3-3m-3 3L5 6M3 11v2h10v-2',
    },
    {
      path: '/audit',
      label: 'Audit Trail',
      hint: 'The chain of custody, across every service — P5',
      icon: 'M4 2h8v12H4zM6 5h4M6 8h4M6 11h2',
    },
  ];
}
