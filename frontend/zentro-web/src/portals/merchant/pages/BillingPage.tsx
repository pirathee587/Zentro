import React, { useState } from 'react';
import { Button, Input } from '@/shared/ui';
import { useMerchantBillingQuery } from '../hooks/useMerchantQueries';

function currentMonth() {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
}

export function BillingPage() {
  const [month, setMonth] = useState(currentMonth);
  const billing = useMerchantBillingQuery(month);
  const currency = billing.data?.currency;
  const format = (amount: number) => currency
    ? new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)
    : 'Unavailable';

  return (
    <main className="sd-page-container" aria-label="Merchant Billing">
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Billing</h1>
          <p className="sd-page-subtitle">
            Persisted delivery fees quoted when orders were booked. Invoice and payment records are not available.
          </p>
        </div>
      </div>
      <section className="sd-card" aria-labelledby="billing-statement-heading">
        <div className="sd-card-header">
          <h2 id="billing-statement-heading" className="sd-card-title">Monthly order fees</h2>
          <label className="sd-input-label" htmlFor="billing-month">Period</label>
          <Input id="billing-month" className="sd-select" type="month" value={month} onChange={event => setMonth(event.target.value)} />
        </div>
        {billing.isLoading && <p role="status" className="sd-cell-mute">Loading billing records…</p>}
        {billing.isError && <div role="alert"><p className="sd-cell-mute">Billing records could not be loaded.</p><Button type="button" variant="secondary" onClick={() => void billing.refetch()}>Retry</Button></div>}
        {billing.isSuccess && billing.data.orderCount === 0 && <p className="sd-cell-mute">No persisted order fee records for this month. Invoice and payment history is unavailable.</p>}
        {billing.isSuccess && billing.data.orders.length > 0 && (
          <>
            <div className="sd-table-wrap">
              <table className="sd-table" aria-label="Monthly order fees">
                <thead><tr><th scope="col">Date</th><th scope="col">Order</th><th scope="col">Tracking</th><th scope="col">Status</th><th scope="col">Quoted fee</th></tr></thead>
                <tbody>{billing.data.orders.map(order => <tr key={order.id}>
                  <td>{new Date(order.createdAt).toLocaleDateString()}</td><td>{order.externalOrderRef}</td><td>{order.trackingRef}</td><td>{order.status}</td><td>{format(order.fee)}</td>
                </tr>)}</tbody>
              </table>
            </div>
            <p className="sd-cell-bold" style={{ textAlign: 'right', marginTop: 20 }}>
              {billing.data.orderCount} orders · Total quoted fees: {format(billing.data.quotedFeeTotal)}
            </p>
          </>
        )}
      </section>
    </main>
  );
}
