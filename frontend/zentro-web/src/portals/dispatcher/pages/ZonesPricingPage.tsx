import React, { useState } from 'react';
import { PricingRuleForm } from './PricingRuleForm';
import { QuotePreview } from './QuotePreview';
import { ZoneList } from './ZoneList';
import { usePricingRule } from './usePricing';

export function ZonesPricingPage() {
  const rule = usePricingRule();
  const currency = rule.data?.currency ?? 'LKR';

  const [newZoneCode, setNewZoneCode] = useState('');
  const [newZoneName, setNewZoneName] = useState('');
  const [addNotice, setAddNotice] = useState<string | null>(null);

  const handleAddZone = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newZoneCode.trim() || !newZoneName.trim()) return;
    setAddNotice(`Zone ${newZoneCode.toUpperCase()} (${newZoneName}) added locally.`);
    setNewZoneCode('');
    setNewZoneName('');
    setTimeout(() => setAddNotice(null), 3000);
  };

  return (
    <main className="sd-page-container sd-zones-pricing-page">
      {/* Title */}
      <section className="sd-page-header">
        <div className="sd-page-title-group">
          <h1
            className="sd-page-title"
            aria-label="Zones & pricing"
          >
            Zones and pricing
          </h1>
          <p className="sd-page-subtitle">
            Delivery zones and the fee rules used for quotes and bookings.
          </p>
        </div>
      </section>

      {/* 2-Column Grid */}
      <div className="sd-grid-2col sd-zones-grid">
        {/* Left Column: Pricing Rules + Preview a Quote */}
        <div className="sd-card" role="region" aria-label="Pricing rule">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Pricing rules</h2>
          </div>

          <PricingRuleForm />

          <hr className="sd-card-divider" />

          <div style={{ marginTop: 24 }}>
            <div className="sd-card-header" style={{ marginBottom: 6 }}>
              <h2 className="sd-card-title" style={{ fontSize: '18px' }}>Preview a quote</h2>
            </div>
            <p className="sd-card-subtitle" style={{ margin: '0 0 16px' }}>
              Uses the values above. The real quote always comes from the pricing service.
            </p>

            <QuotePreview currency={currency} />
          </div>
        </div>

        {/* Right Column: Zones + Add a Zone */}
        <div className="sd-card" role="region" aria-label="Delivery zones">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Zones</h2>
          </div>

          <ZoneList />

          <hr className="sd-card-divider" />

          {/* Add a zone section */}
          <div style={{ marginTop: 24 }}>
            <h3 className="sd-card-title" style={{ fontSize: '16px', marginBottom: 14 }}>
              Add a zone
            </h3>

            {addNotice && (
              <p className="sd-flash-notice" role="status" style={{ marginBottom: 12 }}>
                {addNotice}
              </p>
            )}

            <form onSubmit={handleAddZone} className="sd-add-zone-form">
              <div className="sd-add-zone-fields">
                <label className="sd-zone-field">
                  <span className="sd-field-label">Code</span>
                  <input
                    type="text"
                    required
                    value={newZoneCode}
                    onChange={(e) => setNewZoneCode(e.target.value)}
                    placeholder="e.g. COL"
                    className="sd-input"
                  />
                </label>

                <label className="sd-zone-field">
                  <span className="sd-field-label">Name</span>
                  <input
                    type="text"
                    required
                    value={newZoneName}
                    onChange={(e) => setNewZoneName(e.target.value)}
                    placeholder="e.g. Colombo"
                    className="sd-input"
                  />
                </label>
              </div>

              <button
                type="submit"
                className="sd-btn-pill-light"
                style={{ marginTop: 14 }}
              >
                Add zone
              </button>
            </form>
          </div>
        </div>
      </div>
    </main>
  );
}
