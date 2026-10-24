import { Link, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import './customer.css';

const navigation = [
  { to: '/customer', label: 'Overview', end: true },
  { to: '/customer/parcels', label: 'My parcels' },
  { to: '/track', label: 'Track a parcel' },
];

export function CustomerLayout() {
  const { logout } = useAuth();
  const { pathname } = useLocation();

  return (
    <div className="customer-portal">
      <header className="customer-header">
        <Link className="customer-brand" to="/customer" aria-label="Zentro customer home">
          <span className="customer-brand-mark">z</span>
          <span>zentro</span>
        </Link>

        <nav className="customer-nav" aria-label="Customer navigation">
          {navigation.map(({ to, label, end }) => {
            const active = end ? pathname === to : pathname.startsWith(to);
            return <Link key={to} className={active ? 'is-active' : undefined} to={to}>{label}</Link>;
          })}
        </nav>

        <div className="customer-header-actions">
          <Link className="customer-new-delivery" to="/customer/create-delivery">Send a parcel <span aria-hidden="true">↗</span></Link>
          <button className="customer-logout" type="button" onClick={() => void logout()}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 17l5-5-5-5M15 12H3m9-8h6a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6" /></svg>
            Log out
          </button>
        </div>
      </header>
      <Outlet />
    </div>
  );
}
