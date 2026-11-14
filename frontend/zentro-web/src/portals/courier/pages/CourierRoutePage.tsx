import React from 'react';
import { Link } from 'react-router-dom';

interface RouteStop {
  id: string;
  order: number;
  city: string;
  address: string;
  reference: string;
  status: 'Delivered' | 'Failed' | 'In transit' | 'Picked up' | 'Assigned';
  dotType: 'completed' | 'failed' | 'active' | 'upcoming';
}

const ROUTE_STOPS: RouteStop[] = [
  {
    id: 'stop-1',
    order: 1,
    city: 'Nugegoda',
    address: '12 Temple Road',
    reference: 'ZT-4K8M2QX7PA',
    status: 'Delivered',
    dotType: 'completed',
  },
  {
    id: 'stop-2',
    order: 2,
    city: 'Maharagama',
    address: '48 High Level Road',
    reference: 'ZT-9HD3V6NC1R',
    status: 'Failed',
    dotType: 'failed',
  },
  {
    id: 'stop-3',
    order: 3,
    city: 'Boralesgamuwa',
    address: '7 Station Lane',
    reference: 'ZT-2WQ7T4BE8X',
    status: 'In transit',
    dotType: 'active',
  },
  {
    id: 'stop-4',
    order: 4,
    city: 'Piliyandala',
    address: '23 Moragahahena Road',
    reference: 'ZT-6RN5K1YD3V',
    status: 'Picked up',
    dotType: 'upcoming',
  },
  {
    id: 'stop-5',
    order: 5,
    city: 'Kottawa',
    address: '5 Galle Road',
    reference: 'ZT-8XB2M9QH4C',
    status: 'Assigned',
    dotType: 'upcoming',
  },
  {
    id: 'stop-6',
    order: 6,
    city: 'Homagama',
    address: '31 Highlevel Road',
    reference: 'ZT-3FT7P1ZK6J',
    status: 'Assigned',
    dotType: 'upcoming',
  },
];

export function CourierRoutePage() {
  const completedCount = 2; // 1 delivered + 1 failed
  const totalCount = ROUTE_STOPS.length;
  const remainingCount = totalCount - completedCount;
  const nextStop = 'Boralesgamuwa';
  const progressPercent = Math.round((completedCount / totalCount) * 100);

  return (
    <main className="sd-page-container" aria-label="Route Overview">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Route</h1>
          <p className="sd-page-subtitle">
            Today&apos;s stops in the order they were assigned. Open a stop in your maps app for directions.
          </p>
        </div>
      </div>

      {/* 2-Column Layout */}
      <div className="sd-grid-2col sd-courier-grid">
        {/* Left Column: Stops Timeline */}
        <section className="sd-card" aria-labelledby="stops-heading">
          <h2 id="stops-heading" className="sd-card-title">Stops</h2>

          <div className="sd-route-timeline" role="list">
            {ROUTE_STOPS.map((stop) => {
              const mapsUrl = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(
                `${stop.address}, ${stop.city}, Sri Lanka`
              )}`;

              return (
                <div key={stop.id} className="sd-timeline-item" role="listitem">
                  {/* Vertical Connector Line and Node Dot */}
                  <div className="sd-timeline-rail">
                    <span
                      className={`sd-timeline-dot sd-dot-${stop.dotType}`}
                      aria-hidden="true"
                    />
                    <span className="sd-timeline-line" aria-hidden="true" />
                  </div>

                  {/* Stop Information */}
                  <div className="sd-timeline-content">
                    <div className="sd-timeline-title">
                      {stop.order}. {stop.city}
                    </div>
                    <div className="sd-timeline-subtitle">
                      {stop.address} · {stop.reference}
                    </div>

                    <div className="sd-timeline-actions">
                      {stop.status === 'Delivered' && (
                        <span className="sd-badge-dark">Delivered</span>
                      )}
                      {stop.status === 'Failed' && (
                        <span className="sd-badge-coral">Failed</span>
                      )}
                      {stop.status === 'In transit' && (
                        <span className="sd-badge-yellow">In transit</span>
                      )}
                      {stop.status === 'Picked up' && (
                        <span className="sd-badge-yellow">Picked up</span>
                      )}
                      {stop.status === 'Assigned' && (
                        <span className="sd-badge-light">Assigned</span>
                      )}

                      <a
                        href={mapsUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="sd-btn-pill-light"
                      >
                        Open in Maps
                      </a>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        {/* Right Column: Progress */}
        <section className="sd-card" aria-labelledby="progress-heading">
          <h2 id="progress-heading" className="sd-card-title">Progress</h2>

          <div className="sd-progress-stats">
            <div className="sd-progress-row">
              <span className="sd-progress-label">Completed</span>
              <span className="sd-progress-val">{completedCount} of {totalCount}</span>
            </div>
            <div className="sd-progress-row">
              <span className="sd-progress-label">Remaining</span>
              <span className="sd-progress-val">{remainingCount}</span>
            </div>
            <div className="sd-progress-row">
              <span className="sd-progress-label">Next stop</span>
              <span className="sd-progress-val">{nextStop}</span>
            </div>
          </div>

          {/* Progress Bar */}
          <div
            className="sd-progress-track"
            role="progressbar"
            aria-valuenow={progressPercent}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="Route completion progress"
          >
            <div
              className="sd-progress-bar-fill"
              style={{ width: `${progressPercent}%` }}
            />
          </div>

          <p className="sd-progress-footnote">
            Directions open in your maps app. Tap a stop on the{' '}
            <Link to="/courier" className="sd-inline-link">
              Today page
            </Link>{' '}
            to update its status.
          </p>
        </section>
      </div>
    </main>
  );
}

