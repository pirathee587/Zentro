import { useEffect, useRef, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowIcon } from '../assets/ArrowIcon';
import { LandingIcon } from '../assets/LandingIcon';
import { SyntraHero } from '@/components/ui/syntra-hero';
import { HeroTrackingSearch } from '@/components/ui/HeroTrackingSearch';
import { HowItWorksSection } from '@/components/ui/HowItWorksSection';
import { ZentroChatbot } from '@/components/ui/ZentroChatbot';
import { ZentroLogo } from '@/components/ui/ZentroLogo';
import PushpinTearFooter from '@/components/ui/pushpin-tear-footer';
import { SriLankaDeliveryMap } from '@/components/ui/SriLankaDeliveryMap';

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
  { icon: 'pin' as const, title: 'Islandwide delivery network', body: 'Connecting customers and businesses across Sri Lanka.' },
  { icon: 'heart' as const, title: 'Care at every handoff', body: 'Helping couriers manage pickups, handovers, and delivery updates.' },
  { icon: 'route' as const, title: 'Real-time parcel tracking', body: 'Follow parcel status updates from pickup to final delivery.' }
];

export function HomePage() {

  return (
    <div className="landing">
      {/* Unified dark hero wrapper: eliminates any line artifacts between navbar and hero */}
      <div className="hero-wrap">
        <header className="site-nav">
          <Link to="/" className="brand" aria-label="Zentro Home">
            <span className="brand-mark brand-mark-logo">
              <ZentroLogo className="w-full h-full object-contain" />
            </span>
            zentro
          </Link>
          <nav aria-label="Main navigation">
            <a href="#home">Home</a>
            <a href="#how-it-works">How It Works</a>
            <a href="#why-zentro">Why Zentro</a>
            <a href="#businesses">For Businesses</a>
            <a
              href="#faqs"
              onClick={(e) => {
                e.preventDefault();
                window.dispatchEvent(new CustomEvent('zentro:open-chatbot'));
              }}
            >
              FAQs
            </a>
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

          <div className="phone-art">
            <SyntraHero />
            <span className="sun-disc" />
          </div>
          <span className="hero-stamp">COLOMBO<br/><b>•</b> SRI LANKA</span>
        </section>

        <HeroTrackingSearch />
      </div>

      <main id="main-content">
        <HowItWorksSection />

        <section id="why-zentro" className="why-section">
          <div className="why-header">
            <Reveal from="right">
              <span className="eyebrow"><i/>A BETTER WAY TO DELIVER</span>
              <h2>Local roots.<br/><em>Thoughtful routes.</em></h2>
              <p>We believe a delivery is more than a package moving from A to B. It&apos;s a promise someone is counting on across every town and province.</p>
            </Reveal>
          </div>
          <div className="why-map-col">
            <Reveal from="left">
              <SriLankaDeliveryMap />
            </Reveal>
          </div>
          <div className="why-features">
            <ul className="why-feature-list">
              {benefits.map((b, index) => (
                <li key={b.title}>
                  <Reveal from="up" delay={100 + index * 90}>
                    <div className="why-feature-card">
                      <span className="benefit-icon"><LandingIcon name={b.icon}/></span>
                      <div className="benefit-text">
                        <b>{b.title}</b>
                        <small>{b.body}</small>
                      </div>
                    </div>
                  </Reveal>
                </li>
              ))}
            </ul>
          </div>
        </section>
      </main>

      <PushpinTearFooter />
      <ZentroChatbot />
    </div>
  );
}
