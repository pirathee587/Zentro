import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, ErrorState, Spinner } from '@/shared/ui';

export function CallbackPage() {
  const { user, login, logout, loading } = useAuth();
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
  const returnTo = isMerchantApplicationPath ? location.pathname : user.returnTo ?? '/';
  const entryPoint = isMerchantApplicationPath || returnTo.startsWith('/merchant')
    ? 'merchant'
    : user.entryPoint ?? (
      returnTo.startsWith('/staff')
      || returnTo.startsWith('/admin')
      || returnTo.startsWith('/dispatcher')
      || returnTo.startsWith('/courier')
        ? 'staff'
        : 'customer'
    );
  const hasStaffRole = user.roles.includes('DISPATCHER') || user.roles.includes('COURIER');
  // Return to the originally requested page only when it lies inside a portal this role may use.
  const inPortal = (...bases: string[]) => !returnTo.startsWith('//') && bases.some((base) =>
    returnTo === base || returnTo.startsWith(`${base}/`) || returnTo.startsWith(`${base}?`));

  // Priority 1: If user has a specific staff or business role, direct them to their primary portal
  if (user.roles.includes('DISPATCHER')) {
    return <Navigate to={inPortal('/dispatcher', '/admin') ? returnTo : '/dispatcher'} replace />;
  }

  if (user.roles.includes('COURIER')) {
    return <Navigate to={inPortal('/courier') ? returnTo : '/courier'} replace />;
  }

  if (user.roles.includes('MERCHANT')) {
    return <Navigate to={inPortal('/merchant/dashboard') ? returnTo : '/merchant/dashboard'} replace />;
  }

  // Priority 2: Merchant applicant workflow
  if (entryPoint === 'merchant' || isMerchantApplicationPath) {
    if (user.merchantStatus === 'PENDING' || user.merchantStatus === 'REJECTED') {
      return <Navigate to="/merchant/status" replace />;
    }
    return <Navigate to="/merchant/register-business" replace />;
  }

  // Priority 3: Customer role
  if (user.roles.includes('CUSTOMER')) {
    if (user.merchantStatus === 'PENDING' || user.merchantStatus === 'REJECTED') {
      return <Navigate to="/merchant/status" replace />;
    }
    const customerPath = inPortal('/customer') ? returnTo : '/customer';
    return <Navigate to={customerPath} replace />;
  }

  return (
    <section className="auth-card">
      <ErrorState message="This account does not have access to a Zentro portal." />
      <Button onClick={() => void logout('/login')}>Sign in with another account</Button>
      <Button variant="secondary" onClick={() => void logout('/staff/login')}>Staff sign in</Button>
    </section>
  );
}
