import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, ErrorState, Input } from '@/shared/ui';
import { ArrowIcon } from '../assets/ArrowIcon';
import { LandingIcon } from '../assets/LandingIcon';
import { DeliveryTruckArt } from '../assets/DeliveryTruckArt';
import { SriLankaRouteMap, BusinessHubIllustration } from '../assets/LandingIllustrations';
import { normalizePublicTrackingNumber } from '../utils/trackingNumber';

const Arrow = ArrowIcon;

type RevealFrom = 'left' | 'right' | 'up' | 'down' | 'zoom';
function Reveal({ children, from = 'up', delay = 0 }: { children: ReactNode; from?: RevealFrom; delay?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (typeof window.matchMedia !== 'function' || window.matchMedia('(prefers-reduced-motion: reduce)').matches || typeof IntersectionObserver === 'undefined') {
      node.classList.add('is-visible');
      return;
    }
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) {
        node.classList.add('is-visible');
        observer.disconnect();
      }
    }, { threshold: .12 });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return <div ref={ref} className={`reveal reveal-${from}`} style={{ ['--delay' as string]: `${delay}ms` }}>{children}</div>;
}

const benefits = [
  { icon: 'pin' as const, title: 'Islandwide island coverage', body: 'All 25 districts connected from Colombo express hubs.' },
  { icon: 'heart' as const, title: 'Care at every handoff', body: 'Dedicated couriers who treat every package with personal care.' },
  { icon: 'route' as const, title: 'Live door-to-door tracking', body: 'Real-time GPS updates, pickup confirmation, and digital POD.' }
];

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
    { icon: 'wallet' as const, title: 'Clear fee estimates', body: 'See the estimated delivery fee in LKR before you confirm a booking.' },
    { icon: 'route' as const, title: 'Live parcel tracking', body: 'Follow your delivery truck along Sri Lankan express routes with real-time waypoint notifications.' },
    { icon: 'receipt' as const, title: 'PDF receipts', body: 'Keep a downloadable record of your delivery booking with instant verification.' },
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

  return (
    <div className="landing">
      {/* Unified dark hero wrapper: eliminates any line artifacts between navbar and hero */}
      <div className="hero-wrap">
        <header className="site-nav">
          <Link to="/" className="brand"><span className="brand-mark">z</span>zentro</Link>
          <nav aria-label="Main navigation">
            <a href="#home">Home</a>
            <a href="#how-it-works">How It Works</a>
            <a href="#why-zentro">Why Zentro</a>
            <a href="#businesses">For Businesses</a>
            <a href="#faqs">FAQs</a>
          </nav>
          <div className="nav-actions">
            <Link to="/login" className="nav-login">Login</Link>
            <Link to="/register" className="button-link">Get Started <Arrow/></Link>
          </div>
        </header>

        <section id="home" className="hero">
          <div className="hero-copy">
            <span className="eyebrow"><i/>DELIVERY THAT FEELS CLOSER</span>
            <h1>Your parcel,<br/>in <em>good hands.</em></h1>
            <p>From your doorstep to theirs, Zentro brings thoughtful, fast delivery to every corner of Sri Lanka.</p>
            <div className="hero-actions">
              <Link to="/register" className="button-link button-large">Send a parcel <Arrow/></Link>
              <a href="#how-it-works" className="text-link">See how it works <Arrow/></a>
            </div>
            <div className="hero-proof">
              <div className="proof-icons">
                <span><LandingIcon name="route"/></span>
                <span><LandingIcon name="pin"/></span>
                <span><LandingIcon name="heart"/></span>
              </div>
              <span>Thoughtful delivery, made for Sri Lanka</span>
            </div>
          </div>

          <div className="phone-art" role="img" aria-label="Animated Zentro Delivery Truck in Transit">
            <DeliveryTruckArt variant="hero" />
            <span className="sun-disc" />
          </div>
          <span className="hero-stamp">COLOMBO<br/><b>•</b> SRI LANKA</span>
        </section>
      </div>

      <main id="main-content">
        <section id="key-features" className="feature-section section">
          <Reveal from="down">
            <div className="section-heading">
              <span className="eyebrow">THE DETAILS THAT HELP</span>
              <h2>Everything in<br/><em>good order.</em></h2>
              <p>Plan a delivery, stay informed, and keep a reliable record at every milestone.</p>
            </div>
          </Reveal>
          <div className="feature-grid">
            {features.map((feature, index) => (
              <Reveal key={feature.title} from="up" delay={index * 100}>
                <article className="feature-card">
                  <span className="feature-icon"><LandingIcon name={feature.icon}/></span>
                  <h3>{feature.title}</h3>
                  <p>{feature.body}</p>
                </article>
              </Reveal>
            ))}
          </div>
        </section>

        <section className="tracking-section">
          <Reveal from="zoom">
            <div className="tracking-inner">
              <span className="eyebrow">YOUR PARCEL, RIGHT HERE</span>
              <h2>A little peace<br/>of <em>mind.</em></h2>
              <p>Enter your tracking number to watch your delivery truck's real-time progress.</p>
              <form className="tracking-form" onSubmit={submit}>
                <label className="sr-only" htmlFor="tracking-code">Tracking number</label>
                <Input
                  id="tracking-code"
                  value={tracking}
                  onChange={(e) => {
                    setTracking(e.target.value);
                    setTrackError('');
                  }}
                  placeholder="e.g. ZTR-ABCDEFG234"
                  aria-invalid={Boolean(trackError)}
                  aria-describedby={trackError ? 'tracking-error' : undefined}
                />
                <Button type="submit">Track parcel <Arrow/></Button>
              </form>
              {trackError && <div id="tracking-error" aria-live="polite"><ErrorState message={trackError}/></div>}
              <small className="tracking-hint">ZTR- followed by 10 letters or numbers</small>
            </div>
          </Reveal>
          <div className="tracking-scribble" aria-hidden="true"><LandingIcon name="route"/></div>
        </section>

        <section id="how-it-works" className="section steps-section">
          <Reveal from="down">
            <div className="section-heading">
              <span className="eyebrow">SIMPLE FROM THE START</span>
              <h2>Delivery, without<br/><em>the detour.</em></h2>
              <p>Choose the path that fits the way you send.</p>
            </div>
          </Reveal>
          <div className="audience-tabs" role="tablist" aria-label="Choose a delivery guide">
            {(['Customers', 'Businesses'] as const).map((tab) => (
              <Button
                key={tab}
                role="tab"
                aria-selected={audience === tab}
                variant={audience === tab ? 'primary' : 'secondary'}
                onClick={() => setAudience(tab)}
              >
                {tab}
              </Button>
            ))}
          </div>
          <div className="step-grid">
            {steps.map((step, i) => (
              <Reveal key={`${audience}-${step.n}`} from="up" delay={i * 100}>
                <article className="step-card">
                  <span className="step-number">{step.n}</span>
                  <div className="step-icon">
                    <LandingIcon name={(['route', 'package', 'clock'] as const)[i]}/>
                  </div>
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                </article>
              </Reveal>
            ))}
          </div>
        </section>

        <section id="why-zentro" className="why-section">
          <Reveal from="left">
            <SriLankaRouteMap />
          </Reveal>
          <Reveal from="right">
            <div className="why-copy">
              <span className="eyebrow">A BETTER WAY TO DELIVER</span>
              <h2>Local roots.<br/><em>Thoughtful routes.</em></h2>
              <p>We believe a delivery is more than a package moving from A to B. It is a promise someone is counting on across every town and province.</p>
              <ul>
                {benefits.map((b) => (
                  <li key={b.title}>
                    <span className="benefit-icon"><LandingIcon name={b.icon}/></span>
                    <span><b>{b.title}</b><small>{b.body}</small></span>
                  </li>
                ))}
              </ul>
            </div>
          </Reveal>
        </section>

        <section id="businesses" className="business-section">
          <Reveal from="left">
            <div className="business-copy">
              <span className="eyebrow">FOR LOCAL BUSINESSES</span>
              <h2>Good things<br/>are <em>going places.</em></h2>
              <p>Apply for approval, manage high-volume delivery orders, and connect your ecommerce store with real-time REST API credentials and signed webhooks.</p>
              <Link to="/merchant" className="button-link">Partner with Zentro <Arrow/></Link>
            </div>
          </Reveal>
          <Reveal from="right">
            <BusinessHubIllustration />
          </Reveal>
        </section>

        <section id="faqs" className="faq-section">
          <Reveal from="zoom">
            <div className="section-heading">
              <span className="eyebrow">A FEW THINGS YOU MIGHT WONDER</span>
              <h2>Good to <em>know.</em></h2>
            </div>
          </Reveal>
          <div className="faq-list">
            {[
              ['Where does Zentro deliver?', 'Zentro delivers islandwide across all 25 districts in Sri Lanka with dedicated regional hubs connecting Colombo, Kandy, Galle, and beyond.'],
              ['How can I follow my parcel?', 'Use the tracking number provided upon booking confirmation. Enter it into our live tracking tool to watch your delivery truck in real-time.'],
              ['I run a business. How do I get started?', 'Register your business on the merchant portal. Once verified, you get API keys, bulk order dispatch tools, and webhook integrations.'],
              ['Can I keep a receipt?', 'Yes! Downloadable official PDF receipts with digital verification codes are available immediately in your dashboard.']
            ].map(([q, a]) => (
              <details key={q}>
                <summary>{q}<span>+</span></summary>
                <p>{a}</p>
              </details>
            ))}
          </div>
        </section>
      </main>

      <footer className="site-footer">
        <Link to="/" className="brand"><span className="brand-mark">z</span>zentro</Link>
        <span>Thoughtful delivery, made for Sri Lanka · LKR.</span>
        <div>
          <Link to="/login">Customer login</Link>
          <Link to="/staff/login">Staff login</Link>
          <Link to="/register/merchant">For merchants</Link>
        </div>
        <small>© {new Date().getFullYear()} Zentro Express. Made with care in Sri Lanka.</small>
      </footer>
    </div>
  );
}
