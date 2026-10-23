import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useNavigate, useLocation, useParams } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { apiClient, friendlyApiError } from '@/shared/api/apiClient';
import { Button, EmptyState, ErrorState, Input, Spinner } from '@/shared/ui';
import { useQuery } from '@tanstack/react-query';
import { getTracking } from '@/shared/domain/tracking/trackingApi';
import { StatusTimeline } from '@/shared/domain/parcel/StatusTimeline';
import type { ParcelStatus } from '@/shared/domain/parcel/types';
import { useTrackingStream } from '@/shared/domain/tracking/useTrackingStream';
import { ArrowIcon } from '../assets/ArrowIcon';
import { LandingIcon } from '../assets/LandingIcon';
import { PhonePreview } from '../assets/PhonePreview';
const Arrow = ArrowIcon;

type RevealFrom = 'left' | 'right' | 'up' | 'down' | 'zoom';
function Reveal({ children, from = 'up', delay = 0 }: { children: ReactNode; from?: RevealFrom; delay?: number }) { const ref=useRef<HTMLDivElement>(null); useEffect(()=>{const node=ref.current;if(!node)return;if(typeof window.matchMedia!=='function'||window.matchMedia('(prefers-reduced-motion: reduce)').matches||typeof IntersectionObserver==='undefined'){node.classList.add('is-visible');return;}const observer=new IntersectionObserver(([entry])=>{if(entry.isIntersecting){node.classList.add('is-visible');observer.disconnect();}},{threshold:.12});observer.observe(node);return()=>observer.disconnect();},[]);return <div ref={ref} className={`reveal reveal-${from}` } style={{ ['--delay' as string]: `${delay}ms` }}>{children}</div>; }
function PhoneArt() { return <div className="phone-art"><PhonePreview/><span className="float-tag"><LandingIcon name="heart"/>Every parcel, cared for</span><span className="sun-disc"/></div>; }

const benefits = [{ icon: 'pin' as const, title: 'Made for your city', body: 'Local knowledge brings your parcels closer, faster.' }, { icon: 'heart' as const, title: 'Care at every handoff', body: 'People who handle your delivery with attention.' }, { icon: 'route' as const, title: 'Know where it is', body: 'Straightforward updates, from pickup to doorstep.' }];

export function HomePage() {
  const [tracking, setTracking] = useState('');
  const [trackError, setTrackError] = useState('');
  const [audience, setAudience] = useState<'Customers' | 'Businesses'>('Customers');
  const navigate = useNavigate();
  const customerSteps = [
    { n: '01', title: 'Estimate and book', body: 'See your delivery fee in LKR, choose the details, and place your booking.' },
    { n: '02', title: 'Hand it over', body: 'A courier picks up the parcel and starts its journey to the recipient.' },
    { n: '03', title: 'Follow the route', body: 'Live updates, delivery history, and a PDF receipt stay in one place.' },
  ];
  const businessSteps = [
    { n: '01', title: 'Apply for access', body: 'Share your business details for dispatcher review and approval.' },
    { n: '02', title: 'Prepare your orders', body: 'Use your merchant tools and API credentials to create delivery orders.' },
    { n: '03', title: 'Receive signed updates', body: 'Track each order and verify delivery updates through signed webhooks.' },
  ];
  const features = [
    { icon: 'wallet' as const, title: 'Clear fee estimates', body: 'See the estimated delivery fee before you confirm a booking.' },
    { icon: 'route' as const, title: 'Live parcel tracking', body: 'Follow parcel status updates as they arrive, with a complete history.' },
    { icon: 'receipt' as const, title: 'PDF receipts', body: 'Keep a downloadable record of your delivery booking.' },
  ];
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const code = tracking.trim().toUpperCase();
    if (!/^ZTR-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$/.test(code)) {
      setTrackError('Enter a valid tracking number: ZTR- followed by 10 letters or numbers.');
      return;
    }
    navigate(`/track/${code}`);
  };
  const steps = audience === 'Customers' ? customerSteps : businessSteps;

  return <div className="landing">
    <header className="site-nav">
      <Link to="/" className="brand"><span className="brand-mark">z</span>zentro</Link>
      <nav aria-label="Main navigation">
        <a href="#home">Home</a><a href="#how-it-works">How It Works</a><a href="#why-zentro">Why Zentro</a><a href="#businesses">For Businesses</a><a href="#faqs">FAQs</a>
      </nav>
      <div className="nav-actions"><Link to="/register/merchant" className="nav-login">For Merchants</Link><Link to="/login" className="nav-login">Login</Link><Link to="/register" className="button-link">Get Started <Arrow/></Link></div>
    </header>
    <main id="home">
      <section className="hero">
        <div className="hero-copy"><span className="eyebrow"><i/>DELIVERY THAT FEELS CLOSER</span><h1>Your parcel,<br/>in <em>good hands.</em></h1><p>From your doorstep to theirs, Zentro brings thoughtful delivery to every corner of Sri Lanka.</p><div className="hero-actions"><Link to="/register" className="button-link button-large">Send a parcel <Arrow/></Link><a href="#how-it-works" className="text-link">See how it works <Arrow/></a></div><div className="hero-proof"><div className="proof-icons"><span><LandingIcon name="route"/></span><span><LandingIcon name="pin"/></span><span><LandingIcon name="heart"/></span></div><span>Thoughtful delivery, made for Sri Lanka</span></div></div>
        <PhoneArt/><span className="hero-stamp">COLOMBO<br/><b>•</b> SRI LANKA</span>
      </section>
      <section id="key-features" className="feature-section section"><Reveal from="down"><div className="section-heading"><span className="eyebrow">THE DETAILS THAT HELP</span><h2>Everything in<br/><em>good order.</em></h2><p>Plan a delivery, stay informed, and keep a useful record.</p></div></Reveal><div className="feature-grid">{features.map((feature,index)=><Reveal key={feature.title} from="up" delay={index*100}><article className="feature-card"><span className="feature-icon"><LandingIcon name={feature.icon}/></span><h3>{feature.title}</h3><p>{feature.body}</p></article></Reveal>)}</div></section>
      <section className="tracking-section"><Reveal from="zoom"><div className="tracking-inner"><span className="eyebrow">YOUR PARCEL, RIGHT HERE</span><h2>A little peace<br/>of <em>mind.</em></h2><p>Enter your tracking number to see the latest update on your delivery.</p><form className="tracking-form" onSubmit={submit}><label className="sr-only" htmlFor="tracking-code">Tracking number</label><Input id="tracking-code" value={tracking} onChange={(e)=>{setTracking(e.target.value);setTrackError('');}} placeholder="e.g. ZTR-ABCDEFG234" aria-invalid={Boolean(trackError)} aria-describedby={trackError?'tracking-error':undefined}/><Button type="submit">Track parcel <Arrow/></Button></form>{trackError&&<div id="tracking-error" aria-live="polite"><ErrorState message={trackError}/></div>}<small className="tracking-hint">ZTR- followed by 10 letters or numbers</small></div></Reveal><div className="tracking-scribble" aria-hidden="true"><LandingIcon name="route"/></div></section>
      <section id="how-it-works" className="section steps-section"><Reveal from="down"><div className="section-heading"><span className="eyebrow">SIMPLE FROM THE START</span><h2>Delivery, without<br/><em>the detour.</em></h2><p>Choose the path that fits the way you send.</p></div></Reveal><div className="audience-tabs" role="tablist" aria-label="Choose a delivery guide">{(['Customers','Businesses'] as const).map((tab)=><Button key={tab} role="tab" aria-selected={audience===tab} variant={audience===tab?'primary':'secondary'} onClick={()=>setAudience(tab)}>{tab}</Button>)}</div><div className="step-grid">{steps.map((step,i)=><Reveal key={`${audience}-${step.n}`} from="up" delay={i*100}><article className="step-card"><span className="step-number">{step.n}</span><div className="step-icon"><LandingIcon name={(['route','package','clock'] as const)[i]}/></div><h3>{step.title}</h3><p>{step.body}</p></article></Reveal>)}</div></section>
      <section id="why-zentro" className="why-section"><Reveal from="left"><div className="why-art" role="img" aria-label="Illustration of a parcel route across the city"><div className="map-lines"/><span className="pin pin-a"><LandingIcon name="pin"/></span><span className="pin pin-b"><LandingIcon name="pin"/></span><span className="map-note">A little closer,<br/>every time.</span><div className="map-route"/></div></Reveal><Reveal from="right"><div className="why-copy"><span className="eyebrow">A BETTER WAY TO DELIVER</span><h2>Local roots.<br/><em>Thoughtful routes.</em></h2><p>We believe a delivery is more than a package moving from A to B. It is a promise someone is counting on.</p><ul>{benefits.map((b)=><li key={b.title}><span className="benefit-icon"><LandingIcon name={b.icon}/></span><span><b>{b.title}</b><small>{b.body}</small></span></li>)}</ul></div></Reveal></section>
      <section id="businesses" className="business-section"><Reveal from="left"><div className="business-copy"><span className="eyebrow">FOR LOCAL BUSINESSES</span><h2>Good things<br/>are <em>going places.</em></h2><p>Apply for approval, manage delivery orders, and connect your systems with API credentials and signed webhooks.</p><Link to="/merchant" className="button-link">Partner with Zentro <Arrow/></Link></div></Reveal><Reveal from="right"><div className="business-card" role="img" aria-label="Illustration of a Zentro parcel"><div className="parcel-box"><span>z</span><i>HANDLE WITH CARE</i></div><span className="orbit orbit-one"/><span className="orbit orbit-two"/><div className="business-note"><span className="business-note-mark"><LandingIcon name="sparkle"/></span><span><b>Made for the way you work</b><small>Local delivery, one less thing to manage.</small></span></div></div></Reveal></section>
      <section id="faqs" className="faq-section"><Reveal from="zoom"><div className="section-heading"><span className="eyebrow">A FEW THINGS YOU MIGHT WONDER</span><h2>Good to <em>know.</em></h2></div></Reveal><div className="faq-list">{[['Where does Zentro deliver?','Delivery availability depends on your pickup and delivery locations. Enter your details during booking to check.'],['How can I follow my parcel?','Use the tracking number shared after your booking. Enter it above to see live updates and delivery history.'],['I run a business. How do I get started?','Register your business through the merchant page. A dispatcher reviews applications before merchant access is enabled.'],['Can I keep a receipt?','A PDF receipt is available for your delivery booking.']].map(([q,a])=><details key={q}><summary>{q}<span>+</span></summary><p>{a}</p></details>)}</div></section>
    </main>
    <footer className="site-footer"><Link to="/" className="brand"><span className="brand-mark">z</span>zentro</Link><span>Thoughtful delivery, made for Sri Lanka · LKR.</span><div><Link to="/login">Customer login</Link><Link to="/staff/login">Staff login</Link><Link to="/register/merchant">For merchants</Link></div><small>© {new Date().getFullYear()} Zentro. Made with care in Sri Lanka.</small></footer>
  </div>;
}
export function LoginPage() { return <AuthForm kind="customer"/>; }
export function StaffLoginPage() { return <AuthForm kind="staff"/>; }
export function MerchantLoginPage() { return <AuthForm kind="merchant"/>; }
export function SignupPage() {
  const {register,user}=useAuth();
  const location=useLocation();
  const navigate=useNavigate();
  const page=location.pathname;
  if(page==='/register') return <section className="auth-card" aria-labelledby="register-title">
    <span className="eyebrow">WELCOME TO ZENTRO</span><h1 id="register-title">How will you<br/><em>use Zentro?</em></h1>
    <p>Choose the account that fits. Every new account starts with customer access.</p>
    <Link className="button-link auth-submit" to="/register/customer">I'm a customer <Arrow/></Link>
    <Link className="button-link auth-submit" to="/register/merchant">I'm a business <Arrow/></Link>
    <p className="auth-switch">Already have an account? <Link to="/login">Sign in</Link></p>
  </section>;
  const merchantApplication=page==='/register/merchant'||new URLSearchParams(location.search).get('next')==='merchant';
  const isRegisterPage=page==='/register/customer'||page==='/register/merchant'||new URLSearchParams(location.search).has('next');
  if(!isRegisterPage) return <Navigate to="/register" replace/>;
  const returnTo=merchantApplication?'/merchant/register-business':'/customer';
  const entryPoint=merchantApplication?'merchant':'customer';
  return <section className="auth-card" aria-labelledby="register-title">
    <span className="eyebrow">{merchantApplication?'ZENTRO BUSINESS':'WELCOME TO ZENTRO'}</span>
    <h1 id="register-title">{merchantApplication?<>One account.<br/><em>More ways to move.</em></>:<>Make room for<br/><em>one less worry.</em></>}</h1>
    <p>{merchantApplication?'Create your Zentro account, then submit your business details for review.':'Create one secure account for your personal deliveries. You can use the same account to apply for merchant access.'}</p>
    <Button className="auth-submit" onClick={()=>{if(user) navigate(returnTo,{replace:true});else void register(returnTo,entryPoint);}}>{user?'Continue with this account':merchantApplication?'Create merchant account':'Create customer account'} <Arrow/></Button>
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
    {staff?<p className="auth-switch">For dispatchers and couriers only. Staff accounts are created by Zentro.</p>:merchant?<>
      <p className="auth-switch">New to Zentro? <Link to="/register?next=merchant">Create an account and apply</Link></p>
      <div className="auth-divider"><span>or</span></div>
      <p className="auth-switch"><Link to="/login">Customer sign in</Link> | <Link to="/merchant">Business information</Link></p>
    </>:<>
      <p className="auth-switch">New to Zentro? <Link to="/register">Create an account</Link></p>
      <div className="auth-divider"><span>or</span></div>
      <p className="auth-switch"><Link to="/staff/login">Staff sign in</Link> | <Link to="/merchant/login">Merchant sign in</Link></p>
    </>}
  </section>;
}

export function CallbackPage() { const {user,login,logout,loading}=useAuth(); const location=useLocation(); const [merchantStatus,setMerchantStatus]=useState<MerchantApplication|null|undefined>(undefined); const [error,setError]=useState(''); const [busy,setBusy]=useState(true); const [form,setForm]=useState({businessName:'',businessRegNo:'',contactPhone:''});
  if(!user) return loading?<section className="auth-card"><Spinner/><p>Finishing sign in...</p></section>:<section className="auth-card"><ErrorState message="We could not complete sign in. Please try again."/><Button onClick={()=>void login('/')}>Try again</Button></section>;
  const isMerchantApplicationPath=location.pathname==='/merchant/register-business'||location.pathname==='/merchant/status';
  const returnTo=isMerchantApplicationPath?location.pathname:user.returnTo ?? '/';
  const roles=user.roles;
  const entryPoint=isMerchantApplicationPath?'merchant':user.entryPoint ?? (returnTo.startsWith('/staff')||returnTo.startsWith('/admin')||returnTo.startsWith('/courier')?'staff':returnTo.startsWith('/merchant')?'merchant':'customer');
  const hasStaffRole=roles.includes('DISPATCHER')||roles.includes('COURIER');
  if(entryPoint==='staff') {
    if(roles.includes('DISPATCHER')) return <Navigate to="/admin" replace/>;
    if(roles.includes('COURIER')) return <Navigate to="/courier" replace/>;
    return <section className="auth-card"><span className="eyebrow">STAFF ACCESS</span><h1>This account is not enabled for staff.</h1><p>Staff sign-in requires a Zentro-issued dispatcher or courier account. Contact your administrator if you need staff access.</p><Button onClick={()=>void logout()}>Sign out</Button></section>;
  }
  if(entryPoint==='merchant') {
    if(hasStaffRole) return <section className="auth-card"><span className="eyebrow">BUSINESS ACCESS</span><h1>Use your staff sign-in.</h1><p>Staff accounts cannot use the merchant portal.</p><Button onClick={()=>void logout()}>Sign out</Button></section>;
    if(roles.includes('MERCHANT')) return <Navigate to="/merchant/dashboard" replace/>;
    if(roles.includes('CUSTOMER')) return <MerchantStatus merchantStatus={merchantStatus} setMerchantStatus={setMerchantStatus} error={error} setError={setError} busy={busy} setBusy={setBusy} form={form} setForm={setForm}/>;
  }
  if(entryPoint==='customer'&&!hasStaffRole&&roles.includes('CUSTOMER')) {
    const customerPath=returnTo==='/customer'||returnTo.startsWith('/customer/')?returnTo:'/customer';
    return <Navigate to={customerPath} replace/>;
  }
  return <section className="auth-card"><ErrorState message={error||'This account does not have access to a Zentro portal.'}/><Button onClick={()=>void logout()}>Sign out</Button><Button variant="secondary" onClick={()=>void login('/staff/login')}>Staff sign in</Button></section>;
}
type MerchantApplication = { status: 'PENDING'|'REJECTED'|'APPROVED'; rejectionReason?: string };
type MerchantForm = { businessName: string; businessRegNo: string; contactPhone: string };
type MerchantStatusProps = { merchantStatus: MerchantApplication|null|undefined; setMerchantStatus:(value:MerchantApplication|null)=>void; error:string; setError:(value:string)=>void; busy:boolean; setBusy:(value:boolean)=>void; form:MerchantForm; setForm:(value:MerchantForm)=>void };
function MerchantStatus({merchantStatus,setMerchantStatus,error,setError,busy,setBusy,form,setForm}:MerchantStatusProps) {
  const navigate=useNavigate();
  const refresh=useCallback(async()=>{setBusy(true);setError('');try{const {data}=await apiClient.get<MerchantApplication>('/api/v1/merchants/me');setMerchantStatus(data);if(window.location.pathname!=='/merchant/status')navigate('/merchant/status',{replace:true});}catch(e:unknown){if((e as {response?:{status?:number}})?.response?.status===404)setMerchantStatus(null);else setError(friendlyApiError(e,'We could not load your business status. Please try again.'));}finally{setBusy(false);}},[navigate,setBusy,setError,setMerchantStatus]);
  useEffect(()=>{void refresh();},[refresh]);
  if(busy&&merchantStatus===undefined)return <section className="auth-card"><Spinner/><p>Checking your business status...</p></section>;
  if(merchantStatus?.status==='APPROVED')return <section className="auth-card"><h1>Your business is approved.</h1><p>A dispatcher will assign merchant access to your account. Sign in again after access is enabled.</p><EmptyState message="Merchant access requires the MERCHANT role."/></section>;
  if(merchantStatus?.status==='PENDING')return <section className="auth-card"><h1>We're reviewing your details.</h1><p>Your business profile is pending review. We'll update this page when a decision is made.</p><Button variant="secondary" onClick={()=>void refresh()}>Refresh status</Button></section>;
  if(merchantStatus?.status==='REJECTED')return <section className="auth-card"><h1>A little more information, please.</h1><p>{merchantStatus.rejectionReason||'Your application was not approved. Please contact our team for next steps.'}</p><EmptyState message="Only a dispatcher can approve merchant access."/></section>;
  return <form className="auth-card" onSubmit={async e=>{e.preventDefault();const phone=form.contactPhone.replace(/[\s()-]/g,'');if(!/^\+?[0-9]{9,15}$/.test(phone)){setError('Enter a phone number with 9 to 15 digits.');return;}setBusy(true);setError('');try{const {data}=await apiClient.post<MerchantApplication>('/api/v1/merchants',{businessName:form.businessName.trim(),businessRegNo:form.businessRegNo.trim(),contactPhone:phone});setMerchantStatus(data);navigate('/merchant/status',{replace:true});}catch(err:unknown){setError(friendlyApiError(err,'We could not submit your business details. Please try again.'));}finally{setBusy(false);}}}><span className="eyebrow">BUSINESS REGISTRATION</span><h1>Let's get<br/><em>good things moving.</em></h1><label>Business name<Input required maxLength={140} value={form.businessName} onChange={e=>setForm({...form,businessName:e.target.value})}/></label><label>Business registration number<Input maxLength={80} value={form.businessRegNo} onChange={e=>setForm({...form,businessRegNo:e.target.value})}/></label><label>Contact phone<Input required type="tel" pattern="\+?[0-9]{9,15}" aria-describedby="contact-phone-help" value={form.contactPhone} onChange={e=>setForm({...form,contactPhone:e.target.value})}/><small id="contact-phone-help">Use 9 to 15 digits, with an optional + prefix.</small></label>{error&&<ErrorState message={error}/>}<Button className="auth-submit" disabled={busy}>{busy?<Spinner label="Submitting business details"/>:'Submit for review'}</Button></form>;
}
export function TrackingSearchPage() { return <section className="auth-card"><span className="eyebrow">PARCEL TRACKING</span><h1>Where's it<br/><em>headed?</em></h1><p>Enter the tracking number from your booking.</p><TrackForm/></section>; }
function TrackForm(){const [value,setValue]=useState('');const [error,setError]=useState('');const navigate=useNavigate();return <form onSubmit={e=>{e.preventDefault();const v=value.trim().toUpperCase();if(!/^ZTR-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$/.test(v)){setError('Enter a valid tracking number: ZTR- followed by 10 letters or numbers.');return;}navigate(`/track/${v}`);}}><Input aria-label="Tracking number" value={value} onChange={e=>{setValue(e.target.value);setError('');}} placeholder="ZTR-ABCDEFG234"/>{error&&<ErrorState message={error}/>}<Button className="auth-submit">Track parcel <Arrow/></Button></form>;}
export function TrackingPage() { const {trackingNumber=''}=useParams(); const navigate=useNavigate(); const tracking=useQuery({queryKey:['tracking',trackingNumber],queryFn:async()=> (await getTracking(trackingNumber)).data,retry:false}); const { refetch } = tracking; const refresh=useCallback(()=>{void refetch();},[refetch]); useTrackingStream(trackingNumber,refresh); const timeline=(tracking.data as {timeline?:ParcelStatus[]}|undefined)?.timeline; return <section className="auth-card"><span className="eyebrow">TRACKING UPDATE</span><h1>Your parcel's<br/><em>journey.</em></h1><p>Tracking number: <b>{trackingNumber}</b></p>{tracking.isPending?<Spinner label="Loading parcel updates"/>:tracking.isError?<ErrorState message="We couldn't load this parcel. Check the number and try again."/>:timeline?.length?<StatusTimeline statuses={timeline}/>:<EmptyState message="No tracking updates are available yet."/>}<Button variant="secondary" onClick={()=>navigate('/track')}>Track another parcel</Button></section>; }
export function MerchantPage(){const navigate=useNavigate();return <section className="auth-card"><span className="eyebrow">FOR SRI LANKAN BUSINESSES</span><h1>Let's deliver<br/><em>something good.</em></h1><p>Use your existing Zentro account to apply for business access. Once approved, sign in with the same email and password on the merchant page.</p><Button className="auth-submit" onClick={()=>navigate('/register?next=merchant')}>Create account and apply <Arrow/></Button><Button variant="secondary" onClick={()=>navigate('/merchant/login')}>I already have a Zentro account</Button><p className="auth-switch">Personal deliveries? <Link to="/login">Customer sign in</Link></p></section>; }
