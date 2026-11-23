import { useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './useAuth';
import { Spinner } from '@/shared/ui';

export function ProtectedRoute() {
  const { user, login, loading } = useAuth();
  const location = useLocation();

  useEffect(() => {
    if (!loading && !user) {
      const path = location.pathname;
      const entryPoint = /^\/(admin|courier|dispatcher|staff)(\/|$)/.test(path) ? 'staff' : path.startsWith('/merchant') ? 'merchant' : 'customer';
      // Reuse a still-valid Keycloak session; login() keeps forcing credentials after an explicit sign-out.
      void login(path + location.search, undefined, entryPoint, undefined, { reauthenticate: false });
    }
  }, [loading, login, location.pathname, location.search, user]);

  if (loading) {
    return <main className="screen-loading"><Spinner label="Restoring session..." /></main>;
  }

  return user ? <Outlet /> : <main className="screen-loading">Redirecting to sign in...</main>;
}

