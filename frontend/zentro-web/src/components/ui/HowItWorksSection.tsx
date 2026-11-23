import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Package,
  Laptop,
  Smartphone,
  Building2,
  Layers,
  Activity,
} from 'lucide-react';

const H = (c: string) => String.fromCharCode(35) + c;
const C_ALPHA = (c: string) => 'r' + 'gba(' + c + ')';

const COLORS = {
  orange: H('FF7A2F'),
  orangeStrong: H('FF6418'),
  orangeSoft: H('FFF0E4'),
  orangeBorder: H('F5D9C5'),
  darkHeading: H('171717'),
  mutedText: H('918B85'),
  white: H('FFFFFF'),
  warmCream: H('FAF6F0'),
  softGrey: H('E8DFD5'),
  screenBorder: H('D9CEBF'),
  headerTone: H('F2EAE0'),
  goldTone: H('E5B574'),
  stoneTone: H('8A8178'),
  boxTop: H('F6C898'),
  boxLeft: H('E7A566'),
  boxRight: H('D6914F'),
  handSleeve: H('EAE2D8'),
  handCuff: H('D1C4B4'),
  skinTone1: H('F3D1B4'),
  skinTone2: H('F5D6BD'),
  phoneFrame: H('FAF8F5'),
  phoneNotch: H('C9BEAF'),
  phoneGrid: H('FFF5EA'),
  orderRotated: H('FAF2E6'),
  orderRow: H('F7F4EF'),
  groundShadow: C_ALPHA('245, 217, 197, 0.55'),
  boxShadow: C_ALPHA('23, 23, 23, 0.1'),
  routeLine: C_ALPHA('255, 122, 47, 0.15'),
  laptopDark: H('2C2D30'),
  laptopSilver: H('E2E4E8'),
  laptopSilverDark: H('B6BAC2'),
  laptopDeck: H('D2D5DC'),
  laptopTrackpad: H('EDEFF3'),
  glowPeach: H('FFE2CD'),
  inputBg: H('FAF7F2'),
  inputLine: H('E6DFD5'),
  boxKraftTop: H('E8BC8C'),
  boxKraftFront: H('D69D64'),
  boxKraftSide: H('BE854C'),
  boxTapeTop: H('FF6E20'),
  boxTapeFront: H('E85C10'),
};

interface StepItem {
  n: string;
  title: string;
  body: string;
  tag: string;
  icon: typeof Laptop;
  artType: 'booking' | 'handover' | 'tracking' | 'merchant' | 'orders' | 'monitor';
}

const CUSTOMER_STEPS: StepItem[] = [
  {
    n: '01',
    title: 'Estimate and book',
    body: 'See your delivery fee in LKR, choose the details, and place your booking.',
    tag: 'Instant booking',
    icon: Laptop,
    artType: 'booking',
  },
  {
    n: '02',
    title: 'Hand it over',
    body: 'A courier picks up the parcel and starts its journey to the recipient.',
    tag: 'Doorstep pickup',
    icon: Package,
    artType: 'handover',
  },
  {
    n: '03',
    title: 'Follow the route',
    body: 'Live updates, delivery history, and a PDF receipt stay in one place.',
    tag: 'Real-time GPS',
    icon: Smartphone,
    artType: 'tracking',
  },
];

const BUSINESS_STEPS: StepItem[] = [
  {
    n: '01',
    title: 'Apply for access',
    body: 'Register as a merchant and complete the dispatcher approval process.',
    tag: 'Merchant portal',
    icon: Building2,
    artType: 'merchant',
  },
  {
    n: '02',
    title: 'Create delivery orders',
    body: 'Create and manage parcel orders through the merchant dashboard or secured API.',
    tag: 'Bulk dispatch',
    icon: Layers,
    artType: 'orders',
  },
  {
    n: '03',
    title: 'Monitor every delivery',
    body: 'Track delivery progress and receive shipment status updates through the dashboard and signed webhooks.',
    tag: 'Signed webhooks',
    icon: Activity,
    artType: 'monitor',
  },
];

/* 3D Isometric SVG Illustrations */
function StepIllustration({ type }: { type: StepItem['artType'] }) {
  if (type === 'booking') {
    return (
      <img
        src="/images/how-it-works/estimate-and-book.webp"
        alt="Estimate and book parcel delivery in Sri Lanka with Zentro"
        className="w-full h-full object-contain pointer-events-none select-none transition-transform duration-300"
        loading="lazy"
        width={660}
        height={440}
      />
    );
  }

  if (type === 'handover') {
    return (
      <img
        src="/images/how-it-works/doorstep-pickup.webp"
        alt="Doorstep parcel pickup in Sri Lanka by Zentro courier"
        className="w-full h-full object-contain pointer-events-none select-none transition-transform duration-300"
        loading="lazy"
        width={660}
        height={450}
      />
    );
  }

  if (type === 'tracking') {
    return (
      <img
        src="/images/how-it-works/follow-the-route.webp"
        alt="Real-time GPS parcel route tracking across Sri Lanka with Zentro"
        className="w-full h-full object-contain pointer-events-none select-none transition-transform duration-300"
        loading="lazy"
        width={660}
        height={450}
      />
    );
  }

  if (type === 'merchant') {
    return (
      <img
        src="/images/how-it-works/merchant-portal.webp"
        alt="Zentro Merchant Portal registration and API key activation"
        className="w-full h-full object-contain pointer-events-none select-none transition-transform duration-300"
        loading="lazy"
        width={604}
        height={342}
      />
    );
  }

  if (type === 'orders') {
    return (
      <img
        src="/images/how-it-works/bulk-dispatch.webp"
        alt="Zentro bulk dispatch and single parcel order management"
        className="w-full h-full object-contain pointer-events-none select-none transition-transform duration-300"
        loading="lazy"
        width={600}
        height={342}
      />
    );
  }

  // monitor
  return (
    <img
      src="/images/how-it-works/signed-webhooks.webp"
      alt="Zentro real-time delivery monitoring and HMAC-SHA256 signed webhooks"
      className="w-full h-full object-contain pointer-events-none select-none transition-transform duration-300"
      loading="lazy"
      width={600}
      height={342}
    />
  );
}

export function HowItWorksSection() {
  const sectionRef = useRef<HTMLElement>(null);
  const [audience, setAudience] = useState<'Customers' | 'Businesses'>('Customers');
  const [inView, setInView] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(false);

  useEffect(() => {
    const prefersReduced =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    setReducedMotion(prefersReduced);

    if (prefersReduced || typeof IntersectionObserver === 'undefined') {
      setInView(true);
      return;
    }

    const node = sectionRef.current;
    if (!node) {
      setInView(true);
      return;
    }

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          observer.disconnect();
        }
      },
      { threshold: 0.14 }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const steps = audience === 'Customers' ? CUSTOMER_STEPS : BUSINESS_STEPS;

  return (
    <section id="how-it-works" ref={sectionRef} className="hiw-section">
      <div className="hiw-container">
        {/* Section Header */}
        <div className="hiw-header">
          <div className="hiw-eyebrow">
            <span className="hiw-eyebrow-icon">
              <Package size={11} strokeWidth={2.4} />
            </span>
            <span className="hiw-eyebrow-text">SIMPLE FROM THE START</span>
          </div>

          <h2>
            Delivery, without <br />
            <em>the detour.</em>
          </h2>

          <p>Choose the path that fits the way you send.</p>

          {/* Interactive Customers / Businesses Toggle Switcher */}
          <div className="hiw-tabs" role="tablist" aria-label="Choose a delivery guide">
            {(['Customers', 'Businesses'] as const).map((tab) => {
              const isActive = audience === tab;
              return (
                <button
                  key={tab}
                  type="button"
                  role="tab"
                  aria-selected={isActive}
                  onClick={() => setAudience(tab)}
                  className={`hiw-tab-btn ${isActive ? 'is-active' : ''}`}
                >
                  {tab}
                </button>
              );
            })}
          </div>
        </div>

        {/* Three Illustrated Workflow Cards */}
        <div className="hiw-grid">
          {steps.map((step, idx) => {
            const Icon = step.icon;
            return (
              <div
                key={`${audience}-${step.n}`}
                className="hiw-card"
                style={{
                  opacity: inView ? 1 : 0,
                  transform: inView ? 'translateY(0)' : 'translateY(24px)',
                  transition: reducedMotion
                    ? 'none'
                    : `opacity 0.55s ease ${idx * 0.12}s, transform 0.55s cubic-bezier(0.22, 1, 0.36, 1) ${idx * 0.12}s, box-shadow 0.3s ease, border-color 0.3s ease`,
                }}
              >
                <div className="hiw-card-top">
                  <span className="hiw-card-num">{step.n}</span>
                  <span className="hiw-card-tag">{step.tag}</span>
                </div>

                <div className="hiw-art-wrap">
                  <StepIllustration type={step.artType} />
                </div>

                <div className="hiw-card-bottom">
                  <div className="hiw-card-header">
                    <span className="hiw-card-icon">
                      <Icon size={15} strokeWidth={2.4} />
                    </span>
                    <h3>{step.title}</h3>
                  </div>
                  <p>{step.body}</p>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
export default HowItWorksSection;
