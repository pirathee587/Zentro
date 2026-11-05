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

  if (entryPoint === 'staff') {
    if (user.roles.includes('DISPATCHER')) return <Navigate to="/dispatcher" replace />;
    if (user.roles.includes('COURIER')) return <Navigate to="/courier" replace />;

    return (
      <section className="auth-card">
        <span className="eyebrow">STAFF ACCESS</span>
        <h1>This account is not enabled for staff.</h1>
        <p>Staff sign-in requires a Zentro-issued dispatcher or courier account. Contact your administrator if you need staff access.</p>
        <Button onClick={() => void logout()}>Sign out</Button>
      </section>
    );
  }

  if (entryPoint === 'merchant') {
    if (hasStaffRole) {
      return (
        <section className="auth-card">
          <span className="eyebrow">BUSINESS ACCESS</span>
          <h1>Use your staff sign-in.</h1>
          <p>Staff accounts cannot use the merchant portal.</p>
          <Button onClick={() => void logout()}>Sign out</Button>
        </section>
      );
    }

    if (user.roles.includes('MERCHANT')) return <Navigate to="/merchant/dashboard" replace />;
    if (user.roles.includes('CUSTOMER')) {
      if (user.merchantStatus === 'PENDING') return <Navigate to="/merchant/status" replace />;
      return <Navigate to="/merchant/register-business" replace />;
    }
  }

  if (entryPoint === 'customer' && !hasStaffRole && user.roles.includes('CUSTOMER')) {
    const customerPath = returnTo === '/customer' || returnTo.startsWith('/customer/')
      ? returnTo
      : '/customer';
    return <Navigate to={customerPath} replace />;
  }

  return (
    <section className="auth-card">
      <ErrorState message="This account does not have access to a Zentro portal." />
      <Button onClick={() => void logout()}>Sign out</Button>
      <Button variant="secondary" onClick={() => void login('/staff/login')}>Staff sign in</Button>
    </section>
  );
}
