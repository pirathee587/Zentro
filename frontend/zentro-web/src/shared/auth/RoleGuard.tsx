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
  const isApprovedMerchant = user?.merchantStatus === 'APPROVED' || user?.roles.includes('MERCHANT');
  const hasRequestedRole = Boolean(
    user?.roles.some((role) => roles.includes(role)) ||
    (roles.includes('MERCHANT') && isApprovedMerchant)
  );
  const accountIsActive = user?.accountStatus !== 'DISABLED' && user?.accountStatus !== 'SUSPENDED';

  // Only merchant-only routes (like /merchant/dashboard) require merchant approval.
  // Shared customer routes (like /customer or /merchant/status) never block on merchant approval.
  const merchantOnlyRoute = roles.includes('MERCHANT') && !roles.includes('CUSTOMER');
  const merchantIsApproved = !merchantOnlyRoute || isApprovedMerchant;

  const customerOnMerchantDashboard = merchantOnlyRoute
    && user?.roles.includes('CUSTOMER') === true
    && location.pathname.replace(/\/$/, '') === '/merchant/dashboard';
  const pendingMerchantDashboard = customerOnMerchantDashboard
    && (user?.merchantStatus === 'PENDING' || navigationMerchantStatus === 'PENDING');

  const allowed = (hasRequestedRole && merchantIsApproved || pendingMerchantDashboard) && accountIsActive && (!userIsStaff || staffOnlyPortal);

  // A pending applicant only has CUSTOMER, so wait for the profile before deciding on the merchant dashboard too.
  if (merchantOnlyRoute && user?.profileLoaded !== true) {
    return <main className="screen-loading"><Spinner label="Checking merchant approval" /></main>;
  }

  return allowed ? <Outlet /> : <Navigate to={user ? '/forbidden' : '/login'} replace />;
}
