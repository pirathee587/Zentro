import { Link, Outlet, useLocation } from 'react-router-dom';

export function PublicLayout() {
  const { pathname } = useLocation();
  if (pathname === '/') return <Outlet />;
  return <main className="auth-page"><Link to="/" className="brand auth-brand"><span className="brand-mark">z</span>zentro</Link><Outlet /></main>;
}
