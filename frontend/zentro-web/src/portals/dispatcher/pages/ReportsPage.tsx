import React, { useState } from 'react';
import { Calendar } from 'lucide-react';

export function ReportsPage() {
  const [rangeType, setRangeType] = useState<'7' | '30'>('30');
  const [fromDate, setFromDate] = useState('2026-09-28');
  const [toDate, setToDate] = useState('2026-10-27');
  const [viewAsTable, setViewAsTable] = useState(false);

  const handleApply = (e: React.FormEvent) => {
    e.preventDefault();
  };

  const deliveryBars = [
    { label: '28 Sept', count: 210, isHighlight: false, heightPercent: 82 },
    { label: '05 Oct', count: 215, isHighlight: false, heightPercent: 84 },
    { label: '12 Oct', count: 220, isHighlight: false, heightPercent: 86 },
    { label: '19 Oct', count: 212, isHighlight: false, heightPercent: 83 },
    { label: '26 Oct', count: 37, isHighlight: true, heightPercent: 22 },
  ];

  const failureReasons = [
    { reason: 'No answer', count: 27, percent: 51 },
    { reason: 'Wrong address', count: 16, percent: 30 },
    { reason: 'Refused by recipient', count: 11, percent: 21 },
  ];

  return (
    <main className="sd-page-container">
      {/* Title */}
      <section className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Reports</h1>
          <p className="sd-page-subtitle">
            Sample data · period 2026-09-28 to 2026-10-27 · as of 27 Oct 2026, 10:42
          </p>
        </div>
      </section>

      {/* Notice Banner */}
      <div className="sd-notice-box" role="status">
        The reports service is not available yet. This page shows sample data so the layout can be reviewed.
      </div>

      {/* Date Filter Card */}
      <div className="sd-card sd-filter-card">
        <form onSubmit={handleApply} className="sd-report-filter-form">
          <div className="sd-report-filter-row">
            <div className="sd-report-pills">
              <button
                type="button"
                className={`sd-filter-pill ${rangeType === '7' ? 'is-active' : ''}`}
                onClick={() => setRangeType('7')}
              >
                Last 7 days
              </button>
              <button
                type="button"
                className={`sd-filter-pill ${rangeType === '30' ? 'is-active' : ''}`}
                onClick={() => setRangeType('30')}
              >
                Last 30 days
              </button>
            </div>

            <div className="sd-date-inputs-group">
              <label className="sd-date-field">
                <span className="sd-field-label">From</span>
                <div className="sd-input-with-icon">
                  <input
                    type="date"
                    value={fromDate}
                    onChange={(e) => setFromDate(e.target.value)}
                    className="sd-date-input"
                  />
                  <Calendar size={16} className="sd-input-icon" />
                </div>
              </label>

              <label className="sd-date-field">
                <span className="sd-field-label">To</span>
                <div className="sd-input-with-icon">
                  <input
                    type="date"
                    value={toDate}
                    onChange={(e) => setToDate(e.target.value)}
                    className="sd-date-input"
                  />
                  <Calendar size={16} className="sd-input-icon" />
                </div>
              </label>
            </div>
          </div>

          <button type="submit" className="sd-btn-black sd-apply-btn">
            Apply range
          </button>
        </form>
      </div>

      {/* 4 Metric Cards Row */}
      <div className="sd-metrics-row">
        <div className="sd-metric-card">
          <span className="sd-metric-label">Created</span>
          <strong className="sd-metric-val">992</strong>
        </div>

        <div className="sd-metric-card">
          <span className="sd-metric-label">Delivered</span>
          <strong className="sd-metric-val">894</strong>
        </div>

        <div className="sd-metric-card">
          <span className="sd-metric-label">Failed</span>
          <strong className="sd-metric-val">53</strong>
        </div>

        <div className="sd-metric-card">
          <span className="sd-metric-label">Success rate</span>
          <strong className="sd-metric-val">94%</strong>
        </div>
      </div>

      {/* Bottom 2 Columns: Deliveries Chart + Failure Reasons */}
      <div className="sd-grid-2col" style={{ marginTop: 24 }}>
        {/* Left: Deliveries Chart */}
        <div className="sd-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Deliveries</h2>
          </div>

          {viewAsTable ? (
            <div className="sd-table-wrap" style={{ marginTop: 16 }}>
              <table className="sd-parcels-table">
                <thead>
                  <tr>
                    <th>Week</th>
                    <th>Deliveries</th>
                  </tr>
                </thead>
                <tbody>
                  {deliveryBars.map((b) => (
                    <tr key={b.label}>
                      <td>{b.label}</td>
                      <td>{b.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="sd-bar-chart-wrap">
              <div className="sd-bar-chart">
                {deliveryBars.map((bar) => (
                  <div key={bar.label} className="sd-bar-col">
                    <div className="sd-bar-track">
                      <div
                        className={`sd-bar-fill ${bar.isHighlight ? 'is-yellow' : 'is-dark'}`}
                        style={{ height: `${bar.heightPercent}%` }}
                        title={`${bar.label}: ${bar.count} deliveries`}
                      />
                    </div>
                    <span className="sd-bar-label">{bar.label}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div style={{ marginTop: 20 }}>
            <button
              type="button"
              className="sd-text-link sd-toggle-table-btn"
              onClick={() => setViewAsTable((v) => !v)}
            >
              ▶ {viewAsTable ? 'View as chart' : 'View as table'}
            </button>
          </div>
        </div>

        {/* Right: Failure Reasons */}
        <div className="sd-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Failure reasons</h2>
          </div>

          <div className="sd-failure-list">
            {failureReasons.map((item) => (
              <div key={item.reason} className="sd-failure-item">
                <div className="sd-failure-meta">
                  <span className="sd-failure-reason">{item.reason}</span>
                  <span className="sd-failure-count">{item.count}</span>
                </div>
                <div className="sd-progress-track">
                  <div
                    className="sd-progress-fill"
                    style={{ width: `${item.percent}%` }}
                    aria-valuenow={item.count}
                  />
                </div>
              </div>
            ))}
          </div>

          <p className="sd-card-footnote" style={{ marginTop: 32 }}>
            Success rate = delivered / (delivered + failed). Shown as a dash when there are no completed deliveries.
          </p>
        </div>
      </div>
    </main>
  );
}

