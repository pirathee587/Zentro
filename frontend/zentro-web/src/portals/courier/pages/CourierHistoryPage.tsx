import React, { useState, useMemo } from 'react';

interface HistoryItem {
  id: string;
  date: string;
  reference: string;
  destination: string;
  status: 'Delivered' | 'Failed';
  proofOrReason: string;
}

const HISTORY_DATA: HistoryItem[] = [
  { id: 'h1', date: '26 Oct', reference: 'ZT-1A4D7KQ2MP', destination: 'Dehiwala', status: 'Delivered', proofOrReason: 'Recipient: K. Fernando' },
  { id: 'h2', date: '26 Oct', reference: 'ZT-5N8C2WX9RB', destination: 'Mount Lavinia', status: 'Delivered', proofOrReason: 'Recipient: N. Perera' },
  { id: 'h3', date: '26 Oct', reference: 'ZT-7V3H6TD1YJ', destination: 'Ratmalana', status: 'Failed', proofOrReason: 'Wrong address' },
  { id: 'h4', date: '25 Oct', reference: 'ZT-2M9B5QK4XE', destination: 'Moratuwa', status: 'Delivered', proofOrReason: 'Recipient: S. Gunawardena' },
  { id: 'h5', date: '25 Oct', reference: 'ZT-8R1F7ZN3CL', destination: 'Panadura', status: 'Delivered', proofOrReason: 'Recipient: T. Silva' },
  { id: 'h6', date: '24 Oct', reference: 'ZT-4J6P2VH8DW', destination: 'Kalutara', status: 'Delivered', proofOrReason: 'Recipient: P. Rajapaksa' },
  { id: 'h7', date: '24 Oct', reference: 'ZT-9X2K5MB7QA', destination: 'Wadduwa', status: 'Delivered', proofOrReason: 'Recipient: L. Mendis' },
  { id: 'h8', date: '24 Oct', reference: 'ZT-3C8T1RE6NG', destination: 'Horana', status: 'Delivered', proofOrReason: 'Recipient: H. Dias' },
  { id: 'h9', date: '24 Oct', reference: 'ZT-6D4Q9YW2UK', destination: 'Bandaragama', status: 'Failed', proofOrReason: 'No answer' },
  { id: 'h10', date: '23 Oct', reference: 'ZT-1H7N3BV5ZS', destination: 'Kesbewa', status: 'Delivered', proofOrReason: 'Recipient: A. Fonseka' },
  { id: 'h11', date: '23 Oct', reference: 'ZT-5K2W8CD4TM', destination: 'Piliyandala', status: 'Delivered', proofOrReason: 'Recipient: R. Kumar' },
  { id: 'h12', date: '21 Oct', reference: 'ZT-8B6F1XQ9JE', destination: 'Nugegoda', status: 'Delivered', proofOrReason: 'Recipient: D. Abeysekera' },
  { id: 'h13', date: '21 Oct', reference: 'ZT-2Y5M7HK3VP', destination: 'Maharagama', status: 'Delivered', proofOrReason: 'Recipient: C. Weerasinghe' },
  { id: 'h14', date: '18 Oct', reference: 'ZT-7Q3D9NB2LX', destination: 'Kottawa', status: 'Delivered', proofOrReason: 'Recipient: V. Samarasinghe' },
  { id: 'h15', date: '18 Oct', reference: 'ZT-4T8R6WC1HA', destination: 'Homagama', status: 'Failed', proofOrReason: 'Refused by recipient' },
  { id: 'h16', date: '12 Oct', reference: 'ZT-9E1K5ZM8DB', destination: 'Boralesgamuwa', status: 'Delivered', proofOrReason: 'Recipient: G. Peiris' },
  { id: 'h17', date: '12 Oct', reference: 'ZT-3N7V2QH6YC', destination: 'Dehiwala', status: 'Delivered', proofOrReason: 'Recipient: I. Cooray' },
  { id: 'h18', date: '05 Oct', reference: 'ZT-6P4X8TB3WR', destination: 'Moratuwa', status: 'Delivered', proofOrReason: 'Recipient: O. Senanayake' },
];

export function CourierHistoryPage() {
  const [filter, setFilter] = useState<'All' | 'Delivered' | 'Failed'>('All');
  const [period, setPeriod] = useState<string>('Last 30 days');

  const filteredItems = useMemo(() => {
    if (filter === 'All') return HISTORY_DATA;
    return HISTORY_DATA.filter((item) => item.status === filter);
  }, [filter]);

  return (
    <main className="sd-page-container" aria-label="Delivery History">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">History</h1>
          <p className="sd-page-subtitle">18 deliveries · 83% success</p>
        </div>
      </div>

      {/* Main Card with Controls and Table */}
      <div className="sd-card">
        {/* Filter Pills */}
        <div className="sd-filter-row" style={{ marginBottom: 20 }}>
          <div className="sd-filter-pills" role="tablist" aria-label="Filter deliveries by status">
            {(['All', 'Delivered', 'Failed'] as const).map((tab) => (
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
        </div>

        {/* Period Selector */}
        <div className="sd-period-select-wrap" style={{ marginBottom: 24 }}>
          <label htmlFor="history-period-select" className="sd-input-label">
            Period
          </label>
          <div className="sd-select-wrapper" style={{ maxWidth: 220 }}>
            <select
              id="history-period-select"
              className="sd-select"
              value={period}
              onChange={(e) => setPeriod(e.target.value)}
            >
              <option value="Last 7 days">Last 7 days</option>
              <option value="Last 30 days">Last 30 days</option>
              <option value="Last 90 days">Last 90 days</option>
            </select>
          </div>
        </div>

        {/* Deliveries Table */}
        <div className="sd-table-wrap">
          <table className="sd-table" aria-label="Courier historical deliveries">
            <thead>
              <tr>
                <th scope="col" style={{ width: '12%' }}>Date</th>
                <th scope="col" style={{ width: '22%' }}>Reference</th>
                <th scope="col" style={{ width: '24%' }}>Destination</th>
                <th scope="col" style={{ width: '16%' }}>Status</th>
                <th scope="col" style={{ width: '26%' }}>Proof or reason</th>
              </tr>
            </thead>
            <tbody>
              {filteredItems.map((row) => (
                <tr key={row.id}>
                  <td className="sd-cell-date">{row.date}</td>
                  <td className="sd-cell-ref">
                    <span className="sd-ref-code">{row.reference}</span>
                  </td>
                  <td className="sd-cell-bold">{row.destination}</td>
                  <td>
                    {row.status === 'Delivered' ? (
                      <span className="sd-badge-dark">Delivered</span>
                    ) : (
                      <span className="sd-badge-coral">Failed</span>
                    )}
                  </td>
                  <td className="sd-cell-mute">{row.proofOrReason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </main>
  );
}

