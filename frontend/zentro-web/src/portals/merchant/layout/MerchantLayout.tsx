import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { Button } from '@/shared/ui';
import './merchant.css';

export function MerchantLayout() {
  const { logout, user } = useAuth();
  return <div className="merchant-portal">
    <header className="merchant-header">
      <Link className="merchant-brand" to="/merchant/dashboard" aria-label="Zentro merchant home"><span className="merchant-brand-mark">z</span><span>zentro <small>business</small></span></Link>
      <nav className="merchant-nav" aria-label="Merchant navigation">
        <NavLink end to="/merchant/dashboard">Overview</NavLink>
        <NavLink to="/merchant/dashboard/api-credentials">API credentials</NavLink>
        <NavLink to="/merchant/dashboard/webhooks">Webhooks</NavLink>
      </nav>
      <div className="merchant-account"><span>{user?.name || 'Merchant account'}</span><Button variant="secondary" type="button" className="merchant-logout" onClick={() => void logout()}>Log out <span aria-hidden="true">↗</span></Button></div>
    </header>
    <Outlet />
  </div>;
}
