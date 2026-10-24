import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './useAuth';
import type { Role } from '@/shared/config/roles';
import { Spinner } from '@/shared/ui';

export function RoleGuard({ roles }: { roles: Role[] }) {
  const { user } = useAuth();
  const location = useLocation();
  const navigationMerchantStatus = (location.state as { merchantStatus?: string } | null)?.merchantStatus;
  const userIsStaff = Boolean(user?.roles.some((role) => role === 'DISPATCHER' || role === 'COURIER'));
  const staffOnlyPortal = roles.includes('DISPATCHER') || roles.includes('COURIER');
  const hasRequestedRole = Boolean(user?.roles.some((role) => roles.includes(role)));
  const accountIsActive = user?.accountStatus !== 'DISABLED' && user?.accountStatus !== 'SUSPENDED';
  const merchantIsApproved = !roles.includes('MERCHANT')
    || (user?.profileLoaded === true && user.merchantStatus === 'APPROVED');
  const pendingMerchantDashboard = roles.includes('MERCHANT')
    && user?.roles.includes('CUSTOMER') === true
    && (user.merchantStatus === 'PENDING' || navigationMerchantStatus === 'PENDING')
    && location.pathname.replace(/\/$/, '') === '/merchant/dashboard';
  const allowed = (hasRequestedRole && merchantIsApproved || pendingMerchantDashboard) && accountIsActive && (!userIsStaff || staffOnlyPortal);

  if (hasRequestedRole && roles.includes('MERCHANT') && user?.profileLoaded !== true) {
    return <main className="screen-loading"><Spinner label="Checking merchant approval" /></main>;
  }

  return allowed ? <Outlet /> : <Navigate to={user ? '/forbidden' : '/login'} replace />;
}
