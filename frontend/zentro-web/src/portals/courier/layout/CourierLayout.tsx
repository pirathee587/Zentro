import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import './courier.css';

export function CourierLayout() {
  const { logout, user } = useAuth();

  return <div className="courier-portal">
    <header className="courier-header">
      <Link className="staff-brand" to="/courier" aria-label="Zentro courier home">
        <span className="staff-brand-mark">z</span><span>zentro <small>courier</small></span>
      </Link>
      <nav className="staff-nav" aria-label="Courier navigation">
        <NavLink end to="/courier">My dashboard</NavLink>
        <NavLink to="/courier/profile">My profile</NavLink>
      </nav>
      <div className="staff-account">
        <span>{user?.name || 'Courier'}</span>
        <button className="staff-logout" type="button" onClick={() => void logout()}>Log out <span aria-hidden="true">↗</span></button>
      </div>
    </header>
    <Outlet />
  </div>;
}
