import React, { useState, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Calendar } from 'lucide-react';
import { useAuth } from '@/shared/auth/useAuth';
import { getDeliverySummary, getDailyDeliveries } from '../api/reportApi';

function toIsoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function getDefaultDates(daysBack: number) {
  const to = new Date();
  const from = new Date();
  from.setDate(to.getDate() - daysBack);
  return { from: toIsoDate(from), to: toIsoDate(to) };
}

export function ReportsPage() {
  const { user } = useAuth();
  const initialDates = useMemo(() => getDefaultDates(30), []);

  const [rangeType, setRangeType] = useState<'7' | '30'>('30');
  const [fromDate, setFromDate] = useState(initialDates.from);
  const [toDate, setToDate] = useState(initialDates.to);
  const [appliedFrom, setAppliedFrom] = useState(initialDates.from);
  const [appliedTo, setAppliedTo] = useState(initialDates.to);
  const [viewAsTable, setViewAsTable] = useState(false);

  const handleApply = (e: React.FormEvent) => {
    e.preventDefault();
    setAppliedFrom(fromDate);
    setAppliedTo(toDate);
  };

  const handleQuickRange = (days: '7' | '30') => {
    setRangeType(days);
    const d = getDefaultDates(days === '7' ? 7 : 30);
    setFromDate(d.from);
    setToDate(d.to);
    setAppliedFrom(d.from);
    setAppliedTo(d.to);
  };

  const summaryQuery = useQuery({
    queryKey: ['dispatcher', 'reports', 'summary', appliedFrom, appliedTo],
    enabled: Boolean(user?.accessToken && appliedFrom && appliedTo),
    queryFn: ({ signal }) => getDeliverySummary(appliedFrom, appliedTo, user?.accessToken, signal),
  });

  const dailyQuery = useQuery({
    queryKey: ['dispatcher', 'reports', 'daily', appliedFrom, appliedTo],
    enabled: Boolean(user?.accessToken && appliedFrom && appliedTo),
    queryFn: ({ signal }) => getDailyDeliveries(appliedFrom, appliedTo, user?.accessToken, signal),
  });

  const summary = summaryQuery.data;
  const isPending = summaryQuery.isPending || dailyQuery.isPending;

  // Format daily bars from real API data
  const deliveryBars = useMemo(() => {
    const days = dailyQuery.data?.days ?? [];
    if (!days.length) return [];

    const maxCount = Math.max(...days.map((d) => d.delivered), 1);
    return days.map((d, index) => {
      const parts = d.day.split('-');
      const label = parts.length === 3 ? `${parts[2]}/${parts[1]}` : d.day;
      const heightPercent = Math.max(Math.round((d.delivered / maxCount) * 85), 6);
      return {
        label,
        count: d.delivered,
        isHighlight: index === days.length - 1,
        heightPercent,
      };
    });
  }, [dailyQuery.data?.days]);

  const createdCount = summary?.created ?? 0;
  const deliveredCount = summary?.delivered ?? 0;
  const failedCount = summary?.failed ?? 0;
  const successRate = summary?.rate !== null && summary?.rate !== undefined
    ? `${Math.round(summary.rate * 100)}%`
    : '—';

  return (
    <main className="sd-page-container">
      {/* Title */}
      <section className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Reports</h1>
          <p className="sd-page-subtitle">
            Operational analytics and delivery performance overview.
          </p>
        </div>
      </section>

      {/* Date Filter Card */}
      <div className="sd-card sd-filter-card">
        <form onSubmit={handleApply} className="sd-report-filter-form">
          <div className="sd-report-filter-row">
            <div className="sd-report-pills">
              <button
                type="button"
                className={`sd-filter-pill ${rangeType === '7' ? 'is-active' : ''}`}
                onClick={() => handleQuickRange('7')}
              >
                Last 7 days
              </button>
              <button
                type="button"
                className={`sd-filter-pill ${rangeType === '30' ? 'is-active' : ''}`}
                onClick={() => handleQuickRange('30')}
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
          <strong className="sd-metric-val">{isPending ? '…' : createdCount}</strong>
        </div>

        <div className="sd-metric-card">
          <span className="sd-metric-label">Delivered</span>
          <strong className="sd-metric-val">{isPending ? '…' : deliveredCount}</strong>
        </div>

        <div className="sd-metric-card">
          <span className="sd-metric-label">Failed</span>
          <strong className="sd-metric-val">{isPending ? '…' : failedCount}</strong>
        </div>

        <div className="sd-metric-card">
          <span className="sd-metric-label">Success rate</span>
          <strong className="sd-metric-val">{isPending ? '…' : successRate}</strong>
        </div>
      </div>

      {/* Bottom 2 Columns: Deliveries Chart + Failure Reasons */}
      <div className="sd-grid-2col" style={{ marginTop: 24 }}>
        {/* Left: Deliveries Chart */}
        <div className="sd-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Deliveries</h2>
          </div>

          {deliveryBars.length === 0 ? (
            <div className="staff-panel staff-empty" style={{ margin: '24px 0', textAlign: 'center' }}>
              {isPending ? 'Loading delivery trends…' : 'No recorded deliveries in this date range.'}
            </div>
          ) : viewAsTable ? (
            <div className="sd-table-wrap" style={{ marginTop: 16 }}>
              <table className="sd-parcels-table">
                <thead>
                  <tr>
                    <th>Day</th>
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

          {deliveryBars.length > 0 && (
            <div style={{ marginTop: 20 }}>
              <button
                type="button"
                className="sd-text-link sd-toggle-table-btn"
                onClick={() => setViewAsTable((v) => !v)}
              >
                ▶ {viewAsTable ? 'View as chart' : 'View as table'}
              </button>
            </div>
          )}
        </div>

        {/* Right: Operational Note & Rates */}
        <div className="sd-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Operational summary</h2>
          </div>

          <div style={{ padding: '16px 0', fontSize: '14px', lineHeight: '1.8' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid var(--line, #eee)' }}>
              <span>Period</span>
              <strong>{appliedFrom} to {appliedTo}</strong>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid var(--line, #eee)' }}>
              <span>Total recorded</span>
              <strong>{createdCount} parcels</strong>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid var(--line, #eee)' }}>
              <span>Completed deliveries</span>
              <strong>{deliveredCount} delivered</strong>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0' }}>
              <span>Failed deliveries</span>
              <strong style={{ color: failedCount > 0 ? 'var(--err, #e53935)' : 'inherit' }}>{failedCount}</strong>
            </div>
          </div>

          <p className="sd-card-footnote" style={{ marginTop: 24 }}>
            Success rate = delivered / (delivered + failed). Shown as a dash when there are no completed deliveries.
          </p>
        </div>
      </div>
    </main>
  );
}
