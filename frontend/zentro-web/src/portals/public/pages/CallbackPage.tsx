import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, ErrorState, Spinner } from '@/shared/ui';
import { routeForRoles } from '@/shared/config/roles';
import { isAuthPath } from '@/shared/auth/authContext';

export function CallbackPage() {
  const { user, login, loading } = useAuth();
  const location = useLocation();

  if (!user) {
    return loading ? (
      <section className="auth-card"><Spinner /><p>Finishing sign in...</p></section>
    ) : (
      <section className="auth-card">
        <ErrorState message="We could not complete sign in. Please try again." />
        <Button onClick={() => void login('/')}>Try again</Button>
      </section>
    );
  }

  const isMerchantApplicationPath =
    location.pathname === '/merchant/register-business' || location.pathname === '/merchant/status';
  const rawReturnTo = isMerchantApplicationPath ? location.pathname : user.returnTo ?? '/';
  const returnTo = isAuthPath(rawReturnTo) ? '/' : rawReturnTo;

  // Return to the originally requested page only when it lies inside a portal this role may use.
  const inPortal = (...bases: string[]) =>
    returnTo !== '/' &&
    !returnTo.startsWith('//') &&
    bases.some((base) => returnTo === base || returnTo.startsWith(`${base}/`) || returnTo.startsWith(`${base}?`));

  // If signed in under Merchant mode or has merchant intent
  const hasMerchantIntent =
    user.entryPoint === 'merchant' ||
    sessionStorage.getItem('merchant_upgrade_intent') === '1' ||
    inPortal('/merchant', '/merchant/register-business', '/merchant/status', '/merchant/dashboard');

  if (user.entryPoint === 'merchant' || (hasMerchantIntent && !user.roles.includes('DISPATCHER') && !user.roles.includes('COURIER'))) {
    sessionStorage.removeItem('merchant_upgrade_intent');
    if (user.roles.includes('MERCHANT') || user.merchantStatus === 'APPROVED') {
      return <Navigate to={inPortal('/merchant/dashboard') ? returnTo : '/merchant/dashboard'} replace />;
    }
    if (user.merchantStatus === 'PENDING' || user.merchantStatus === 'REJECTED') {
      return <Navigate to="/merchant/status" replace />;
    }
    if (user.roles.includes('CUSTOMER')) {
      return <Navigate to={inPortal('/merchant/register-business') ? returnTo : '/merchant/register-business'} replace />;
    }
  }

  // Customer or Staff login mode
  if (user.roles.includes('DISPATCHER')) {
    return <Navigate to={inPortal('/dispatcher', '/admin') ? returnTo : '/dispatcher'} replace />;
  }

  if (user.roles.includes('COURIER')) {
    return <Navigate to={inPortal('/courier') ? returnTo : '/courier'} replace />;
  }

  if (user.roles.includes('CUSTOMER')) {
    if (inPortal('/merchant', '/merchant/register-business', '/merchant/status', '/merchant/dashboard')) {
      if (user.roles.includes('MERCHANT') && inPortal('/merchant/dashboard')) {
        return <Navigate to={returnTo} replace />;
      }
      if (user.merchantStatus === 'PENDING' || user.merchantStatus === 'REJECTED') {
        return <Navigate to="/merchant/status" replace />;
      }
      return <Navigate to="/merchant/register-business" replace />;
    }
    const customerPath = inPortal('/customer') ? returnTo : '/customer';
    return <Navigate to={customerPath} replace />;
  }

  if (user.roles.includes('MERCHANT')) {
    return <Navigate to={inPortal('/merchant/dashboard') ? returnTo : '/merchant/dashboard'} replace />;
  }

  const defaultRoute = routeForRoles(user.roles) || '/customer';
  return <Navigate to={defaultRoute} replace />;
}
