import { Navigate } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';

export function MerchantPage() {
  const { user } = useAuth();
  if (user) {
    if (user.roles.includes('MERCHANT') || user.merchantStatus === 'APPROVED') {
      return <Navigate to="/merchant/dashboard" replace />;
    }
    if (user.merchantStatus === 'PENDING' || user.merchantStatus === 'REJECTED') {
      return <Navigate to="/merchant/status" replace />;
    }
    return <Navigate to="/merchant/register-business" replace />;
  }
  return <Navigate to="/register?intent=merchant" replace />;
}
