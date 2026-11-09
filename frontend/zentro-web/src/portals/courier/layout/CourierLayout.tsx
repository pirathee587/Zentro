import { Link, NavLink, Outlet } from 'react-router-dom';
import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { principalCacheScope } from '@/shared/api/queryKeys';
import { useAuth } from '@/shared/auth/useAuth';
import { Button } from '@/shared/ui';
import './courier.css';

export function CourierLayout() {
  const { logout, user } = useAuth();
  const client = useQueryClient();
  const scope = principalCacheScope(user?.id);
  useEffect(() => () => {
    const filters = { predicate: (query: { queryKey: readonly unknown[] }) =>
      query.queryKey[0] === 'courier' && query.queryKey.some(part =>
        typeof part === 'object' && part !== null && 'scope' in part && part.scope === scope) };
    void client.cancelQueries(filters);
    client.removeQueries(filters);
  }, [client, scope]);

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
        <Button variant="secondary" className="staff-logout" type="button" onClick={() => void logout()}>Log out <span aria-hidden="true">↗</span></Button>
      </div>
    </header>
    <div key={scope}><Outlet /></div>
  </div>;
}
