import { useState, useEffect, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, Input } from '@/shared/ui';
import { friendlyApiError } from '@/shared/api/apiClient';
import { routeForRoles, type Role } from '@/shared/config/roles';
import { ArrowIcon } from '../assets/ArrowIcon';
import { SignupPage as ModernSignupPage } from '@/components/ui/sign-up-page';
import { ModernLoginPage } from '@/components/ui/login-page';
import { ModernForgotPasswordPage } from '@/components/ui/forgot-password-page';

function authApiError(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { detail?: string; message?: string } | undefined;
    if (data?.detail && typeof data.detail === 'string') return data.detail;
    if (data?.message && typeof data.message === 'string' && data.message.trim() && data.message !== 'No message available') return data.message;
    if (error.response?.status === 409) return 'An account with this email address already exists. Please sign in or use a different email.';
  }
  return friendlyApiError(error, fallback);
}
import {
  sendRegistrationOtp,
  verifyRegistrationOtp,
} from '../api/registrationApi';

const Arrow = ArrowIcon;

export function LoginPage() { return <ModernLoginPage kind="customer" />; }
export function StaffLoginPage() { return <Navigate to="/login" replace />; }
export function MerchantLoginPage() { return <Navigate to="/login?mode=merchant" replace />; }

function GoogleSignInButton({ returnTo, entryPoint }: { returnTo: string; entryPoint: 'customer' | 'merchant' }) {
  const { login } = useAuth();
  return <Button type="button" variant="secondary" className="google-login" onClick={() => void login(returnTo, undefined, entryPoint, 'google')}><span className="google-mark" aria-hidden="true">G</span>Continue with Google</Button>;
}

export function SignupPage() {
  return <ModernSignupPage />;
}

export function ForgotPasswordPage() {
  return <ModernForgotPasswordPage />;
}
