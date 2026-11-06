import { PricingRuleForm } from './PricingRuleForm';
import { QuotePreview } from './QuotePreview';
import { ZoneList } from './ZoneList';
import { usePricingRule } from './usePricing';

/**
 * Dispatcher "Zones & pricing" page: the live pricing rule (view and edit), the delivery zones (read-only) and a
 * quotation preview. Access is limited by the Dispatcher route guard; the backend enforces DISPATCHER on every
 * call and the page shows its 401/403 messages.
 */
export function ZonesPricingPage() {
  const rule = usePricingRule();
  const currency = rule.data?.currency ?? 'LKR';

  return (
    <main className="staff-main">
      <section className="staff-hero">
        <div>
          <span className="staff-eyebrow">PRICING ADMINISTRATION</span>
          <h1>Zones &amp; pricing</h1>
          <p>Review the delivery fee rule, check delivery zones and preview what a parcel will cost.</p>
        </div>
        <span className="staff-hero-status">Live pricing data</span>
      </section>

      <section className="staff-section" aria-labelledby="pricing-rule-heading">
        <div className="staff-section-heading">
          <div>
            <h2 id="pricing-rule-heading">Pricing rule</h2>
            <p>These amounts are used for every customer quote, booking and merchant order.</p>
          </div>
        </div>
        <PricingRuleForm />
      </section>

      <section className="staff-section" aria-labelledby="quote-preview-heading">
        <div className="staff-section-heading">
          <div>
            <h2 id="quote-preview-heading">Quotation preview</h2>
            <p>Calculated by the pricing service with the saved rule, exactly as customers see it.</p>
          </div>
        </div>
        <QuotePreview currency={currency} />
      </section>

      <section className="staff-section" aria-labelledby="zones-heading">
        <div className="staff-section-heading">
          <div>
            <h2 id="zones-heading">Delivery zones</h2>
            <p>Active zones that quotes and bookings can use.</p>
          </div>
        </div>
        <ZoneList />
      </section>
    </main>
  );
}
