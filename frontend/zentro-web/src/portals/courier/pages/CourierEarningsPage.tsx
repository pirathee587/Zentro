import React from 'react';

interface DailyDelivery {
  day: string;
  count: number;
  isToday?: boolean;
}

const DAILY_DELIVERIES: DailyDelivery[] = [
  { day: 'Wed', count: 2 },
  { day: 'Thu', count: 0 },
  { day: 'Fri', count: 2 },
  { day: 'Sat', count: 3 },
  { day: 'Sun', count: 2 },
  { day: 'Mon', count: 2 },
  { day: 'Tue', count: 1, isToday: true },
];

export function CourierEarningsPage() {
  const completed = 12;
  const failed = 3;
  const successRate = '80%';
  const maxCount = 3.5; // for proportional scaling so 3 fills ~85%

  return (
    <main className="sd-page-container" aria-label="Courier Earnings and Delivery Counts">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Earnings</h1>
          <p className="sd-page-subtitle">Your completed deliveries for the last 7 days.</p>
        </div>

        {/* Top Right Stat Counters */}
        <div className="sd-header-stats-group">
          <div className="sd-header-stat">
            <span className="sd-stat-number">{completed}</span>
            <span className="sd-stat-label">Completed</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">{failed}</span>
            <span className="sd-stat-label">Failed</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">{successRate}</span>
            <span className="sd-stat-label">Success rate</span>
          </div>
        </div>
      </div>

      {/* 2-Column Grid */}
      <div className="sd-grid-2col sd-courier-grid">
        {/* Left Column: Deliveries Per Day Bar Chart */}
        <section className="sd-card" aria-labelledby="chart-heading">
          <h2 id="chart-heading" className="sd-card-title">Deliveries per day</h2>

          <div className="sd-earnings-chart-container">
            <div className="sd-earnings-chart" role="region" aria-label="7-day delivery bar chart">
              {DAILY_DELIVERIES.map((item) => {
                const heightPercent = item.count === 0 ? 3 : Math.round((item.count / maxCount) * 100);
                return (
                  <div key={item.day} className="sd-chart-column">
                    <div className="sd-chart-bar-wrap">
                      <div
                        className={`sd-chart-bar ${item.isToday ? 'sd-bar-yellow' : 'sd-bar-dark'}`}
                        style={{ height: `${heightPercent}%` }}
                        aria-label={`${item.day}: ${item.count} deliveries`}
                      />
                    </div>
                    <span className="sd-chart-day-label">
                      {item.day} · {item.count}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        </section>

        {/* Right Column: Payouts */}
        <section className="sd-card" aria-labelledby="payouts-heading">
          <h2 id="payouts-heading" className="sd-card-title">Payouts</h2>
          <p className="sd-card-subtitle" style={{ fontSize: 14, lineHeight: 1.6, marginTop: 8 }}>
            Payout amounts will appear here once pay rules are set up. Until then this page shows your delivery counts only.
          </p>
        </section>
      </div>
    </main>
  );
}

