import { useState, useEffect, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, Input } from '@/shared/ui';
import { friendlyApiError } from '@/shared/api/apiClient';
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

export function LoginPage() { return <ModernLoginPage kind="customer"/>; }
export function StaffLoginPage() { return <ModernLoginPage kind="staff"/>; }
export function MerchantLoginPage() { return <ModernLoginPage kind="merchant"/>; }

function GoogleSignInButton({returnTo,entryPoint}:{returnTo:string;entryPoint:'customer'|'merchant'}) {
  const {login}=useAuth();
  return <Button type="button" variant="secondary" className="google-login" onClick={()=>void login(returnTo,undefined,entryPoint,'google')}><span className="google-mark" aria-hidden="true">G</span>Continue with Google</Button>;
}

export function SignupPage() {
  const {login, register, user}=useAuth();
  const location=useLocation();
  const navigate=useNavigate();
  const page=location.pathname;
  const merchantApplication=page==='/register/merchant'||new URLSearchParams(location.search).get('next')==='merchant';
  const isRegisterPage=page==='/register'||page==='/register/customer'||page==='/register/merchant';
  const returnTo=merchantApplication?'/merchant/register-business':'/customer';
  const entryPoint=merchantApplication?'merchant':'customer';

  const [step, setStep] = useState<'form' | 'otp' | 'success'>('form');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [otpCode, setOtpCode] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);

  useEffect(() => {
    if (resendCooldown <= 0) return;
    const interval = setInterval(() => setResendCooldown((c) => c - 1), 1000);
    return () => clearInterval(interval);
  }, [resendCooldown]);

  if(!isRegisterPage && page!=='/signup') return <Navigate to="/register" replace/>;
  return <ModernSignupPage />;

  if (user) {
    return (
      <section className="auth-card" aria-labelledby="register-title">
        <span className="eyebrow">{merchantApplication ? 'ZENTRO BUSINESS' : 'WELCOME TO ZENTRO'}</span>
        <h1 id="register-title">
          {merchantApplication ? <>One account.<br/><em>More ways to move.</em></> : <>Make room for<br/><em>one less worry.</em></>}
        </h1>
        <p>You are already signed in. Continue to your account, or create a separate Zentro account.</p>
        <Button className="auth-submit" onClick={() => navigate(returnTo, { replace: true })}>
          Continue with this account <Arrow/>
        </Button>
        <Button type="button" variant="secondary" className="auth-submit" onClick={() => void register(returnTo, entryPoint)}>
          Create a different account <Arrow/>
        </Button>
        <p className="auth-switch">Already have an account? <Link to={merchantApplication ? '/merchant/login' : '/login'}>Sign in</Link></p>
      </section>
    );
  }

  const handleSendOtp = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    const trimmedName = name.trim();
    const trimmedEmail = email.trim();
    if (!trimmedName && !trimmedEmail && register) {
      void register(returnTo, entryPoint);
      return;
    }
    if (!trimmedName) {
      setError('Please enter your full name.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
      setError('Please enter a valid email address.');
      return;
    }
    if (password.length < 8) {
      setError('Password must be at least 8 characters long.');
      return;
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

    setLoading(true);
    try {
      await sendRegistrationOtp({ email: trimmedEmail, name: trimmedName, entryPoint });
      setStep('otp');
      setResendCooldown(60);
    } catch (err) {
      setError(authApiError(err, 'Failed to send verification code. Please check your email and try again.'));
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOtp = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    const cleanCode = otpCode.trim().replace(/\D/g, '');
    if (cleanCode.length !== 6) {
      setError('Please enter the 6-digit verification code.');
      return;
    }

    setLoading(true);
    try {
      await verifyRegistrationOtp({
        email: email.trim(),
        name: name.trim(),
        password,
        code: cleanCode,
        entryPoint,
      });
      setStep('success');
      setTimeout(() => {
        void login(returnTo, email.trim(), entryPoint);
      }, 1200);
    } catch (err) {
      setError(authApiError(err, 'Invalid or expired verification code.'));
    } finally {
      setLoading(false);
    }
  };

  const handleResend = async () => {
    if (resendCooldown > 0 || loading) return;
    setError('');
    setLoading(true);
    try {
      await sendRegistrationOtp({ email: email.trim(), name: name.trim(), entryPoint });
      setResendCooldown(60);
      setError('');
    } catch (err) {
      setError(authApiError(err, 'Failed to resend code. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  if (step === 'success') {
    return <section className="auth-card" aria-labelledby="success-title">
      <span className="eyebrow" style={{ color: 'var(--umber)' }}>ACCOUNT VERIFIED</span>
      <h1 id="success-title">Welcome to Zentro!</h1>
      <p>Your account has been created and verified. Connecting you to sign in...</p>
      <Button className="auth-submit" onClick={() => void login(returnTo, email.trim(), entryPoint)}>
        Continue to Sign In <Arrow/>
      </Button>
    </section>;
  }

  if (step === 'otp') {
    return <section className="auth-card" aria-labelledby="otp-title">
      <span className="eyebrow">VERIFY YOUR EMAIL</span>
      <h1 id="otp-title">Enter verification code</h1>
      <p>We sent a 6-digit verification code to <strong>{email.trim()}</strong>. Enter it below to activate your account.</p>

      <form className="auth-form" noValidate onSubmit={handleVerifyOtp}>
        <label htmlFor="otp-input">Verification Code
          <Input
            id="otp-input"
            type="text"
            inputMode="numeric"
            pattern="[0-9]*"
            maxLength={6}
            autoFocus
            required
            value={otpCode}
            placeholder="000000"
            style={{ fontSize: '26px', letterSpacing: '8px', textAlign: 'center', fontFamily: 'monospace' }}
            onChange={(e) => {
              setOtpCode(e.target.value.replace(/\D/g, ''));
              setError('');
            }}
          />
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <Button className="auth-submit" type="submit" disabled={loading}>
          {loading ? 'Verifying...' : 'Verify & Complete Registration'} <Arrow/>
        </Button>
      </form>

      <div style={{ marginTop: '16px', display: 'flex', justifyContent: 'space-between', fontSize: '13px' }}>
        <Button
          type="button"
          variant="secondary"
          onClick={handleResend}
          disabled={resendCooldown > 0 || loading}
          style={{ background: 'none', border: 'none', color: resendCooldown > 0 ? 'var(--stone)' : 'var(--bronze)', cursor: resendCooldown > 0 ? 'default' : 'pointer', padding: 0 }}
        >
          {resendCooldown > 0 ? `Resend code in ${resendCooldown}s` : 'Resend code'}
        </Button>
        <Button
          type="button"
          variant="secondary"
          onClick={() => { setStep('form'); setError(''); }}
          style={{ background: 'none', border: 'none', color: 'var(--muted)', cursor: 'pointer', padding: 0 }}
        >
          Change email address
        </Button>
      </div>

      <p className="auth-switch" style={{ marginTop: '20px' }}>
        Already have an account? <Link to={merchantApplication ? '/merchant/login' : '/login'}>Sign in</Link>
      </p>
    </section>;
  }

  return <section className="auth-card" aria-labelledby="register-title">
    <span className="eyebrow">{merchantApplication ? 'ZENTRO BUSINESS' : 'WELCOME TO ZENTRO'}</span>
    <h1 id="register-title">
      {merchantApplication ? <>One account.<br/><em>More ways to move.</em></> : <>Make room for<br/><em>one less worry.</em></>}
    </h1>
    <p>
      {merchantApplication
        ? 'Create your Zentro account with email verification, then submit your business details.'
        : 'Create one secure account for personal deliveries and business access.'}
    </p>

    <form className="auth-form" noValidate onSubmit={handleSendOtp}>
      <label htmlFor="reg-name">Full name
        <Input
          id="reg-name"
          type="text"
          autoComplete="name"
          autoFocus
          required
          value={name}
          onChange={(e) => { setName(e.target.value); setError(''); }}
          placeholder="e.g. Priyantha Silva"
        />
      </label>

      <label htmlFor="reg-email">Email address
        <Input
          id="reg-email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => { setEmail(e.target.value); setError(''); }}
          placeholder="you@example.com"
        />
      </label>

      <label htmlFor="reg-password">Password
        <div style={{ position: 'relative' }}>
          <Input
            id="reg-password"
            type={showPassword ? 'text' : 'password'}
            autoComplete="new-password"
            required
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(''); }}
            placeholder="At least 8 characters"
          />
          <Button
            type="button"
            variant="secondary"
            aria-label={showPassword ? 'Hide password' : 'Show password'}
            onClick={() => setShowPassword(!showPassword)}
            style={{
              position: 'absolute',
              right: '12px',
              top: '50%',
              transform: 'translateY(-50%)',
              background: 'none',
              border: 'none',
              color: 'var(--muted)',
              cursor: 'pointer',
              fontSize: '13px',
              padding: 0,
            }}
          >
            {showPassword ? 'Hide' : 'Show'}
          </Button>
        </div>
      </label>

      <label htmlFor="reg-confirm-password">Confirm password
        <Input
          id="reg-confirm-password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="new-password"
          required
          value={confirmPassword}
          onChange={(e) => { setConfirmPassword(e.target.value); setError(''); }}
          placeholder="Re-enter password"
        />
      </label>

      {error && <p className="form-error" role="alert">{error}</p>}

      <Button className="auth-submit" type="submit" disabled={loading}>
        {loading ? 'Sending code...' : (merchantApplication ? 'Create merchant account' : 'Create customer account')} <Arrow/>
      </Button>
    </form>

    <div className="auth-divider"><span>or</span></div>
    <GoogleSignInButton returnTo={returnTo} entryPoint={entryPoint}/>

    <p className="auth-switch">
      Already have an account? <Link to={merchantApplication ? '/merchant/login' : '/login'}>Sign in</Link>
    </p>
  </section>;
}

export function ForgotPasswordPage() {
  return <ModernForgotPasswordPage />;
}
