import { Navigate } from 'react-router-dom';
import { SignupPage as ModernSignupPage } from '@/components/ui/sign-up-page';
import { ModernLoginPage } from '@/components/ui/login-page';
import { ModernForgotPasswordPage } from '@/components/ui/forgot-password-page';

export function LoginPage() { return <ModernLoginPage kind="customer" />; }
export function StaffLoginPage() { return <Navigate to="/login" replace />; }
export function MerchantLoginPage() { return <Navigate to="/login?mode=merchant" replace />; }

export function SignupPage() {
  return <ModernSignupPage />;
}

export function ForgotPasswordPage() {
  return <ModernForgotPasswordPage />;
}
