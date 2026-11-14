import React, { useState } from 'react';
import { Button, Select } from '@/shared/ui';

interface BillingStatementItem {
  id: string;
  date: string;
  orderRef: string;
  trackingRef: string;
  parcelDesc: string;
  fee: string;
}

const STATEMENT_ITEMS: BillingStatementItem[] = [
  { id: 'b1', date: '27 Oct', orderRef: 'LT-5521', trackingRef: 'ZT-7K2M9QX4PA', parcelDesc: 'Medium · 2 kg', fee: 'LKR 1,072' },
  { id: 'b2', date: '26 Oct', orderRef: 'LT-5520', trackingRef: 'ZT-3HD8V1NC6R', parcelDesc: 'Small · 1 kg', fee: 'LKR 656' },
  { id: 'b3', date: '26 Oct', orderRef: 'LT-5519', trackingRef: 'ZT-9WQ4T7BE2X', parcelDesc: 'Small · 0.5 kg', fee: 'LKR 380' },
  { id: 'b4', date: '25 Oct', orderRef: 'LT-5518', trackingRef: 'ZT-5RN2K8YD1V', parcelDesc: 'Medium · 3 kg', fee: 'LKR 1,168' },
  { id: 'b5', date: '24 Oct', orderRef: 'LT-5517', trackingRef: 'ZT-2XB6M4QH8C', parcelDesc: 'Large · 6 kg', fee: 'LKR 2,016' },
  { id: 'b6', date: '24 Oct', orderRef: 'LT-5516', trackingRef: 'ZT-8FT1P5ZK3J', parcelDesc: 'Small · 1 kg', fee: 'LKR 656' },
  { id: 'b7', date: '23 Oct', orderRef: 'LT-5515', trackingRef: 'ZT-4CJ9W2LR7N', parcelDesc: 'Medium · 2 kg', fee: 'LKR 1,072' },
  { id: 'b8', date: '23 Oct', orderRef: 'LT-5514', trackingRef: 'ZT-6MV3D8HA9E', parcelDesc: 'Small · 0.5 kg', fee: 'LKR 380' },
  { id: 'b9', date: '21 Oct', orderRef: 'LT-5513', trackingRef: 'ZT-10K7S4TB5G', parcelDesc: 'Small · 1 kg', fee: 'LKR 656' },
  { id: 'b10', date: '19 Oct', orderRef: 'LT-5512', trackingRef: 'ZT-9DP5X1NW2Y', parcelDesc: 'Medium · 4 kg', fee: 'LKR 1,264' },
  { id: 'b11', date: '15 Oct', orderRef: 'LT-5511', trackingRef: 'ZT-3ZG8R6CE4U', parcelDesc: 'Large · 8 kg', fee: 'LKR 2,448' },
  { id: 'b12', date: '12 Oct', orderRef: 'LT-5510', trackingRef: 'ZT-7HN2L9VM6F', parcelDesc: 'Small · 0.5 kg', fee: 'LKR 380' },
];

export function BillingPage() {
  const [period, setPeriod] = useState('October 2026');

  return (
    <main className="sd-page-container" aria-label="Merchant Billing">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Billing</h1>
          <p className="sd-page-subtitle">
            Delivery fees for your orders as quoted at booking. Statement only; online payment is not available yet.
          </p>
        </div>
      </div>

      {/* 2-Column Grid */}
      <div className="sd-grid-2col" style={{ gap: 24, alignItems: 'start' }}>
        {/* Left Column: Statement Card */}
        <section className="sd-card" aria-labelledby="statement-heading">
          <h2 id="statement-heading" className="sd-card-title">Statement</h2>

          {/* Period selector */}
          <div style={{ margin: '16px 0 20px', maxWidth: 220 }}>
            <label htmlFor="billing-period-select" className="sd-input-label">
              Period
            </label>
            <div className="sd-select-wrapper" style={{ marginTop: 4 }}>
              <Select
                id="billing-period-select"
                className="sd-select"
                value={period}
                onChange={(e) => setPeriod(e.target.value)}
              >
                <option value="September 2026">September 2026</option>
                <option value="October 2026">October 2026</option>
                <option value="November 2026">November 2026</option>
              </Select>
            </div>
          </div>

          {/* Table */}
          <div className="sd-table-wrap">
            <table className="sd-table" aria-label="Monthly billing statement">
              <thead>
                <tr>
                  <th scope="col" style={{ width: '15%' }}>Date</th>
                  <th scope="col" style={{ width: '18%' }}>Order</th>
                  <th scope="col" style={{ width: '27%' }}>Tracking</th>
                  <th scope="col" style={{ width: '22%' }}>Parcel</th>
                  <th scope="col" style={{ width: '18%' }}>Fee</th>
                </tr>
              </thead>
              <tbody>
                {STATEMENT_ITEMS.map((row) => (
                  <tr key={row.id}>
                    <td className="sd-cell-date">{row.date}</td>
                    <td className="sd-cell-bold">{row.orderRef}</td>
                    <td className="sd-cell-ref">{row.trackingRef}</td>
                    <td className="sd-cell-mute">{row.parcelDesc}</td>
                    <td className="sd-cell-bold">{row.fee}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Bottom Total Row */}
          <div
            className="sd-border-top-subtle"
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginTop: 24,
              paddingTop: 18,
            }}
          >
            <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--ink)' }}>
              Total quoted fees
            </span>
            <span style={{ fontSize: 24, fontWeight: 700, color: 'var(--ink)' }}>
              LKR 12,148
            </span>
          </div>

          {/* Print button */}
          <div style={{ marginTop: 20 }}>
            <Button
              type="button"
              className="sd-btn-yellow"
              onClick={() => window.print()}
            >
              Print / Save as PDF
            </Button>
          </div>
        </section>

        {/* Right Column: Summary Card */}
        <section className="sd-card" aria-labelledby="summary-heading">
          <h2 id="summary-heading" className="sd-card-title">Summary</h2>

          {/* Stats */}
          <div className="sd-health-list" style={{ marginTop: 14, marginBottom: 28 }}>
            <div className="sd-health-row">
              <span className="sd-health-label">Orders</span>
              <span className="sd-health-val">12</span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Average fee</span>
              <span className="sd-health-val">LKR 1,012</span>
            </div>
          </div>

          {/* By parcel size header */}
          <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--ink)', marginBottom: 16 }}>
            By parcel size
          </div>

          {/* Breakdown bars */}
          <div className="sd-breakdown-row">
            <div className="sd-breakdown-header">
              <span className="sd-cell-mute">Small (6)</span>
              <span className="sd-cell-mute">LKR 3,108</span>
            </div>
            <div className="sd-breakdown-track">
              <div className="sd-breakdown-fill" style={{ width: '25.6%' }} />
            </div>
          </div>

          <div className="sd-breakdown-row">
            <div className="sd-breakdown-header">
              <span className="sd-cell-mute">Medium (4)</span>
              <span className="sd-cell-mute">LKR 4,576</span>
            </div>
            <div className="sd-breakdown-track">
              <div className="sd-breakdown-fill" style={{ width: '37.7%' }} />
            </div>
          </div>

          <div className="sd-breakdown-row">
            <div className="sd-breakdown-header">
              <span className="sd-cell-mute">Large (2)</span>
              <span className="sd-cell-mute">LKR 4,464</span>
            </div>
            <div className="sd-breakdown-track">
              <div className="sd-breakdown-fill" style={{ width: '36.7%' }} />
            </div>
          </div>

          <p className="sd-cell-mute" style={{ fontSize: 13, marginTop: 24, lineHeight: 1.6, marginBottom: 0 }}>
            Fees are the amounts returned by the pricing service when each order was booked.
          </p>
        </section>
      </div>
    </main>
  );
}
