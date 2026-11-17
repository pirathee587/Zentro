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
  const customerOnMerchantDashboard = roles.includes('MERCHANT')
    && user?.roles.includes('CUSTOMER') === true
    && location.pathname.replace(/\/$/, '') === '/merchant/dashboard';
  const pendingMerchantDashboard = customerOnMerchantDashboard
    && (user?.merchantStatus === 'PENDING' || navigationMerchantStatus === 'PENDING');
  const allowed = (hasRequestedRole && merchantIsApproved || pendingMerchantDashboard) && accountIsActive && (!userIsStaff || staffOnlyPortal);

  // A pending applicant only has CUSTOMER, so wait for the profile before deciding on the merchant dashboard too.
  if ((hasRequestedRole || customerOnMerchantDashboard) && roles.includes('MERCHANT') && user?.profileLoaded !== true) {
    return <main className="screen-loading"><Spinner label="Checking merchant approval" /></main>;
  }

  return allowed ? <Outlet /> : <Navigate to={user ? '/forbidden' : '/login'} replace />;
}
