import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, Input } from '@/shared/ui';
import { ArrowIcon } from '../assets/ArrowIcon';

const Arrow = ArrowIcon;

export function LoginPage() { return <AuthForm kind="customer"/>; }
export function StaffLoginPage() { return <AuthForm kind="staff"/>; }
export function MerchantLoginPage() { return <AuthForm kind="merchant"/>; }
function GoogleSignInButton({returnTo,entryPoint}:{returnTo:string;entryPoint:'customer'|'merchant'}) {
  const {login}=useAuth();
  return <Button type="button" variant="secondary" className="google-login" onClick={()=>void login(returnTo,undefined,entryPoint,'google')}><span className="google-mark" aria-hidden="true">G</span>Continue with Google</Button>;
}
export function SignupPage() {
  const {register,user}=useAuth();
  const location=useLocation();
  const navigate=useNavigate();
  const page=location.pathname;
  const merchantApplication=page==='/register/merchant'||new URLSearchParams(location.search).get('next')==='merchant';
  const isRegisterPage=page==='/register'||page==='/register/customer'||page==='/register/merchant';
  if(!isRegisterPage) return <Navigate to="/register" replace/>;
  const returnTo=merchantApplication?'/merchant/register-business':'/customer';
  const entryPoint=merchantApplication?'merchant':'customer';
  return <section className="auth-card" aria-labelledby="register-title">
    <span className="eyebrow">{merchantApplication?'ZENTRO BUSINESS':'WELCOME TO ZENTRO'}</span>
    <h1 id="register-title">{merchantApplication?<>One account.<br/><em>More ways to move.</em></>:<>Make room for<br/><em>one less worry.</em></>}</h1>
    <p>{user?'You are already signed in. Continue to your account, or create a separate Zentro account.':merchantApplication?'Create your Zentro account, then submit your business details for review.':'Create one secure account for your personal deliveries. You can use the same account to apply for merchant access.'}</p>
    <Button className="auth-submit" onClick={()=>{if(user) navigate(returnTo,{replace:true});else void register(returnTo,entryPoint);}}>{user?'Continue with this account':merchantApplication?'Create merchant account':'Create customer account'} <Arrow/></Button>
    {user&&<Button type="button" variant="secondary" className="auth-submit" onClick={()=>void register(returnTo,entryPoint)}>Create a different account <Arrow/></Button>}
    {!user&&<><div className="auth-divider"><span>or</span></div><GoogleSignInButton returnTo={returnTo} entryPoint={entryPoint}/></>}
    <p className="auth-switch">Already have an account? <Link to={merchantApplication?'/merchant/login':'/login'}>Sign in</Link></p>
  </section>;
}
function AuthForm({kind}:{kind:'customer'|'staff'|'merchant'}) {
  const {login}=useAuth();
  const [email,setEmail]=useState('');
  const [emailError,setEmailError]=useState('');
  const location=useLocation();
  const from=kind==='staff'?'/staff/login':kind==='merchant'?'/merchant/dashboard':(location.state as {from?:string}|null)?.from ?? '/customer';
  const staff=kind==='staff';
  const merchant=kind==='merchant';
  return <section className="auth-card" aria-labelledby="auth-title">
    <span className="eyebrow">{staff?'ZENTRO TEAM':merchant?'ZENTRO BUSINESS':'WELCOME BACK'}</span>
    <h1 id="auth-title">{staff?'Staff sign in':merchant?'Merchant sign in':'Good to see you, again.'}</h1>
    <p>{staff?'Use the separate staff account provided by your Zentro administrator.':merchant?'Sign in with your existing Zentro email and password to manage your business access.':'Sign in to see where your parcels are going.'}</p>
    <form className="auth-form" noValidate onSubmit={event=>{event.preventDefault();const value=email.trim();if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)){setEmailError('Enter a valid email address.');return;}setEmailError('');void login(from,value,kind);}}>
      <label htmlFor="auth-username">{staff?'Staff email address':'Email address'}
        <Input id="auth-username" type="email" autoComplete="username" autoFocus required value={email} aria-invalid={Boolean(emailError)} aria-describedby={emailError?'auth-email-error':'auth-email-hint'} onChange={event=>{setEmail(event.target.value);setEmailError('');}} placeholder={staff?'your.work@zentro.lk':'you@example.com'}/>
      </label>
      {emailError?<p id="auth-email-error" className="form-error" role="alert" aria-live="polite">{emailError}</p>:<small id="auth-email-hint" className="auth-hint">Use your Zentro account email.</small>}
      <Button className="auth-submit" type="submit">Continue securely <Arrow/></Button>
    </form>
    {!staff&&<><div className="auth-divider"><span>or</span></div><GoogleSignInButton returnTo={from} entryPoint={merchant?'merchant':'customer'}/></>}
    {staff?<p className="auth-switch">For dispatchers and couriers only. Staff accounts are created by Zentro.</p>:merchant?<>
      <p className="auth-switch">New to Zentro? <Link to="/register?next=merchant">Create an account and apply</Link></p>
      <p className="auth-switch"><Link to="/merchant">Business information</Link></p>
    </>:<>
      <p className="auth-switch">New to Zentro? <Link to="/register">Create an account</Link></p>
      <p className="auth-switch"><Link to="/staff/login">Staff sign in</Link> | <Link to="/merchant/login">Merchant sign in</Link></p>
    </>}
  </section>;
}
