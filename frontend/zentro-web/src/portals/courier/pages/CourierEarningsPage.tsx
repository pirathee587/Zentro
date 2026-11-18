import { Link } from 'react-router-dom';
import { Wallet, ArrowRight } from 'lucide-react';
import { CourierPage } from './CourierPageParts';

export function CourierEarningsPage() {
  return (
    <CourierPage
      title="Earnings"
      description="Your payout information, when an authorized payment feed is available."
    >
      <div className="cs-grid">
        <section className="cs-card cs-state" aria-labelledby="earnings-unavailable">
          <Wallet size={36} aria-hidden="true" />
          <h2 id="earnings-unavailable">Earnings unavailable</h2>
          <p>Earnings information is not available yet.</p>
          <p className="cs-muted">
            Balances, payment dates and payout history cannot be displayed yet. Contact dispatch for
            payment information.
          </p>
        </section>
        <section className="cs-card" aria-labelledby="review-deliveries">
          <h2 id="review-deliveries">Review your deliveries</h2>
          <p className="cs-muted">
            You can still check assignment progress and available proof records. Delivery counts do
            not establish a payout amount.
          </p>
          <div className="cs-actions">
            <Link to="/courier">
              Today dashboard <ArrowRight size={16} aria-hidden="true" />
            </Link>
            <Link to="/courier/history">
              View delivery records <ArrowRight size={16} aria-hidden="true" />
            </Link>
          </div>
        </section>
      </div>
    </CourierPage>
  );
}
