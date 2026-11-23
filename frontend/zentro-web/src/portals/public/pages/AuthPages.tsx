import { useState, useEffect, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, Input } from '@/shared/ui';
import { friendlyApiError } from '@/shared/api/apiClient';
import { routeForRoles, type Role } from '@/shared/config/roles';
import { ArrowIcon } from '../assets/ArrowIcon';

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
  sendForgotPasswordOtp,
  verifyForgotPasswordOtp,
  resetPasswordWithOtp,
} from '../api/registrationApi';

const Arrow = ArrowIcon;

export function LoginPage() { return <AuthForm kind="customer" />; }
export function StaffLoginPage() { return <AuthForm kind="staff" />; }
export function MerchantLoginPage() { return <AuthForm kind="merchant" />; }

function GoogleSignInButton({ returnTo, entryPoint }: { returnTo: string; entryPoint: 'customer' | 'merchant' }) {
  const { login } = useAuth();
  return <Button type="button" variant="secondary" className="google-login" onClick={() => void login(returnTo, undefined, entryPoint, 'google')}><span className="google-mark" aria-hidden="true">G</span>Continue with Google</Button>;
}

export function SignupPage() {
  const { login, register, user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const page = location.pathname;
  const merchantApplication = page === '/register/merchant' || new URLSearchParams(location.search).get('next') === 'merchant';
  const isRegisterPage = page === '/register' || page === '/register/customer' || page === '/register/merchant';
  const returnTo = merchantApplication ? '/merchant/register-business' : '/customer';
  const entryPoint = merchantApplication ? 'merchant' : 'customer';

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

  if (!isRegisterPage) return <Navigate to="/register" replace />;

  if (user) {
    return (
      <section className="auth-card" aria-labelledby="register-title">
        <span className="eyebrow">{merchantApplication ? 'ZENTRO BUSINESS' : 'WELCOME TO ZENTRO'}</span>
        <h1 id="register-title">
          {merchantApplication ? <>One account.<br /><em>More ways to move.</em></> : <>Make room for<br /><em>one less worry.</em></>}
        </h1>
        <p>You are already signed in. Continue to your account, or create a separate Zentro account.</p>
        <Button className="auth-submit" onClick={() => navigate(returnTo, { replace: true })}>
          Continue with this account <Arrow />
        </Button>
        <Button type="button" variant="secondary" className="auth-submit" onClick={() => void register(returnTo, entryPoint)}>
          Create a different account <Arrow />
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
        Continue to Sign In <Arrow />
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
          {loading ? 'Verifying...' : 'Verify & Complete Registration'} <Arrow />
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
      {merchantApplication ? <>One account.<br /><em>More ways to move.</em></> : <>Make room for<br /><em>one less worry.</em></>}
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
        {loading ? 'Sending code...' : (merchantApplication ? 'Create merchant account' : 'Create customer account')} <Arrow />
      </Button>
    </form>

    <div className="auth-divider"><span>or</span></div>
    <GoogleSignInButton returnTo={returnTo} entryPoint={entryPoint} />

    <p className="auth-switch">
      Already have an account? <Link to={merchantApplication ? '/merchant/login' : '/login'}>Sign in</Link>
    </p>
  </section>;
}

function AuthForm({ kind }: { kind: 'customer' | 'staff' | 'merchant' }) {
  const { login, logout, user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const searchParams = new URLSearchParams(location.search);
  const initialEmail = searchParams.get('login_hint') ?? '';
  const [email, setEmail] = useState(initialEmail);
  const [emailError, setEmailError] = useState('');
  const from = kind === 'staff' ? '/staff/login' : kind === 'merchant' ? '/merchant/dashboard' : (location.state as { from?: string } | null)?.from ?? '/customer';
  const staff = kind === 'staff';
  const merchant = kind === 'merchant';

  const userPortalRoute = user ? routeForRoles(user.roles as Role[]) : null;
  const userPortalLabel = user?.roles.includes('DISPATCHER') ? 'Dispatch Desk'
    : user?.roles.includes('COURIER') ? 'Courier Dashboard'
      : user?.roles.includes('MERCHANT') ? 'Merchant Dashboard'
        : 'Customer Dashboard';

  if (user) {
    if (kind === 'customer') {
      const target = routeForRoles(user.roles as Role[]) ?? '/customer';
      return <Navigate to={target} replace />;
    }
    if (kind === 'merchant' && user.roles.includes('MERCHANT')) {
      return <Navigate to="/merchant/dashboard" replace />;
    }
    if (kind === 'staff') {
      if (user.roles.includes('DISPATCHER')) return <Navigate to="/dispatcher" replace />;
      if (user.roles.includes('COURIER')) return <Navigate to="/courier" replace />;
    }
  }

  const handleSubmit = async (event: FormEvent) => {
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

  return <section className="auth-card" aria-labelledby="auth-title">
    <span className="eyebrow">{staff ? 'ZENTRO TEAM' : merchant ? 'ZENTRO BUSINESS' : 'WELCOME BACK'}</span>
    <h1 id="auth-title">{staff ? 'Staff sign in' : merchant ? 'Merchant sign in' : 'Good to see you, again.'}</h1>
    <p>{staff ? 'Use the separate staff account provided by your Zentro administrator.' : merchant ? 'Sign in with your existing Zentro email and password to manage your business access.' : 'Sign in to see where your parcels are going.'}</p>
    {user && (
      <div style={{
        marginBottom: '16px',
        padding: '14px 16px',
        background: 'var(--warm-pale)',
        border: '1px solid var(--line)',
        borderRadius: '10px',
        fontSize: '13px',
        lineHeight: '1.5',
      }}>
        <strong style={{ color: 'var(--espresso)', display: 'block', marginBottom: '4px' }}>
          Currently signed in as {user.name || 'another account'}.
        </strong>
        <p style={{ margin: '0 0 10px 0', fontSize: '12px', color: 'var(--stone)' }}>
          {userPortalRoute
            ? `You have access to ${userPortalLabel}. You can proceed to your portal or sign in with another account below.`
            : `To sign in with another account, sign out first or continue below.`}
        </p>
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
          {userPortalRoute && (
            <Button
              type="button"
              onClick={() => navigate(userPortalRoute, { replace: true })}
              style={{ fontSize: '12px', padding: '6px 14px' }}
            >
              Go to {userPortalLabel} <Arrow />
            </Button>
          )}
          <Button
            type="button"
            variant="secondary"
            onClick={() => void logout?.(location.pathname)}
            style={{
              fontSize: '12px',
              padding: '6px 14px',
              borderColor: 'var(--line)',
            }}
          >
            Sign out
          </Button>
        </div>
      </div>
    )}
    <form className="auth-form" noValidate onSubmit={handleSubmit}>
      <label htmlFor="auth-username">{staff ? 'Staff email address' : 'Email address'}
        <Input id="auth-username" type="email" autoComplete="username" autoFocus required value={email} aria-invalid={Boolean(emailError)} aria-describedby={emailError ? 'auth-email-error' : 'auth-email-hint'} onChange={event => { setEmail(event.target.value); setEmailError(''); }} placeholder={staff ? 'your.work@zentro.lk' : 'you@example.com'} />
      </label>
      {emailError ? <p id="auth-email-error" className="form-error" role="alert" aria-live="polite">{emailError}</p> : <small id="auth-email-hint" className="auth-hint">Use your Zentro account email.</small>}
      <Button className="auth-submit" type="submit">Continue securely <Arrow /></Button>
    </form>
    {!staff && <div style={{ textAlign: 'center', marginTop: '10px' }}><Link to="/forgot-password" style={{ color: 'var(--umber)', fontSize: '13px', fontWeight: 500, textDecoration: 'none' }}>Forgot your password?</Link></div>}
    {!staff && <><div className="auth-divider"><span>or</span></div><GoogleSignInButton returnTo={from} entryPoint={merchant ? 'merchant' : 'customer'} /></>}
    {staff ? <p className="auth-switch">For dispatchers and couriers only. Staff accounts are created by Zentro.</p> : merchant ? <>
      <p className="auth-switch">New to Zentro? <Link to="/register?next=merchant">Create an account and apply</Link></p>
      <p className="auth-switch"><Link to="/merchant">Business information</Link></p>
    </> : <>
      <p className="auth-switch">New to Zentro? <Link to="/register">Create an account</Link></p>
      <p className="auth-switch"><Link to="/staff/login">Staff sign in</Link> | <Link to="/merchant/login">Merchant sign in</Link></p>
    </>}
  </section>;
}

export function ForgotPasswordPage() {
  const [step, setStep] = useState<'email' | 'otp' | 'new-password' | 'success'>('email');
  const [email, setEmail] = useState('');
  const [otpCode, setOtpCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);

  useEffect(() => {
    if (resendCooldown <= 0) return;
    const interval = setInterval(() => setResendCooldown((c) => c - 1), 1000);
    return () => clearInterval(interval);
  }, [resendCooldown]);

  const handleSendOtp = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    const cleanEmail = email.trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleanEmail)) {
      setError('Please enter a valid email address.');
      return;
    }
    setLoading(true);
    try {
      await sendForgotPasswordOtp(cleanEmail);
      setStep('otp');
      setResendCooldown(60);
    } catch (err) {
      setError(authApiError(err, 'Failed to send password reset code. Please check your email and try again.'));
    } finally {
      setLoading(false);
    }
  };

  const handleResend = async () => {
    if (resendCooldown > 0 || loading) return;
    setError('');
    setLoading(true);
    try {
      await sendForgotPasswordOtp(email.trim());
      setResendCooldown(60);
    } catch (err) {
      setError(authApiError(err, 'Failed to resend code. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOtp = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    if (otpCode.trim().length !== 6) {
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
      setError(authApiError(err, 'Invalid or expired verification code. Please check your code and try again.'));
    } finally {
      setLoading(false);
    }
  };

  const handleResetPassword = async (event: FormEvent) => {
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
      setError(authApiError(err, 'Failed to reset password. Please try requesting a new code.'));
    } finally {
      setLoading(false);
    }
  };

  if (step === 'success') {
    return (
      <section className="auth-card" aria-labelledby="success-title">
        <span className="eyebrow" style={{ color: 'var(--umber)' }}>PASSWORD UPDATED</span>
        <h1 id="success-title">Password Reset Successful!</h1>
        <p>Your password has been changed successfully. You can now log in using your new credentials.</p>
        <Link to="/login" style={{ textDecoration: 'none' }}>
          <Button className="auth-submit" type="button">
            Proceed to Sign In <Arrow />
          </Button>
        </Link>
      </section>
    );
  }

  if (step === 'new-password') {
    return (
      <section className="auth-card" aria-labelledby="new-pwd-title">
        <span className="eyebrow" style={{ color: 'var(--umber)' }}>CODE VERIFIED</span>
        <h1 id="new-pwd-title">Set new password</h1>
        <p>
          Your verification code has been confirmed. Enter your new password for <strong>{email.trim()}</strong> below.
        </p>

        <form className="auth-form" noValidate onSubmit={handleResetPassword}>
          <label htmlFor="new-password">New Password
            <div style={{ position: 'relative' }}>
              <Input
                id="new-password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="new-password"
                autoFocus
                required
                value={newPassword}
                onChange={(e) => { setNewPassword(e.target.value); setError(''); }}
                placeholder="At least 8 characters"
              />
              <Button
                type="button"
                variant="secondary"
                onClick={() => setShowPassword((v) => !v)}
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

          <label htmlFor="confirm-new-password">Confirm New Password
            <Input
              id="confirm-new-password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="new-password"
              required
              value={confirmPassword}
              onChange={(e) => { setConfirmPassword(e.target.value); setError(''); }}
              placeholder="Re-enter new password"
            />
          </label>

          {error && <p className="form-error" role="alert">{error}</p>}

          <Button className="auth-submit" type="submit" disabled={loading || !newPassword || !confirmPassword}>
            {loading ? 'Updating password...' : 'Update Password'} <Arrow />
          </Button>
        </form>

        <p className="auth-switch" style={{ marginTop: '24px' }}>
          Remembered your password? <Link to="/login">Sign in</Link>
        </p>
      </section>
    );
  }

  if (step === 'otp') {
    return (
      <section className="auth-card" aria-labelledby="otp-title">
        <span className="eyebrow">VERIFY CODE</span>
        <h1 id="otp-title">Enter verification code</h1>
        <p>
          We sent a 6-digit verification code to <strong>{email.trim()}</strong>. Enter it below to verify your request.
        </p>

        <form className="auth-form" noValidate onSubmit={handleVerifyOtp}>
          <label htmlFor="otp-input">6-Digit Verification Code
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

          <Button className="auth-submit" type="submit" disabled={loading || otpCode.length !== 6}>
            {loading ? 'Verifying code...' : 'Verify Code'} <Arrow />
          </Button>
        </form>

        <div style={{ marginTop: '16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Button
            type="button"
            variant="secondary"
            onClick={handleResend}
            disabled={resendCooldown > 0 || loading}
            style={{
              background: 'none',
              border: 'none',
              color: resendCooldown > 0 ? 'var(--stone)' : 'var(--umber)',
              cursor: resendCooldown > 0 ? 'not-allowed' : 'pointer',
              fontWeight: 500,
              fontSize: '14px',
              padding: 0,
            }}
          >
            {resendCooldown > 0 ? `Resend code in ${resendCooldown}s` : 'Resend Code'}
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={() => { setStep('email'); setError(''); }}
            style={{
              background: 'none',
              border: 'none',
              color: 'var(--muted)',
              cursor: 'pointer',
              fontSize: '14px',
              padding: 0,
            }}
          >
            Change email
          </Button>
        </div>

        <p className="auth-switch" style={{ marginTop: '24px' }}>
          Remembered your password? <Link to="/login">Sign in</Link>
        </p>
      </section>
    );
  }

  return (
    <section className="auth-card" aria-labelledby="forgot-title">
      <span className="eyebrow">ACCOUNT RECOVERY</span>
      <h1 id="forgot-title">Forgot your password?</h1>
      <p>Enter the email address associated with your Zentro account and we will send you a 6-digit verification code to reset your password.</p>

      <form className="auth-form" noValidate onSubmit={handleSendOtp}>
        <label htmlFor="forgot-email">Email address
          <Input
            id="forgot-email"
            type="email"
            autoComplete="email"
            autoFocus
            required
            value={email}
            onChange={(e) => { setEmail(e.target.value); setError(''); }}
            placeholder="you@example.com"
          />
        </label>

        {error && <p className="form-error" role="alert">{error}</p>}

        <Button className="auth-submit" type="submit" disabled={loading}>
          {loading ? 'Sending code...' : 'Send verification code'} <Arrow />
        </Button>
      </form>

      <p className="auth-switch">
        Remembered your password? <Link to="/login">Sign in</Link>
      </p>
    </section>
  );
}
