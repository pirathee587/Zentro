import React, { useState, useMemo } from 'react';

interface WebhookDeliveryItem {
  id: string;
  orderRef: string;
  status: 'Retrying' | 'Delivered' | 'Failed';
  attemptsText: string;
  eventName: string;
  attempts: {
    attemptNumber: number;
    time: string;
    result: string;
  }[];
  nextRetryText?: string;
}

const WEBHOOK_DELIVERIES: WebhookDeliveryItem[] = [
  {
    id: 'dlv_6f21',
    orderRef: 'LT-5516',
    status: 'Retrying',
    attemptsText: '2 / 8',
    eventName: 'LT-5516 · Status updated: Out for delivery',
    attempts: [
      { attemptNumber: 1, time: '14:02:10', result: 'HTTP 503' },
      { attemptNumber: 2, time: '14:03:12', result: 'HTTP 503' },
    ],
    nextRetryText: 'Next retry around 14:05.',
  },
  {
    id: 'dlv_5e90',
    orderRef: 'LT-5521',
    status: 'Delivered',
    attemptsText: '1 / 8',
    eventName: 'LT-5521 · Status updated: In transit',
    attempts: [{ attemptNumber: 1, time: '11:42:03', result: 'HTTP 200' }],
  },
  {
    id: 'dlv_4d77',
    orderRef: 'LT-5515',
    status: 'Failed',
    attemptsText: '8 / 8',
    eventName: 'LT-5515 · Status updated: Picked up',
    attempts: [
      { attemptNumber: 7, time: '12:00:15', result: 'HTTP 500' },
      { attemptNumber: 8, time: '12:23:20', result: 'HTTP 500' },
    ],
  },
  {
    id: 'dlv_3c12',
    orderRef: 'LT-5514',
    status: 'Delivered',
    attemptsText: '1 / 8',
    eventName: 'LT-5514 · Status updated: Created',
    attempts: [{ attemptNumber: 1, time: '14:00:22', result: 'HTTP 200' }],
  },
  {
    id: 'dlv_2b08',
    orderRef: 'LT-5518',
    status: 'Delivered',
    attemptsText: '3 / 8',
    eventName: 'LT-5518 · Status updated: Failed',
    attempts: [
      { attemptNumber: 1, time: '15:45:00', result: 'Timeout' },
      { attemptNumber: 2, time: '15:50:00', result: 'HTTP 503' },
      { attemptNumber: 3, time: '16:00:10', result: 'HTTP 200' },
    ],
  },
  {
    id: 'dlv_1a55',
    orderRef: 'LT-5520',
    status: 'Delivered',
    attemptsText: '1 / 8',
    eventName: 'LT-5520 · Status updated: Delivered',
    attempts: [{ attemptNumber: 1, time: '16:45:30', result: 'HTTP 200' }],
  },
];

export function WebhooksPage() {
  const [endpointUrl, setEndpointUrl] = useState('https://hooks.lankathreads.lk/swiftdrop');
  const [createdEventChecked, setCreatedEventChecked] = useState(true);
  const [updatedEventChecked, setUpdatedEventChecked] = useState(true);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);

  const [filter, setFilter] = useState<'All' | 'Delivered' | 'Retrying' | 'Failed'>('All');
  const [selectedId, setSelectedId] = useState('dlv_6f21');

  const filteredDeliveries = useMemo(() => {
    if (filter === 'All') return WEBHOOK_DELIVERIES;
    return WEBHOOK_DELIVERIES.filter((d) => d.status === filter);
  }, [filter]);

  const selectedDelivery = useMemo(() => {
    return WEBHOOK_DELIVERIES.find((d) => d.id === selectedId) || WEBHOOK_DELIVERIES[0];
  }, [selectedId]);

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    setSaveNotice('Endpoint saved successfully.');
    setTimeout(() => setSaveNotice(null), 3000);
  };

  return (
    <main className="sd-page-container" aria-label="Webhooks Integration">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Webhooks</h1>
          <p className="sd-page-subtitle">
            Get notified at your endpoint whenever a parcel status changes.
          </p>
        </div>
      </div>

      {saveNotice && (
        <div className="sd-flash-notice" role="status" style={{ marginBottom: 20 }}>
          <span>{saveNotice}</span>
        </div>
      )}

      {/* 2-Column Grid */}
      <div className="sd-grid-2col" style={{ gap: 24, alignItems: 'start' }}>
        {/* Left Column: Endpoint, Signing secret, Retries */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
          {/* Card 1: Endpoint */}
          <section className="sd-card" aria-labelledby="endpoint-heading">
            <h2 id="endpoint-heading" className="sd-card-title">Endpoint</h2>

            <form onSubmit={handleSave} style={{ marginTop: 16 }}>
              <div className="sd-form-group">
                <label htmlFor="endpoint-url-input" className="sd-input-label">
                  HTTPS endpoint URL
                </label>
                <input
                  id="endpoint-url-input"
                  type="url"
                  className="sd-input"
                  value={endpointUrl}
                  onChange={(e) => setEndpointUrl(e.target.value)}
                  required
                />
              </div>

              {/* Events Checkboxes */}
              <div style={{ marginTop: 18 }}>
                <span className="sd-input-label">Events</span>
                <div className="sd-checkbox-group">
                  <label className="sd-checkbox-label">
                    <input
                      type="checkbox"
                      className="sd-checkbox"
                      checked={createdEventChecked}
                      onChange={(e) => setCreatedEventChecked(e.target.checked)}
                    />
                    <span>Parcel created</span>
                  </label>
                  <label className="sd-checkbox-label">
                    <input
                      type="checkbox"
                      className="sd-checkbox"
                      checked={updatedEventChecked}
                      onChange={(e) => setUpdatedEventChecked(e.target.checked)}
                    />
                    <span>Parcel status updated</span>
                  </label>
                </div>
              </div>

              <div style={{ display: 'flex', gap: 12, marginTop: 24, flexWrap: 'wrap' }}>
                <button type="submit" className="sd-btn-yellow">
                  Save endpoint
                </button>
                <button
                  type="button"
                  className="sd-btn-pill-light"
                  onClick={() => {
                    setSaveNotice('Test event dispatched to ' + endpointUrl);
                    setTimeout(() => setSaveNotice(null), 3500);
                  }}
                >
                  Send test event
                </button>
              </div>

              <p className="sd-cell-mute" style={{ fontSize: 12, marginTop: 16, marginBottom: 0 }}>
                Private and local addresses are blocked. Redirects are not followed.
              </p>
            </form>
          </section>

          {/* Card 2: Signing secret */}
          <section className="sd-card" aria-labelledby="secret-heading">
            <h2 id="secret-heading" className="sd-card-title">Signing secret</h2>

            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '16px 0 20px', fontSize: 14 }}>
              <span className="sd-cell-mute">Secret</span>
              <code style={{ fontFamily: 'monospace', fontSize: 13, color: 'var(--ink)' }}>
                whsec_••••••••••••
              </code>
            </div>

            <div>
              <button
                type="button"
                className="sd-btn-pill-light"
                onClick={() => {
                  setSaveNotice('New signing secret generated.');
                  setTimeout(() => setSaveNotice(null), 3000);
                }}
              >
                Generate new secret
              </button>
            </div>
          </section>

          {/* Card 3: Delivery and retries */}
          <section className="sd-card" aria-labelledby="retries-heading">
            <h2 id="retries-heading" className="sd-card-title">Delivery and retries</h2>

            <div className="sd-health-list" style={{ marginTop: 14 }}>
              <div className="sd-health-row">
                <span className="sd-health-label">Retried</span>
                <span className="sd-health-val" style={{ fontWeight: 500, fontSize: 13 }}>
                  408, 429, 5xx, timeouts
                </span>
              </div>
              <div className="sd-health-row">
                <span className="sd-health-label">Not retried</span>
                <span className="sd-health-val" style={{ fontWeight: 500, fontSize: 13 }}>
                  Other 4xx responses
                </span>
              </div>
              <div className="sd-health-row">
                <span className="sd-health-label">Maximum attempts</span>
                <span className="sd-health-val" style={{ fontWeight: 500, fontSize: 13 }}>
                  8, with growing delays
                </span>
              </div>
            </div>

            <p className="sd-cell-mute" style={{ fontSize: 12, marginTop: 18, lineHeight: 1.6 }}>
              Each event has a unique delivery id. Treat repeats as duplicates.
            </p>
            <p className="sd-cell-mute" style={{ fontSize: 12, marginTop: 6, lineHeight: 1.6, marginBottom: 0 }}>
              Payload fields: trackingRef, previousStatus, status, occurredAt, and failureReason when a delivery fails.
            </p>
          </section>
        </div>

        {/* Right Column: Deliveries Table and Attempts */}
        <section className="sd-card" aria-labelledby="deliveries-heading">
          <div className="sd-card-header" style={{ marginBottom: 16 }}>
            <h2 id="deliveries-heading" className="sd-card-title">Deliveries</h2>
          </div>

          {/* Filter Pills */}
          <div className="sd-filter-pills" role="tablist" aria-label="Filter webhook deliveries by status" style={{ marginBottom: 20 }}>
            {(['All', 'Delivered', 'Retrying', 'Failed'] as const).map((tab) => (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={filter === tab}
                className={`sd-filter-pill ${filter === tab ? 'is-active' : ''}`}
                onClick={() => setFilter(tab)}
              >
                {tab}
              </button>
            ))}
          </div>

          {/* Deliveries Table */}
          <div className="sd-table-wrap">
            <table className="sd-table" aria-label="Webhook deliveries list">
              <thead>
                <tr>
                  <th scope="col" style={{ width: '28%' }}>Delivery</th>
                  <th scope="col" style={{ width: '26%' }}>Order</th>
                  <th scope="col" style={{ width: '26%' }}>Status</th>
                  <th scope="col" style={{ width: '20%' }}>Attempts</th>
                </tr>
              </thead>
              <tbody>
                {filteredDeliveries.map((item) => (
                  <tr
                    key={item.id}
                    style={{ cursor: 'pointer' }}
                    onClick={() => setSelectedId(item.id)}
                  >
                    <td>
                      <span className="sd-cell-code">{item.id}</span>
                    </td>
                    <td className="sd-cell-bold">{item.orderRef}</td>
                    <td>
                      {item.status === 'Retrying' && <span className="sd-badge-yellow">Retrying</span>}
                      {item.status === 'Delivered' && <span className="sd-badge-dark">Delivered</span>}
                      {item.status === 'Failed' && <span className="sd-badge-coral">Failed</span>}
                    </td>
                    <td className="sd-cell-mute">{item.attemptsText}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Attempts Sub-Section */}
          <div className="sd-border-top-subtle" style={{ marginTop: 32, paddingTop: 24 }}>
            <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: 'var(--ink)' }}>
              Attempts for {selectedDelivery.id}
            </h3>
            <p className="sd-cell-mute" style={{ fontSize: 13, margin: '4px 0 16px' }}>
              {selectedDelivery.eventName}
            </p>

            <div className="sd-table-wrap">
              <table className="sd-table" aria-label="Delivery retry attempts">
                <thead>
                  <tr>
                    <th scope="col" style={{ width: '15%' }}>#</th>
                    <th scope="col" style={{ width: '45%' }}>Time</th>
                    <th scope="col" style={{ width: '40%' }}>Result</th>
                  </tr>
                </thead>
                <tbody>
                  {selectedDelivery.attempts.map((att) => (
                    <tr key={att.attemptNumber}>
                      <td className="sd-cell-bold">{att.attemptNumber}</td>
                      <td>{att.time}</td>
                      <td className="sd-cell-mute">{att.result}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {selectedDelivery.nextRetryText && (
              <p className="sd-cell-mute" style={{ fontSize: 13, marginTop: 14, marginBottom: 0 }}>
                {selectedDelivery.nextRetryText}
              </p>
            )}
          </div>
        </section>
      </div>
    </main>
  );
}
