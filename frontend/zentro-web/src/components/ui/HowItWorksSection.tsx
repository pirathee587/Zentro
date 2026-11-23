import { useEffect, useRef, useState } from 'react';
import {
  Package,
  Laptop,
  Smartphone,
  Building2,
  Layers,
  Activity,
} from 'lucide-react';

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
