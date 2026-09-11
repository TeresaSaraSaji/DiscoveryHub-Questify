import { Routes } from '@angular/router';

/**
 * Seven pages, lazily loaded, and **no resolvers**.
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
  // Opening a matter is its own page rather than a panel on the list: it is linkable, the back
  // button cancels it, and the list is not permanently half-covered by a form used once per
  // matter. Listed before 'cases' to read as a pair, not out of necessity — a childless route
  // never matches a URL with segments left over, so 'cases' cannot swallow 'cases/new'.
  {
    path: 'cases/new',
    title: 'Open a case · DiscoveryHub',
    loadComponent: () => import('./pages/cases/new-case-page').then((m) => m.NewCasePage),
  },
  {
    path: 'cases',
    title: 'Cases & Legal Hold · DiscoveryHub',
    loadComponent: () => import('./pages/cases/cases-page').then((m) => m.CasesPage),
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
    path: 'exports',
    title: 'Evidence Exports · DiscoveryHub',
    loadComponent: () => import('./pages/exports/exports-page').then((m) => m.ExportsPage),
  },
  {
    path: 'audit',
    title: 'Audit Trail · DiscoveryHub',
    loadComponent: () => import('./pages/audit/audit-page').then((m) => m.AuditPage),
  },
  // Exports and the trail shared one page until they were split. Kept so a bookmark, or a link
  // in someone's notes, still lands somewhere sensible rather than on the catch-all.
  { path: 'export-audit', pathMatch: 'full', redirectTo: 'exports' },
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: '**', redirectTo: 'home' },
];
