import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { Button } from '@/shared/ui';
import './admin.css';

export function AdminLayout() {
  const { logout, user } = useAuth();

  return <div className="dispatcher-portal">
    <header className="dispatcher-header">
      <Link className="staff-brand" to="/dispatcher" aria-label="Zentro dispatch home">
        <span className="staff-brand-mark">z</span><span>zentro <small>dispatch</small></span>
      </Link>
      <nav className="staff-nav" aria-label="Dispatcher navigation">
        <NavLink end to="/dispatcher">Overview</NavLink>
        <NavLink to="/dispatcher/couriers">Couriers</NavLink>
        <NavLink to="/dispatcher/merchant-approvals">Merchant applications</NavLink>
      </nav>
      <div className="staff-account">
        <span>{user?.name || 'Dispatcher'}</span>
        <Button variant="secondary" className="staff-logout" type="button" onClick={() => void logout()}>Log out <span aria-hidden="true">↗</span></Button>
      </div>
    </header>
    <Outlet />
  </div>;
}
