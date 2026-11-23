'use client';

import React, { useState } from 'react';
import { Eye, EyeOff, ArrowLeft, ArrowRight, Mail, Lock } from 'lucide-react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { ZentroAuthAnimatedBackground } from './ZentroAuthAnimatedBackground';

const DELIVERY_WAREHOUSE_IMAGE =
  'https://images.unsplash.com/photo-1580674285054-bed31e145f59?auto=format&fit=crop&w=1200&q=80';

const H = (c: string) => String.fromCharCode(35) + c;

export interface LoginPageProps {
  kind?: 'customer' | 'staff' | 'merchant';
}

export function ModernLoginPage({ kind = 'customer' }: LoginPageProps) {
  const { login, logout, user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  const searchParams = new URLSearchParams(location.search);
  const initialEmail = searchParams.get('login_hint') ?? '';

  const [email, setEmail] = useState(initialEmail);
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const [emailError, setEmailError] = useState('');

  const from =
    kind === 'staff'
      ? '/staff/login'
      : kind === 'merchant'
        ? '/merchant/dashboard'
        : (location.state as { from?: string } | null)?.from ?? '/customer';

  const staff = kind === 'staff';
  const merchant = kind === 'merchant';

  if (user) {
    if (kind === 'merchant' && user.roles.includes('MERCHANT')) {
      return <Navigate to="/merchant/dashboard" replace />;
    }
    if (kind === 'customer' && user.roles.includes('CUSTOMER')) {
      return <Navigate to="/customer" replace />;
    }
    if (kind === 'staff') {
      if (user.roles.includes('DISPATCHER')) return <Navigate to="/dispatcher" replace />;
      if (user.roles.includes('COURIER')) return <Navigate to="/courier" replace />;
    }
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    const value = email.trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
      setEmailError('Enter a valid email address.');
      return;
    }
    setEmailError('');
    if (user && logout) {
      await logout(`${location.pathname}?login_hint=${encodeURIComponent(value)}`);
      return;
    }
    void login(from, value, kind);
  };

  const handleGoogleSignIn = () => {
    void login(from, undefined, merchant ? 'merchant' : 'customer', 'google');
  };

  return (
    <ZentroAuthAnimatedBackground>
      <div className="auth-card-enter relative z-10 flex flex-col md:flex-row w-full max-w-5xl min-h-[640px] bg-white rounded-3xl shadow-2xl overflow-hidden border border-orange-100/70">
        {/* Left Panel */}
        <div className="flex-1 relative overflow-hidden md:block hidden min-h-[640px]">
          <div className="absolute top-6 left-6 z-10">
            <button
              type="button"
              aria-label="Back to home"
              onClick={() => navigate('/')}
              className="w-10 h-10 bg-black/30 backdrop-blur-sm rounded-full flex items-center justify-center hover:bg-black/40 transition-all"
            >
              <ArrowLeft className="w-5 h-5 text-white" />
            </button>
          </div>

          <div className="absolute inset-0">
            <img
              src={DELIVERY_WAREHOUSE_IMAGE}
              alt="Zentro delivery parcels"
              className="w-full h-full object-cover"
            />
          </div>
        </div>

        {/* Right Panel */}
        <div className="flex-1 p-8 md:p-12 flex flex-col justify-center">
          <div className="mb-7">
            <span className="block text-xs font-semibold tracking-[0.16em] text-orange-600 uppercase mb-3">
              {staff ? 'ZENTRO TEAM' : merchant ? 'ZENTRO BUSINESS' : 'WELCOME BACK'}
            </span>
            <h1 id="auth-title" className="text-4xl font-bold text-slate-900 leading-[1.15] tracking-tight mb-3">
              {staff ? (
                'Staff sign in'
              ) : merchant ? (
                'Merchant sign in'
              ) : (
                <>
                  Good to see you,
                  <br />
                  again.
                </>
              )}
            </h1>
            <p className="text-sm text-gray-500">
              {staff
                ? 'Use the separate staff account provided by your Zentro administrator.'
                : merchant
                  ? 'Sign in with your existing Zentro email and password to manage your business access.'
                  : 'Sign in to see where your parcels are going.'}
            </p>
          </div>

          {user && (
            <div className="mb-5 p-3.5 bg-amber-50 border border-amber-200 rounded-xl text-xs text-red-700 space-y-2">
              <strong className="block">Currently signed in as {user.name || 'another account'}.</strong>
              <p className="text-gray-800">
                To sign in as {kind === 'merchant' ? 'a merchant' : kind === 'staff' ? 'staff' : 'a customer'}, switch
                accounts below.
              </p>
              <button
                type="button"
                onClick={() => void logout?.(location.pathname)}
                className="w-full py-1.5 px-3 bg-white border border-gray-300 rounded-lg font-medium text-red-700 hover:bg-gray-50"
              >
                Sign out current account
              </button>
            </div>
          )}

          <form onSubmit={handleSubmit} noValidate className="space-y-4">
            {/* Email Address Field */}
            <div>
              <label htmlFor="auth-username" className="block text-sm font-semibold text-gray-800 mb-1.5">
                {staff ? 'Staff email address' : 'Email address'}
              </label>
              <div className="relative">
                <Mail className="w-4 h-4 text-gray-500 absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                <input
                  id="auth-username"
                  type="email"
                  autoComplete="username"
                  autoFocus
                  required
                  value={email}
                  aria-invalid={Boolean(emailError)}
                  aria-describedby={emailError ? 'auth-email-error' : 'auth-email-hint'}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    setEmailError('');
                  }}
                  placeholder={staff ? 'your.work@zentro.lk' : 'you@example.com'}
                  className="w-full pl-10 pr-4 py-3 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                />
              </div>
              {emailError ? (
                <p id="auth-email-error" role="alert" aria-live="polite" className="mt-1.5 text-xs text-red-600">
                  {emailError}
                </p>
              ) : (
                <small id="auth-email-hint" className="sr-only">
                  Use your Zentro account email.
                </small>
              )}
            </div>

            {/* Password Field */}
            <div>
              <label htmlFor="auth-password" className="block text-sm font-semibold text-gray-800 mb-1.5">
                Password
              </label>
              <div className="relative">
                <Lock className="w-4 h-4 text-gray-500 absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                <input
                  id="auth-password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter your password"
                  className="w-full pl-10 pr-11 py-3 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                />
                <button
                  type="button"
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 p-1 hover:bg-gray-200/60 rounded-full transition-colors"
                >
                  {showPassword ? (
                    <EyeOff className="w-4 h-4 text-gray-500" />
                  ) : (
                    <Eye className="w-4 h-4 text-gray-500" />
                  )}
                </button>
              </div>
            </div>

            {/* Remember me & Forgot password */}
            <div className="flex items-center justify-between pt-1">
              <label htmlFor="remember-me" className="inline-flex items-center gap-2 text-xs font-semibold text-gray-800 cursor-pointer">
                <input
                  id="remember-me"
                  type="checkbox"
                  checked={rememberMe}
                  onChange={(e) => setRememberMe(e.target.checked)}
                  className="w-3.5 h-3.5 rounded border-gray-300 text-orange-600 focus:ring-orange-500"
                />
                <span>Remember me</span>
              </label>
              {!staff && (
                <Link to="/forgot-password" className="text-xs font-semibold text-orange-600 hover:text-orange-700 hover:underline">
                  Forgot your password?
                </Link>
              )}
            </div>

            {/* Sign In Button */}
            <button
              type="submit"
              className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl text-sm font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all flex items-center justify-center gap-2 shadow-sm"
            >
              <span>Sign In</span>
              <ArrowRight className="w-4 h-4" />
            </button>

            {/* OR Divider & Continue with Google */}
            {!staff && (
              <>
                <div className="relative flex items-center py-1">
                  <div className="flex-grow border-t border-gray-200" />
                  <span className="flex-shrink mx-4 text-[11px] font-semibold text-gray-400 uppercase">OR</span>
                  <div className="flex-grow border-t border-gray-200" />
                </div>

                <button
                  type="button"
                  onClick={handleGoogleSignIn}
                  className="w-full border border-gray-200 bg-white text-gray-800 py-3 px-4 rounded-xl text-sm font-semibold hover:bg-gray-50 transition-colors flex items-center justify-center gap-2.5 shadow-sm"
                >
                  <svg className="w-4 h-4" viewBox="0 0 24 24" aria-hidden="true">
                    <path
                      fill={H('4285F4')}
                      d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
                    />
                    <path
                      fill={H('34A853')}
                      d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
                    />
                    <path
                      fill={H('FBBC05')}
                      d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
                    />
                    <path
                      fill={H('EA4335')}
                      d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
                    />
                  </svg>
                  <span>Continue with Google</span>
                </button>
              </>
            )}
          </form>

          {/* Bottom Links */}
          <div className="mt-6 text-center space-y-2 text-xs">
            {staff ? (
              <p className="text-gray-500">
                For dispatchers and couriers only. Staff accounts are created by Zentro.
              </p>
            ) : merchant ? (
              <>
                <p className="text-gray-500">
                  New to Zentro?{' '}
                  <Link to="/register?next=merchant" className="text-orange-600 hover:text-orange-700 font-semibold">
                    Create an account and apply
                  </Link>
                </p>
                <p>
                  <Link to="/merchant" className="text-orange-600 hover:text-orange-700 font-semibold">
                    Business information
                  </Link>
                </p>
              </>
            ) : (
              <>
                <p className="text-gray-500">
                  New to Zentro?{' '}
                  <Link to="/register" className="text-orange-600 hover:text-orange-700 font-semibold">
                    Create an account
                  </Link>
                </p>
                <p className="text-gray-400">
                  <Link to="/staff/login" className="text-orange-600 hover:text-orange-700 font-semibold">
                    Staff sign in
                  </Link>{' '}
                  |{' '}
                  <Link to="/merchant/login" className="text-orange-600 hover:text-orange-700 font-semibold">
                    Merchant sign in
                  </Link>
                </p>
              </>
            )}
          </div>
        </div>
      </div>
    </ZentroAuthAnimatedBackground>
  );
}

export default ModernLoginPage;
