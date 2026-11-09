import { Link } from 'react-router-dom';
import { RouteAccessibility } from '@/shared/ui/RouteAccessibility';

/** Shown for any path that no portal handles, instead of an empty page. */
export function NotFoundPage() {
  return <>
    <RouteAccessibility portal="Page not found" />
    <main id="main-content" className="p-6">
      <h1>Page not found</h1>
      <p>The page you asked for does not exist.</p>
      <Link to="/">Go to the Zentro home page</Link>
    </main>
  </>;
}
