'use client';

import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import { Eye, EyeOff, ArrowLeft, ArrowRight, Mail, Lock, CheckCircle2 } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  sendForgotPasswordOtp,
  verifyForgotPasswordOtp,
  resetPasswordWithOtp,
} from '@/portals/public/api/registrationApi';
import { ZentroAuthAnimatedBackground } from './ZentroAuthAnimatedBackground';

const DELIVERY_WAREHOUSE_IMAGE =
  'https://images.unsplash.com/photo-1580674285054-bed31e145f59?auto=format&fit=crop&w=1200&q=80';

function parseRecoveryApiError(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { detail?: string; message?: string } | undefined;
    if (data?.detail && typeof data.detail === 'string') return data.detail;
    if (data?.message && typeof data.message === 'string' && data.message.trim() && data.message !== 'No message available') {
      return data.message;
    }
  }
  return friendlyApiError(error, fallback);
}

function maskEmail(val: string): string {
  const parts = val.split('@');
  if (parts.length !== 2) return val;
  const [local, domain] = parts;
  if (local.length <= 2) return `${local[0] || ''}***@${domain}`;
  return `${local[0]}***${local[local.length - 1]}@${domain}`;
}

export function ModernForgotPasswordPage() {
  const navigate = useNavigate();

  const [step, setStep] = useState<'email' | 'otp' | 'new-password' | 'success'>('email');
  const [email, setEmail] = useState('');
  const [otpDigits, setOtpDigits] = useState<string[]>(['', '', '', '', '', '']);
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);

  const otpInputRefs = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    if (resendCooldown <= 0) return;
    const interval = setInterval(() => setResendCooldown((c) => c - 1), 1000);
    return () => clearInterval(interval);
  }, [resendCooldown]);

  const otpCode = otpDigits.join('');

  // 1. Send OTP Request
  const handleSendOtp = async (event: React.FormEvent) => {
    event.preventDefault();
    setError('');
    const trimmedEmail = email.trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
      setError('Please enter a valid email address.');
      return;
    }

    setLoading(true);
    try {
      await sendForgotPasswordOtp(trimmedEmail);
      setStep('otp');
      setResendCooldown(60);
      setOtpDigits(['', '', '', '', '', '']);
      setTimeout(() => {
        otpInputRefs.current[0]?.focus();
      }, 100);
    } catch (err) {
      setError(
        parseRecoveryApiError(
          err,
          'Failed to send verification code. Please check your email and try again.'
        )
      );
    } finally {
      setLoading(false);
    }
  };

  // 2. Resend OTP
  const handleResend = async () => {
    if (resendCooldown > 0 || loading) return;
    setError('');
    setLoading(true);
    try {
      await sendForgotPasswordOtp(email.trim());
      setResendCooldown(60);
      setOtpDigits(['', '', '', '', '', '']);
      otpInputRefs.current[0]?.focus();
    } catch (err) {
      setError(parseRecoveryApiError(err, 'Failed to resend code. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  // 3. Verify OTP
  const handleVerifyOtp = async (event: React.FormEvent) => {
    event.preventDefault();
    setError('');
    if (otpCode.length !== 6) {
      setError('Please enter the complete 6-digit verification code.');
      return;
    }

    setLoading(true);
    try {
      await verifyForgotPasswordOtp({
        email: email.trim(),
        code: otpCode.trim(),
      });
      setStep('new-password');
    } catch (err) {
      setError(
        parseRecoveryApiError(
          err,
          'Invalid or expired verification code. Please check your code and try again.'
        )
      );
    } finally {
      setLoading(false);
    }
  };

  // 4. Reset Password
  const handleResetPassword = async (event: React.FormEvent) => {
    event.preventDefault();
    setError('');
    if (newPassword.length < 8) {
      setError('Password must be at least 8 characters long.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

    setLoading(true);
    try {
      await resetPasswordWithOtp({
        email: email.trim(),
        code: otpCode.trim(),
        newPassword,
      });
      setStep('success');
    } catch (err) {
      setError(parseRecoveryApiError(err, 'Failed to reset password. Please try requesting a new code.'));
    } finally {
      setLoading(false);
    }
  };

  // OTP 6-box input handlers
  const handleOtpDigitChange = (index: number, value: string) => {
    const cleaned = value.replace(/\D/g, '');
    if (!cleaned) {
      const next = [...otpDigits];
      next[index] = '';
      setOtpDigits(next);
      return;
    }

    const lastDigit = cleaned.slice(-1);
    const next = [...otpDigits];
    next[index] = lastDigit;
    setOtpDigits(next);
    setError('');

    // Advance focus to next cell
    if (index < 5) {
      otpInputRefs.current[index + 1]?.focus();
    }
  };

  const handleOtpKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace') {
      if (!otpDigits[index] && index > 0) {
        const next = [...otpDigits];
        next[index - 1] = '';
        setOtpDigits(next);
        otpInputRefs.current[index - 1]?.focus();
      } else {
        const next = [...otpDigits];
        next[index] = '';
        setOtpDigits(next);
      }
    } else if (e.key === 'ArrowLeft' && index > 0) {
      e.preventDefault();
      otpInputRefs.current[index - 1]?.focus();
    } else if (e.key === 'ArrowRight' && index < 5) {
      e.preventDefault();
      otpInputRefs.current[index + 1]?.focus();
    }
  };

  const handleOtpPaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const paste = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (!paste) return;

    const next = [...otpDigits];
    for (let i = 0; i < 6; i++) {
      next[i] = paste[i] || '';
    }
    setOtpDigits(next);
    setError('');

    const targetIndex = Math.min(paste.length, 5);
    otpInputRefs.current[targetIndex]?.focus();
  };

  return (
    <ZentroAuthAnimatedBackground>
      <div className="auth-card-enter relative z-10 flex flex-col md:flex-row w-full max-w-5xl min-h-[640px] bg-white rounded-3xl shadow-2xl overflow-hidden border border-orange-100/70">
        {/* Left Panel: Delivery Side Image */}
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
              alt="Zentro delivery parcel logistics"
              className="w-full h-full object-cover"
            />
          </div>
        </div>

        {/* Right Panel: Interactive Authentication Surface */}
        <div className="flex-1 p-8 md:p-12 flex flex-col justify-center">
          {step === 'success' ? (
            /* STEP 4: Success State */
            <div className="space-y-6">
              <div>
                <span className="block text-xs font-semibold tracking-[0.16em] text-orange-600 uppercase mb-3">
                  PASSWORD UPDATED
                </span>
                <div className="flex items-center gap-3 mb-3">
                  <div className="w-10 h-10 rounded-full bg-orange-50 border border-orange-200 flex items-center justify-center text-orange-600">
                    <CheckCircle2 className="w-6 h-6" />
                  </div>
                  <h1 className="text-3xl font-bold text-slate-900 leading-[1.15] tracking-tight">
                    Password Reset Successful!
                  </h1>
                </div>
                <p className="text-sm text-gray-500 leading-relaxed">
                  Your password has been changed successfully. You can now log in using your new credentials.
                </p>
              </div>

              <button
                type="button"
                onClick={() => navigate('/login')}
                className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl text-sm font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all flex items-center justify-center gap-2 shadow-sm"
              >
                <span>Proceed to Sign In</span>
                <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          ) : step === 'new-password' ? (
            /* STEP 3: Reset Password */
            <>
              <div className="mb-7">
                <span className="block text-xs font-semibold tracking-[0.16em] text-orange-600 uppercase mb-3">
                  SECURE YOUR ACCOUNT
                </span>
                <h1 className="text-3xl font-bold text-slate-900 leading-[1.15] tracking-tight mb-3">
                  Create a new password.
                </h1>
                <p className="text-sm text-gray-500">
                  Choose a new password for your Zentro account.
                </p>
              </div>

              <form onSubmit={handleResetPassword} noValidate className="space-y-4">
                {/* New Password */}
                <div>
                  <label htmlFor="new-password" className="block text-sm font-semibold text-gray-800 mb-1.5">
                    New password
                  </label>
                  <div className="relative">
                    <Lock className="w-4 h-4 text-gray-500 absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                    <input
                      id="new-password"
                      type={showPassword ? 'text' : 'password'}
                      autoComplete="new-password"
                      autoFocus
                      required
                      value={newPassword}
                      onChange={(e) => {
                        setNewPassword(e.target.value);
                        setError('');
                      }}
                      placeholder="Enter your new password"
                      className="w-full pl-10 pr-11 py-3 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                    />
                    <button
                      type="button"
                      aria-label={showPassword ? 'Hide password' : 'Show password'}
                      onClick={() => setShowPassword(!showPassword)}
                      className="absolute right-3 top-1/2 -translate-y-1/2 p-1 hover:bg-gray-200/60 rounded-full transition-colors"
                    >
                      {showPassword ? <EyeOff className="w-4 h-4 text-gray-500" /> : <Eye className="w-4 h-4 text-gray-500" />}
                    </button>
                  </div>
                </div>

                {/* Confirm Password */}
                <div>
                  <label htmlFor="confirm-password" className="block text-sm font-semibold text-gray-800 mb-1.5">
                    Confirm password
                  </label>
                  <div className="relative">
                    <Lock className="w-4 h-4 text-gray-500 absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                    <input
                      id="confirm-password"
                      type={showConfirmPassword ? 'text' : 'password'}
                      autoComplete="new-password"
                      required
                      value={confirmPassword}
                      onChange={(e) => {
                        setConfirmPassword(e.target.value);
                        setError('');
                      }}
                      placeholder="Re-enter your new password"
                      className="w-full pl-10 pr-11 py-3 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                    />
                    <button
                      type="button"
                      aria-label={showConfirmPassword ? 'Hide password' : 'Show password'}
                      onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                      className="absolute right-3 top-1/2 -translate-y-1/2 p-1 hover:bg-gray-200/60 rounded-full transition-colors"
                    >
                      {showConfirmPassword ? <EyeOff className="w-4 h-4 text-gray-500" /> : <Eye className="w-4 h-4 text-gray-500" />}
                    </button>
                  </div>
                </div>

                {error && (
                  <p role="alert" className="text-xs text-red-600 mt-1">
                    {error}
                  </p>
                )}

                {/* Reset Password Button */}
                <button
                  type="submit"
                  disabled={loading || !newPassword || !confirmPassword}
                  className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl text-sm font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all flex items-center justify-center gap-2 shadow-sm disabled:opacity-60"
                >
                  <span>{loading ? 'Resetting password...' : 'Reset password'}</span>
                  <ArrowRight className="w-4 h-4" />
                </button>
              </form>

              <div className="mt-6 text-center text-xs">
                <p className="text-gray-500">
                  Remembered your password?{' '}
                  <Link to="/login" className="text-orange-600 hover:text-orange-700 font-semibold">
                    Sign in
                  </Link>
                </p>
              </div>
            </>
          ) : step === 'otp' ? (
            /* STEP 2: Verification Code Screen with 6-Cell Grid */
            <>
              <div className="mb-7">
                <span className="block text-xs font-semibold tracking-[0.16em] text-orange-600 uppercase mb-3">
                  EMAIL VERIFICATION
                </span>
                <h1 className="text-3xl font-bold text-slate-900 leading-[1.15] tracking-tight mb-2">
                  Check your email.
                  <br />
                  <span className="italic font-normal">Enter your code.</span>
                </h1>
                <p className="text-sm text-gray-500 leading-relaxed">
                  Enter the 6-digit verification code sent to{' '}
                  <strong className="text-gray-800 font-medium">{maskEmail(email.trim())}</strong> to continue resetting your password.
                </p>
              </div>

              <form onSubmit={handleVerifyOtp} noValidate className="space-y-6">
                <div>
                  <label className="block text-sm font-semibold text-gray-800 mb-3">
                    Verification code
                  </label>
                  {/* 6 Individual Equal-Width OTP Boxes */}
                  <div className="flex items-center justify-between gap-2 md:gap-3" onPaste={handleOtpPaste}>
                    {otpDigits.map((digit, idx) => (
                      <input
                        key={idx}
                        ref={(el) => (otpInputRefs.current[idx] = el)}
                        type="text"
                        inputMode="numeric"
                        pattern="[0-9]*"
                        maxLength={1}
                        value={digit}
                        onChange={(e) => handleOtpDigitChange(idx, e.target.value)}
                        onKeyDown={(e) => handleOtpKeyDown(idx, e)}
                        className="w-11 h-13 sm:w-12 sm:h-14 text-center text-2xl font-bold font-mono bg-slate-50 border border-gray-200 rounded-xl text-gray-900 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all shadow-sm"
                        aria-label={`Digit ${idx + 1}`}
                      />
                    ))}
                  </div>
                </div>

                {error && (
                  <p role="alert" className="text-xs text-red-600">
                    {error}
                  </p>
                )}

                {/* Verify Code Button */}
                <button
                  type="submit"
                  disabled={loading || otpCode.length !== 6}
                  className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl text-sm font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all flex items-center justify-center gap-2 shadow-sm disabled:opacity-60"
                >
                  <span>{loading ? 'Verifying code...' : 'Verify code'}</span>
                  <ArrowRight className="w-4 h-4" />
                </button>
              </form>

              <div className="flex items-center justify-between text-xs pt-4 border-t border-gray-100 mt-6">
                <div className="text-gray-500">
                  Didn't receive the code?{' '}
                  <button
                    type="button"
                    onClick={handleResend}
                    disabled={resendCooldown > 0 || loading}
                    className="text-orange-600 hover:text-orange-700 font-semibold disabled:text-gray-400 disabled:cursor-not-allowed"
                  >
                    {resendCooldown > 0 ? `Resend code in ${resendCooldown}s` : 'Resend code'}
                  </button>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setStep('email');
                    setError('');
                  }}
                  className="text-gray-500 hover:text-gray-700 font-medium"
                >
                  Change email
                </button>
              </div>

              <div className="mt-5 text-center text-xs">
                <Link to="/login" className="text-gray-500 hover:text-orange-600 font-medium transition-colors">
                  ← Back to Sign in
                </Link>
              </div>
            </>
          ) : (
            /* STEP 1: Forgot Password (Email Screen) */
            <>
              <div className="mb-7">
                <span className="block text-xs font-semibold tracking-[0.16em] text-orange-600 uppercase mb-3">
                  ACCOUNT RECOVERY
                </span>
                <h1 className="text-3xl font-bold text-slate-900 leading-[1.15] tracking-tight mb-3">
                  Forgot your password?
                </h1>
                <p className="text-sm text-gray-500 leading-relaxed">
                  Enter the email address associated with your Zentro account and we will send you a 6-digit verification code to reset your password.
                </p>
              </div>

              <form onSubmit={handleSendOtp} noValidate className="space-y-5">
                <div>
                  <label htmlFor="forgot-email" className="block text-sm font-semibold text-gray-800 mb-1.5">
                    Email address
                  </label>
                  <div className="relative">
                    <Mail className="w-4 h-4 text-gray-500 absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                    <input
                      id="forgot-email"
                      type="email"
                      autoComplete="email"
                      autoFocus
                      required
                      value={email}
                      onChange={(e) => {
                        setEmail(e.target.value);
                        setError('');
                      }}
                      placeholder="you@example.com"
                      className="w-full pl-10 pr-4 py-3 bg-slate-50 border border-gray-200 rounded-xl text-sm text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none transition-all"
                    />
                  </div>
                </div>

                {error && (
                  <p role="alert" className="text-xs text-red-600">
                    {error}
                  </p>
                )}

                <button
                  type="submit"
                  disabled={loading}
                  className="w-full bg-neutral-900 text-white py-3.5 px-4 rounded-xl text-sm font-semibold hover:bg-neutral-800 active:scale-[0.99] transition-all flex items-center justify-center gap-2 shadow-sm disabled:opacity-60"
                >
                  <span>{loading ? 'Sending verification code...' : 'Send verification code'}</span>
                  <ArrowRight className="w-4 h-4" />
                </button>
              </form>

              <div className="mt-7 text-center text-xs">
                <p className="text-gray-500">
                  Remembered your password?{' '}
                  <Link to="/login" className="text-orange-600 hover:text-orange-700 font-semibold">
                    Sign in
                  </Link>
                </p>
              </div>
            </>
          )}
        </div>
      </div>
    </ZentroAuthAnimatedBackground>
  );
}

export default ModernForgotPasswordPage;
