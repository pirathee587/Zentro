'use client';

import React, { useState, useEffect, useContext } from 'react';
import axios from 'axios';
import { Eye, EyeOff, ArrowLeft } from 'lucide-react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { AuthContext } from '@/shared/auth/authContext';
import { friendlyApiError } from '@/shared/api/apiClient';
import { sendRegistrationOtp, verifyRegistrationOtp } from '@/portals/public/api/registrationApi';
import { ZentroAuthAnimatedBackground } from './ZentroAuthAnimatedBackground';

const DELIVERY_HANDOVER_IMAGE =
  'https://images.unsplash.com/photo-1580674285054-bed31e145f59?auto=format&fit=crop&w=1200&q=80';

const H = (c: string) => String.fromCharCode(35) + c;

function parseAuthApiError(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { detail?: string; message?: string } | undefined;
    if (data?.detail && typeof data.detail === 'string') return data.detail;
    if (data?.message && typeof data.message === 'string' && data.message.trim() && data.message !== 'No message available') {
      return data.message;
    }
    if (error.response?.status === 409) {
      return 'An account with this email address already exists. Please sign in or use a different email.';
    }
  }
  return friendlyApiError(error, fallback);
}

export function SignupPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const auth = useContext(AuthContext);

  const page = location.pathname;
  const merchantApplication =
    page === '/register/merchant' || new URLSearchParams(location.search).get('next') === 'merchant';
  const returnTo = merchantApplication ? '/merchant/register-business' : '/customer';
  const entryPoint = merchantApplication ? 'merchant' : 'customer';

  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [step, setStep] = useState<'form' | 'otp' | 'success'>('form');
  const [otpCode, setOtpCode] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);

  const [formData, setFormData] = useState({
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    confirmPassword: '',
    agreeToTerms: false,
  });

  useEffect(() => {
    if (resendCooldown <= 0) return;
    const interval = setInterval(() => setResendCooldown((c) => c - 1), 1000);
    return () => clearInterval(interval);
  }, [resendCooldown]);

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
      void auth.login(returnTo, undefined, entryPoint, 'google');
    }
  };

  if (auth?.user) {
    return (
      <ZentroAuthAnimatedBackground>
        <div className="auth-card-enter relative z-10 w-full max-w-lg bg-white rounded-3xl shadow-2xl p-8 space-y-6 border border-orange-100/70">
          <h1 className="text-3xl font-bold text-gray-900">Already Signed In</h1>
          <p className="text-gray-600">
            You are already signed in. Continue to your account, or create a separate Zentro account.
          </p>
          <div className="space-y-3">
            <button
              type="button"
              onClick={() => navigate(returnTo, { replace: true })}
              className="w-full bg-neutral-900 text-white py-3 px-4 rounded-xl font-medium hover:bg-neutral-800 transition-colors"
            >
              Continue with this account
            </button>
            <button
              type="button"
              onClick={() => void auth.register(returnTo, entryPoint)}
              className="w-full bg-gray-100 text-gray-900 py-3 px-4 rounded-xl font-medium hover:bg-gray-200 transition-colors"
            >
              Create a different account
            </button>
          </div>
        </div>
      </ZentroAuthAnimatedBackground>
    );
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    const fullName = `${formData.firstName} ${formData.lastName}`.trim();
    const trimmedEmail = formData.email.trim();

    if (!fullName && !trimmedEmail && auth?.register) {
      void auth.register(returnTo, entryPoint);
      return;
    }

    if (!fullName) {
      setError('Please enter your first and last name.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
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
      await sendRegistrationOtp({ email: trimmedEmail, name: fullName, entryPoint });
      setStep('otp');
      setResendCooldown(60);
    } catch (err) {
      setError(parseAuthApiError(err, 'Failed to send verification code. Please check your email and try again.'));
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    const cleanCode = otpCode.trim().replace(/\D/g, '');
    if (cleanCode.length !== 6) {
      setError('Please enter the 6-digit verification code.');
      return;
    }
    setLoading(true);
    try {
      await verifyRegistrationOtp({
        email: formData.email.trim(),
        name: `${formData.firstName} ${formData.lastName}`.trim(),
        password: formData.password,
        code: cleanCode,
        entryPoint,
      });
      setStep('success');
      setTimeout(() => {
        if (auth?.login) {
          void auth.login(returnTo, formData.email.trim(), entryPoint);
        } else {
          navigate('/login');
        }
      }, 1200);
    } catch (err) {
      setError(parseAuthApiError(err, 'Invalid or expired verification code.'));
    } finally {
      setLoading(false);
    }
  };

  const handleResend = async () => {
    if (resendCooldown > 0 || loading) return;
    setError('');
    setLoading(true);
    try {
      await sendRegistrationOtp({
        email: formData.email.trim(),
        name: `${formData.firstName} ${formData.lastName}`.trim(),
        entryPoint,
      });
      setResendCooldown(60);
    } catch (err) {
      setError(parseAuthApiError(err, 'Failed to resend code. Please try again.'));
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
              onClick={() => navigate('/')}
              className="w-10 h-10 bg-black/20 backdrop-blur-sm rounded-full flex items-center justify-center hover:bg-black/30 transition-all"
            >
              <ArrowLeft className="w-5 h-5 text-white" />
            </button>
          </div>

          <div className="absolute inset-0">
            <img
              src={DELIVERY_HANDOVER_IMAGE}
              alt="Delivery person handing over parcel"
              className="w-full h-full object-cover"
            />
          </div>
        </div>

        {/* Right Panel */}
        <div className="flex-1 p-8 md:p-10 flex flex-col justify-center">
          {step === 'success' ? (
            <div className="space-y-6">
              <div>
                <span className="block text-xs font-semibold tracking-widest text-orange-600 uppercase mb-2">
                  ACCOUNT VERIFIED
                </span>
                <h1 className="text-3xl font-bold text-gray-900 mb-2">Welcome to Zentro!</h1>
                <p className="text-gray-600">
                  Your account has been created and verified. Connecting you to sign in...
                </p>
              </div>
              <button
                type="button"
                onClick={() => {
                  if (auth?.login) {
                    void auth.login(returnTo, formData.email.trim(), entryPoint);
                  } else {
                    navigate('/login');
                  }
                }}
                className="w-full bg-neutral-900 text-white py-3 px-4 rounded-xl font-semibold hover:bg-neutral-800 transition-colors"
              >
                Continue to Sign In
              </button>
            </div>
          ) : step === 'otp' ? (
            <div className="space-y-6">
              <div>
                <span className="block text-xs font-semibold tracking-widest text-orange-600 uppercase mb-2">
                  VERIFY YOUR EMAIL
                </span>
                <h1 className="text-3xl font-bold text-gray-900 mb-2">Enter verification code</h1>
                <p className="text-gray-600">
                  We sent a 6-digit verification code to <strong>{formData.email.trim()}</strong>. Enter it below to
                  activate your account.
                </p>
              </div>
              <form onSubmit={handleVerifyOtp} noValidate className="space-y-6">
                <div>
                  <label htmlFor="otpCode" className="block text-sm font-medium text-gray-700 mb-2">
                    Verification Code
                  </label>
                  <input
                    type="text"
                    id="otpCode"
                    inputMode="numeric"
                    maxLength={6}
                    value={otpCode}
                    onChange={(e) => {
                      setOtpCode(e.target.value.replace(/\D/g, ''));
                      setError('');
                    }}
                    placeholder="000000"
                    className="w-full px-4 py-3 border border-gray-300 rounded-xl text-center text-2xl tracking-widest font-mono focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                    required
                  />
                </div>
                {error && (
                  <p role="alert" className="text-sm text-red-600">
                    {error}
                  </p>
                )}
                <button
                  type="submit"
                  disabled={loading}
                  className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all shadow-sm"
                >
                  {loading ? 'Verifying...' : 'Verify & Complete Registration'}
                </button>
              </form>
              <div className="flex items-center justify-between text-sm pt-2">
                <button
                  type="button"
                  onClick={handleResend}
                  disabled={resendCooldown > 0 || loading}
                  className="text-orange-600 hover:text-orange-700 font-medium disabled:text-gray-400"
                >
                  {resendCooldown > 0 ? `Resend code in ${resendCooldown}s` : 'Resend code'}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setStep('form');
                    setError('');
                  }}
                  className="text-gray-500 hover:text-gray-700 font-medium"
                >
                  Change email address
                </button>
              </div>
            </div>
          ) : (
            <>
              <div className="mb-6">
                <span className="block text-xs font-semibold tracking-[0.16em] text-orange-600 uppercase mb-2">
                  {merchantApplication ? 'ZENTRO BUSINESS' : 'JOIN ZENTRO'}
                </span>
                <h1 className="text-3xl font-bold text-gray-900 mb-2">
                  {merchantApplication ? (
                    <>
                      One account.
                      <br />
                      <span className="italic font-normal">More ways to move.</span>
                    </>
                  ) : (
                    'Create an Account'
                  )}
                </h1>
                <p className="text-gray-600 text-sm">
                  {merchantApplication ? (
                    'Create your Zentro account with email verification, then submit your business details.'
                  ) : (
                    <>
                      Already have an account?{' '}
                      <Link
                        to="/login"
                        className="text-orange-600 hover:text-orange-700 font-semibold"
                      >
                        Log in
                      </Link>
                    </>
                  )}
                </p>
                {merchantApplication && (
                  <p className="text-gray-600 text-sm mt-1">
                    Already have an account?{' '}
                    <Link
                      to="/merchant/login"
                      className="text-orange-600 hover:text-orange-700 font-semibold"
                    >
                      Sign in
                    </Link>
                  </p>
                )}
              </div>

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
                    <button type="button" className="text-black font-semibold hover:underline">
                      Terms & Conditions
                    </button>
                  </label>
                </div>

                {error && (
                  <p role="alert" className="text-sm text-red-600">
                    {error}
                  </p>
                )}

                {/* Submit Button */}
                <button
                  type="submit"
                  disabled={loading}
                  aria-label={merchantApplication ? 'Create merchant account' : 'Create customer account'}
                  className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl text-sm font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all shadow-sm"
                >
                  {loading
                    ? 'Sending code...'
                    : merchantApplication
                      ? 'Create merchant account'
                      : 'Create Account'}
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
          )}
        </div>
      </div>
    </ZentroAuthAnimatedBackground>
  );
}

export default SignupPage;
