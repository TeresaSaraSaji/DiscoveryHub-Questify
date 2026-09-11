import { Routes } from '@angular/router';

/**
 * Five pages, lazily loaded, and **no resolvers**.
 *
 * A resolver would fetch before activating the route, which is the one thing this UI must not do:
 * six services on six ports, and a route that waited on any of them would refuse to open a page
 * whose other panels work perfectly. Every request in this app is issued by the panel that needs
 * it, after the page is on screen.
 */
export const routes: Routes = [
  {
    path: 'home',
    title: 'DiscoveryHub',
    loadComponent: () => import('./pages/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'search',
    title: 'Search · DiscoveryHub',
    loadComponent: () => import('./pages/search/search-page').then((m) => m.SearchPage),
  },
  {
    path: 'cases',
    title: 'Cases & Legal Hold · DiscoveryHub',
    loadComponent: () => import('./pages/cases/cases-page').then((m) => m.CasesPage),
  },
  {
    // Before nothing, but after 'cases': one case on its own page, and a URL worth sending.
    path: 'cases/:caseId',
    title: 'Case · DiscoveryHub',
    loadComponent: () => import('./pages/cases/case-detail').then((m) => m.CaseDetail),
  },
  {
    path: 'retention',
    title: 'Retention & Disposition · DiscoveryHub',
    loadComponent: () =>
      import('./pages/retention-disposition/retention-disposition').then(
        (m) => m.RetentionDisposition,
      ),
  },
  {
    path: 'export-audit',
    title: 'Exports & Audit · DiscoveryHub',
    loadComponent: () => import('./pages/export-audit/export-audit').then((m) => m.ExportAudit),
  },
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: '**', redirectTo: 'home' },
];
