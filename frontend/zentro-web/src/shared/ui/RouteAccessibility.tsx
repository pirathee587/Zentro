import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';

const pageNames: [RegExp, string][] = [
  [/\/send$/, 'Send a parcel'], [/\/history$/, 'Delivery history'], [/\/receipts$/, 'Receipts'],
  [/\/support$/, 'Support'], [/\/api-credentials$/, 'API credentials'], [/\/webhooks$/, 'Webhooks'],
  [/\/queue$/, 'Dispatch queue'], [/\/workload$/, 'Courier workload'], [/\/zones-pricing$/, 'Zones and pricing'],
  [/\/users$/, 'Users'], [/\/merchant-approvals$/, 'Merchant applications'], [/\/couriers$/, 'Couriers'],
  [/\/integration-attempts$/, 'Integration attempts'], [/\/reports$/, 'Reports'], [/\/profile$/, 'Courier profile'],
  [/\/deliveries\//, 'Delivery details'], [/\/track(?:\/|$)/, 'Track a parcel'],
  [/\/login$/, 'Sign in'], [/\/register(?:\/|$)|\/signup$/, 'Register'],
];

/** Presentation only: fixed titles never include account names, tracking IDs or secrets. */
export function RouteAccessibility({ portal }: { portal: string }) {
  const { pathname } = useLocation();
  useEffect(() => {
    document.title = `${pageNames.find(([pattern]) => pattern.test(pathname))?.[1] ?? portal} | Zentro`;
    const main = document.querySelector('main');
    if (main) {
      if (!main.id) main.id = 'main-content';
      main.tabIndex = -1;
      main.focus({ preventScroll: true });
    }
  }, [pathname, portal]);
  // The landing page has an existing #home anchor, preserved for its internal navigation.
  return <a className="skip-to-content" href={pathname === '/' ? '#home' : '#main-content'}>Skip to main content</a>;
}
