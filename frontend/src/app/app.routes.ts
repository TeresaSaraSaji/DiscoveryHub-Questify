import { Routes } from '@angular/router';

/**
 * Three pages, lazily loaded, and **no resolvers**.
 *
 * A resolver would fetch before activating the route, which is the one thing this UI must not do:
 * P4 and P5 are not running, and a route that waited on them would refuse to open a page whose
 * other panels work perfectly. Every request in this app is issued by the panel that needs it,
 * after the page is on screen.
 */
export const routes: Routes = [
  {
    path: 'retention',
    title: 'Retention & Disposition · DiscoveryHub',
    loadComponent: () =>
      import('./pages/retention-disposition/retention-disposition').then(
        (m) => m.RetentionDisposition,
      ),
  },
  {
    path: 'cases',
    title: 'Case & Hold · DiscoveryHub',
    loadComponent: () => import('./pages/cases/cases-page').then((m) => m.CasesPage),
  },
  {
    path: 'export-audit',
    title: 'Export & Audit · DiscoveryHub',
    loadComponent: () => import('./pages/export-audit/export-audit').then((m) => m.ExportAudit),
  },
  { path: '', pathMatch: 'full', redirectTo: 'retention' },
  { path: '**', redirectTo: 'retention' },
];
