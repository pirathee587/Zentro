'use client';

import React, { useState, useContext, useRef, useEffect } from 'react';
import axios from 'axios';
import { Eye, EyeOff, ArrowLeft } from 'lucide-react';
import { Link, Navigate, useNavigate, useLocation } from 'react-router-dom';
import { AuthContext } from '@/shared/auth/authContext';
import { friendlyApiError } from '@/shared/api/apiClient';
import { sendRegistrationOtp, verifyRegistrationOtp } from '@/portals/public/api/registrationApi';
import { routeForRoles } from '@/shared/config/roles';
import { ZentroAuthAnimatedBackground } from './ZentroAuthAnimatedBackground';

const DELIVERY_WAREHOUSE_IMAGE =
  'https://images.unsplash.com/photo-1580674285054-bed31e145f59?auto=format&fit=crop&w=1200&q=80';

const H = (c: string) => String.fromCharCode(35) + c;

function maskEmail(email: string): string {
  const [local, domain] = email.split('@');
  if (!domain) return email;
  if (local.length <= 2) return `${local[0]}***@${domain}`;
  return `${local[0]}***${local[local.length - 1]}@${domain}`;
}

function parseAuthApiError(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { detail?: string; message?: string } | undefined;
    if (data?.detail && typeof data.detail === 'string') return data.detail;
    if (data?.message && typeof data.message === 'string' && data.message.trim() && data.message !== 'No message available') {
      return data.message;
    }
    if (error.response?.status === 409) {
      return 'This email is already registered. Please log in.';
    }
  }
  return friendlyApiError(error, fallback);
}

export function SignupPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const auth = useContext(AuthContext);

  const searchParams = new URLSearchParams(location.search);
  const stateData = location.state as { intent?: string } | null;
  const isMerchantIntent = searchParams.get('intent') === 'merchant'
    || stateData?.intent === 'merchant'
    || sessionStorage.getItem('merchant_upgrade_intent') === '1';

  useEffect(() => {
    if (isMerchantIntent) {
      sessionStorage.setItem('merchant_upgrade_intent', '1');
    }
  }, [isMerchantIntent]);

  const [step, setStep] = useState<'form' | 'otp'>('form');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const [otpDigits, setOtpDigits] = useState(['', '', '', '', '', '']);
  const [cooldown, setCooldown] = useState(0);
  const otpInputRefs = useRef<(HTMLInputElement | null)[]>([]);

  const [formData, setFormData] = useState({
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    confirmPassword: '',
    agreeToTerms: false,
  });

  useEffect(() => {
    if (cooldown <= 0) return;
    const interval = setInterval(() => {
      setCooldown((c) => Math.max(0, c - 1));
    }, 1000);
    return () => clearInterval(interval);
  }, [cooldown]);

  if (auth?.user) {
    if (isMerchantIntent) {
      if (auth.user.roles.includes('MERCHANT') || auth.user.merchantStatus === 'APPROVED') {
        return <Navigate to="/merchant/dashboard" replace />;
      }
      if (auth.user.merchantStatus === 'PENDING' || auth.user.merchantStatus === 'REJECTED') {
        return <Navigate to="/merchant/status" replace />;
      }
      return <Navigate to="/merchant/register-business" replace />;
    }
    const dashboard = routeForRoles(auth.user.roles) || '/customer';
    return <Navigate to={dashboard} replace />;
  }

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value, type, checked } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: type === 'checkbox' ? checked : value,
    }));
    setError('');
  };

  const handleGoogleSignUp = () => {
    if (auth?.login) {
      const isMerchant = isMerchantIntent || sessionStorage.getItem('merchant_upgrade_intent') === '1';
      const defaultReturnTo = isMerchant ? '/merchant/register-business' : '/customer';
      void auth.login(defaultReturnTo, undefined, isMerchant ? 'merchant' : 'customer', 'google');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    const firstName = formData.firstName.trim();
    const lastName = formData.lastName.trim();
    const email = formData.email.trim();

    if (!firstName || !lastName) {
      setError('Please enter your first and last name.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      setError('Please enter a valid email address.');
      return;
    }
    if (formData.password.length < 8) {
      setError('Password must be at least 8 characters long.');
      return;
    }
    if (formData.password !== formData.confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    if (!formData.agreeToTerms) {
      setError('Please agree to the Terms & Conditions.');
      return;
    }

    setLoading(true);
    try {
      await sendRegistrationOtp({
        email,
        name: `${firstName} ${lastName}`.trim(),
        entryPoint: 'customer',
      });
      setStep('otp');
      setCooldown(60);
      setOtpDigits(['', '', '', '', '', '']);
      setTimeout(() => {
        otpInputRefs.current[0]?.focus();
      }, 100);
    } catch (err) {
      setError(parseAuthApiError(err, 'Failed to send verification code. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  const handleOtpDigitChange = (index: number, val: string) => {
    const cleaned = val.replace(/\D/g, '');
    if (!cleaned) {
      setOtpDigits((prev) => {
        const next = [...prev];
        next[index] = '';
        return next;
      });
      return;
    }

    if (cleaned.length > 1) {
      const digits = cleaned.slice(0, 6).split('');
      setOtpDigits((prev) => {
        const next = [...prev];
        digits.forEach((d, i) => {
          if (index + i < 6) next[index + i] = d;
        });
        return next;
      });
      const nextFocus = Math.min(5, index + digits.length);
      otpInputRefs.current[nextFocus]?.focus();
      return;
    }

    setOtpDigits((prev) => {
      const next = [...prev];
      next[index] = cleaned[0];
      return next;
    });
    setError('');

    if (index < 5) {
      otpInputRefs.current[index + 1]?.focus();
    }
  };

  const handleOtpKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !otpDigits[index] && index > 0) {
      otpInputRefs.current[index - 1]?.focus();
    }
  };

  const handleResendOtp = async () => {
    if (cooldown > 0 || loading) return;
    setLoading(true);
    setError('');
    try {
      await sendRegistrationOtp({
        email: formData.email.trim(),
        name: `${formData.firstName.trim()} ${formData.lastName.trim()}`,
        entryPoint: 'customer',
      });
      setCooldown(60);
    } catch (err) {
      setError(parseAuthApiError(err, 'Failed to resend verification code.'));
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    const code = otpDigits.join('').trim();
    if (code.length !== 6) {
      setError('Please enter the full 6-digit verification code.');
      return;
    }

    setLoading(true);
    try {
      await verifyRegistrationOtp({
        email: formData.email.trim(),
        name: `${formData.firstName.trim()} ${formData.lastName.trim()}`,
        password: formData.password,
        code,
        entryPoint: 'customer',
      });

      // Directly navigate to Login page without auto-login or intermediate success screen
      const isMerchant = isMerchantIntent || sessionStorage.getItem('merchant_upgrade_intent') === '1';
      navigate(isMerchant ? '/login?mode=merchant' : '/login', {
        replace: true,
        state: {
          registered: true,
          email: formData.email.trim(),
          mode: isMerchant ? 'merchant' : 'customer',
          intent: isMerchant ? 'merchant' : undefined,
        },
      });
    } catch (err) {
      setError(parseAuthApiError(err, 'Invalid verification code. Please check and try again.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <ZentroAuthAnimatedBackground>
      <div className="auth-card-enter relative z-10 flex flex-col md:flex-row w-full max-w-5xl min-h-[680px] bg-white rounded-3xl shadow-2xl overflow-hidden border border-orange-100/70">
        {/* Left Panel */}
        <div className="flex-1 relative overflow-hidden md:block hidden min-h-[680px]">
          <div className="absolute top-6 left-6 z-10">
            <button
              type="button"
              aria-label="Back to home"
              onClick={() => (step === 'otp' ? setStep('form') : navigate('/'))}
              className="w-10 h-10 bg-black/20 backdrop-blur-sm rounded-full flex items-center justify-center hover:bg-black/30 transition-all"
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
        <div className="flex-1 p-8 md:p-10 flex flex-col justify-center">
          {step === 'form' ? (
            <>
              <div className="mb-6">
                <span className="block text-xs font-semibold tracking-[0.16em] text-orange-600 uppercase mb-2">
                  JOIN ZENTRO
                </span>
                <h1 className="text-3xl font-bold text-gray-900 mb-2">
                  Create an Account
                </h1>
                <p className="text-gray-600 text-sm">
                  Already have an account?{' '}
                  <Link
                    to={isMerchantIntent ? "/login?mode=merchant" : "/login"}
                    className="text-orange-600 hover:text-orange-700 font-semibold"
                  >
                    Log in
                  </Link>
                </p>
              </div>

              {error && (
                <div role="alert" className="mb-4 p-3.5 bg-red-50 border border-red-200 rounded-xl text-xs text-red-700 font-medium">
                  {error}
                </div>
              )}

              <form onSubmit={handleSubmit} noValidate className="space-y-4">
                {/* Name Fields */}
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label htmlFor="firstName" className="block text-sm font-semibold text-gray-800 mb-1.5">
                      First Name
                    </label>
                    <input
                      type="text"
                      id="firstName"
                      name="firstName"
                      value={formData.firstName}
                      onChange={handleInputChange}
                      placeholder="John"
                      className="w-full px-4 py-3 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                      required
                    />
                  </div>
                  <div>
                    <label htmlFor="lastName" className="block text-sm font-semibold text-gray-800 mb-1.5">
                      Last Name
                    </label>
                    <input
                      type="text"
                      id="lastName"
                      name="lastName"
                      value={formData.lastName}
                      onChange={handleInputChange}
                      placeholder="Doe"
                      className="w-full px-4 py-3 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                      required
                    />
                  </div>
                </div>

                {/* Email Field */}
                <div>
                  <label htmlFor="email" className="block text-sm font-semibold text-gray-800 mb-1.5">
                    Email Address
                  </label>
                  <input
                    type="email"
                    id="email"
                    name="email"
                    value={formData.email}
                    onChange={handleInputChange}
                    placeholder="Email Address"
                    className="w-full px-4 py-3 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                    required
                  />
                </div>

                {/* Password Field */}
                <div>
                  <label htmlFor="password" className="block text-sm font-semibold text-gray-800 mb-1.5">
                    Password
                  </label>
                  <div className="relative">
                    <input
                      type={showPassword ? 'text' : 'password'}
                      id="password"
                      name="password"
                      value={formData.password}
                      onChange={handleInputChange}
                      placeholder="Password"
                      className="w-full px-4 py-3 pr-12 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                      required
                    />
                    <button
                      type="button"
                      aria-label={showPassword ? 'Hide password' : 'Show password'}
                      onClick={() => setShowPassword(!showPassword)}
                      className="absolute right-3 top-1/2 transform -translate-y-1/2 p-1 hover:bg-gray-200/60 rounded-full transition-colors"
                    >
                      {showPassword ? (
                        <EyeOff className="w-4 h-4 text-gray-500" />
                      ) : (
                        <Eye className="w-4 h-4 text-gray-500" />
                      )}
                    </button>
                  </div>
                </div>

                {/* Confirm Password Field */}
                <div>
                  <label htmlFor="confirmPassword" className="block text-sm font-semibold text-gray-800 mb-1.5">
                    Confirm Password
                  </label>
                  <div className="relative">
                    <input
                      type={showConfirmPassword ? 'text' : 'password'}
                      id="confirmPassword"
                      name="confirmPassword"
                      value={formData.confirmPassword}
                      onChange={handleInputChange}
                      placeholder="Confirm Password"
                      className="w-full px-4 py-3 pr-12 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                      required
                    />
                    <button
                      type="button"
                      aria-label={showConfirmPassword ? 'Hide confirm password' : 'Show confirm password'}
                      onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                      className="absolute right-3 top-1/2 transform -translate-y-1/2 p-1 hover:bg-gray-200/60 rounded-full transition-colors"
                    >
                      {showConfirmPassword ? (
                        <EyeOff className="w-4 h-4 text-gray-500" />
                      ) : (
                        <Eye className="w-4 h-4 text-gray-500" />
                      )}
                    </button>
                  </div>
                </div>

                {/* Terms Checkbox */}
                <div className="flex items-center space-x-3 pt-1">
                  <input
                    type="checkbox"
                    id="agreeToTerms"
                    name="agreeToTerms"
                    checked={formData.agreeToTerms}
                    onChange={handleInputChange}
                    className="w-4 h-4 text-orange-600 border-gray-300 rounded focus:ring-orange-500"
                    required
                  />
                  <label htmlFor="agreeToTerms" className="text-sm text-gray-600">
                    I agree to the{' '}
                    <span className="text-black font-semibold">
                      Terms & Conditions
                    </span>
                  </label>
                </div>

                {/* Submit Button */}
                <button
                  type="submit"
                  disabled={loading}
                  className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl text-sm font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all shadow-sm disabled:opacity-70"
                >
                  {loading ? 'Sending Code...' : 'Create Account'}
                </button>

                {/* Divider */}
                <div className="relative flex items-center py-1">
                  <div className="flex-grow border-t border-gray-200" />
                  <span className="flex-shrink mx-4 text-xs font-semibold text-gray-400 uppercase">or</span>
                  <div className="flex-grow border-t border-gray-200" />
                </div>

                {/* Google Sign-Up Button */}
                <button
                  type="button"
                  onClick={handleGoogleSignUp}
                  className="w-full border border-gray-200 bg-white text-gray-800 py-3 px-4 rounded-xl text-sm font-semibold hover:bg-gray-50 transition-colors flex items-center justify-center gap-3 shadow-sm"
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
              </form>
            </>
          ) : (
            <>
              <div className="mb-6">
                <span className="block text-xs font-semibold tracking-[0.16em] text-orange-600 uppercase mb-2">
                  VERIFY YOUR EMAIL
                </span>
                <h1 className="text-3xl font-bold text-gray-900 mb-2">
                  Enter verification code
                </h1>
                <p className="text-gray-600 text-sm">
                  We sent a 6-digit code to{' '}
                  <span className="font-semibold text-gray-900">{maskEmail(formData.email)}</span>
                </p>
              </div>

              {error && (
                <div role="alert" className="mb-4 p-3.5 bg-red-50 border border-red-200 rounded-xl text-xs text-red-700 font-medium">
                  {error}
                </div>
              )}

              <form onSubmit={handleVerifyOtp} noValidate className="space-y-6">
                <div>
                  <label className="block text-xs font-medium text-gray-500 mb-3 text-center uppercase tracking-wider">
                    6-Digit Security Code
                  </label>
                  <div className="flex justify-center gap-2 sm:gap-3">
                    {otpDigits.map((digit, index) => (
                      <input
                        key={index}
                        ref={(el) => (otpInputRefs.current[index] = el)}
                        type="text"
                        inputMode="numeric"
                        maxLength={6}
                        value={digit}
                        onChange={(e) => handleOtpDigitChange(index, e.target.value)}
                        onKeyDown={(e) => handleOtpKeyDown(index, e)}
                        className="w-11 h-13 sm:w-12 sm:h-14 text-center text-xl font-bold rounded-xl border border-gray-200 bg-slate-50 text-gray-900 focus:bg-white focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                      />
                    ))}
                  </div>
                  <p className="mt-2.5 text-xs text-gray-400 text-center">
                    Code expires in 5 minutes (max 5 attempts).
                  </p>
                </div>

                <button
                  type="submit"
                  disabled={loading || otpDigits.join('').length !== 6}
                  className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl text-sm font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all shadow-sm disabled:opacity-50"
                >
                  {loading ? 'Verifying...' : 'Verify & Complete Registration'}
                </button>

                <div className="text-center space-y-2 text-xs pt-1">
                  <p className="text-gray-500">
                    {cooldown > 0 ? (
                      <span>Resend code in <strong className="text-orange-600">{cooldown}s</strong></span>
                    ) : (
                      <>
                        Didn't receive the code?{' '}
                        <button
                          type="button"
                          disabled={loading}
                          onClick={handleResendOtp}
                          className="text-orange-600 hover:text-orange-700 font-semibold underline-offset-2 hover:underline cursor-pointer"
                        >
                          Resend Code
                        </button>
                      </>
                    )}
                  </p>

                  <p className="text-gray-500">
                    Wrong email address?{' '}
                    <button
                      type="button"
                      onClick={() => {
                        setStep('form');
                        setError('');
                      }}
                      className="text-gray-700 hover:text-black font-semibold underline-offset-2 hover:underline cursor-pointer"
                    >
                      Back to registration
                    </button>
                  </p>
                </div>
              </form>
            </>
          )}
        </div>
      </div>
    </ZentroAuthAnimatedBackground>
  );
}

export default SignupPage;

