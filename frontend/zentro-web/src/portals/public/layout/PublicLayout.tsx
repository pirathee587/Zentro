import { Link, Outlet, useLocation } from 'react-router-dom';
import { AuthIllustration } from '../assets/AuthIllustration';
import { RouteAccessibility } from '@/shared/ui/RouteAccessibility';
import { ZentroLogo } from '@/components/ui/ZentroLogo';

export function PublicLayout() {
  const { pathname } = useLocation();
  if (pathname === '/') return <><RouteAccessibility portal="Zentro home" /><Outlet /></>;
  if (
    pathname === '/register' ||
    pathname === '/register/customer' ||
    pathname === '/register/merchant' ||
    pathname === '/signup' ||
    pathname === '/login' ||
    pathname === '/staff/login' ||
    pathname === '/merchant/login' ||
    pathname === '/forgot-password'
  ) {
    return <><RouteAccessibility portal="Sign in" /><main id="main-content"><Outlet /></main></>;
  }
  if (pathname.startsWith('/track')) return <><RouteAccessibility portal="Track a parcel" /><main id="main-content"><Outlet /></main></>;
  return (
    <>
      <RouteAccessibility portal="Sign in" />
      <main className="auth-page">
        <section className="auth-art" aria-label="Thoughtful delivery in Sri Lanka">
          <Link to="/" className="brand auth-brand" aria-label="Zentro Home">
            <span className="brand-mark brand-mark-logo">
              <ZentroLogo className="w-full h-full object-contain" />
            </span>
            zentro
          </Link>
          <AuthIllustration />
          <p>
            Thoughtful delivery,
            <br />
            <em>made for Sri Lanka.</em>
          </p>
          <span className="auth-art-note">A little closer, every time.</span>
        </section>
        <section className="auth-panel">
          <Link to="/" className="brand auth-brand-mobile" aria-label="Zentro Home">
            <span className="brand-mark brand-mark-logo">
              <ZentroLogo className="w-full h-full object-contain" />
            </span>
            zentro
          </Link>
          <Outlet />
        </section>
      </main>
    </>
  );
}
