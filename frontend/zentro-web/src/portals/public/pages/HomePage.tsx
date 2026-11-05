import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, ErrorState, Input } from '@/shared/ui';
import { ArrowIcon } from '../assets/ArrowIcon';
import { LandingIcon } from '../assets/LandingIcon';
import { PhonePreview } from '../assets/PhonePreview';
import { normalizePublicTrackingNumber } from '../utils/trackingNumber';

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
    const code = normalizePublicTrackingNumber(tracking);
    if (!code) {
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
      <div className="nav-actions"><Link to="/login" className="nav-login">Login</Link><Link to="/register" className="button-link">Get Started <Arrow/></Link></div>
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
