import { useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './useAuth';

export function ProtectedRoute() {
  const { user, login } = useAuth();
  const location = useLocation();
  useEffect(() => {
    if (!user) {
      const path = location.pathname;
      const entryPoint = /^\/(admin|courier|staff)(\/|$)/.test(path) ? 'staff' : path.startsWith('/merchant') ? 'merchant' : 'customer';
      void login(path + location.search, undefined, entryPoint);
    }
  }, [login, location.pathname, location.search, user]);
  return user ? <Outlet /> : <main className="screen-loading">Redirecting to sign in...</main>;
}
