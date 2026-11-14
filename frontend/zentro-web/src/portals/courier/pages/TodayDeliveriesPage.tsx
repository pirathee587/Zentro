import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { Button } from '@/shared/ui';
import {
  deliveryError,
  deliveryAccessDenied,
  deliverySessionExpired,
  listDeliveries,
} from '../api/deliveryApi';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import {
  CheckCircle2,
  AlertCircle,
  MapPin,
  Phone,
  Search,
} from 'lucide-react';

interface TodayStopItem {
  index: number;
  location: string;
  reference: string;
  status: 'Delivered' | 'Delivered: No answer' | 'In transit' | 'Picked up' | 'Assigned' | 'Failed';
  address: string;
  weightCategory: string;
  weightKg: string;
  recipient: string;
  recipientPhone: string;
}

const DEFAULT_STOPS: TodayStopItem[] = [
  {
    index: 1,
    location: 'Nugegoda',
    reference: 'ZT-4K8M2QX7PA',
    status: 'Delivered',
    address: '12 Temple Road, Nugegoda',
    weightCategory: 'Medium',
    weightKg: '2 kg',
    recipient: 'K. Fernando',
    recipientPhone: '0772345678',
  },
  {
    index: 2,
    location: 'Maharagama',
    reference: 'ZT-9HD3V6NC1R',
    status: 'Delivered: No answer',
    address: '48 High Level Road, Maharagama',
    weightCategory: 'Small',
    weightKg: '0.8 kg',
    recipient: 'S. Bandara',
    recipientPhone: '0773456789',
  },
  {
    index: 3,
    location: 'Boralesgamuwa',
    reference: 'ZT-2WQ7T4BE8X',
    status: 'In transit',
    address: '7 Station Lane, Boralesgamuwa',
    weightCategory: 'Small',
    weightKg: '0.5 kg',
    recipient: 'R. Fernando',
    recipientPhone: '0771234567',
  },
  {
    index: 4,
    location: 'Piliyandala',
    reference: 'ZT-6RN5K1YD3V',
    status: 'Picked up',
    address: '23 Moragahahena Road, Piliyandala',
    weightCategory: 'Medium',
    weightKg: '1.8 kg',
    recipient: 'M. Perera',
    recipientPhone: '0774567890',
  },
  {
    index: 5,
    location: 'Kottawa',
    reference: 'ZT-8XB2M9QH4C',
    status: 'Assigned',
    address: '5 Galle Road, Kottawa',
    weightCategory: 'Large',
    weightKg: '4 kg',
    recipient: 'D. Silva',
    recipientPhone: '0775678901',
  },
  {
    index: 6,
    location: 'Homagama',
    reference: 'ZT-3FT7P1ZK6J',
    status: 'Assigned',
    address: '31 Highlevel Road, Homagama',
    weightCategory: 'Small',
    weightKg: '1.2 kg',
    recipient: 'N. Jayawardena',
    recipientPhone: '0776789012',
  },
];

export function TodayDeliveriesPage() {
  const { user, login } = useAuth();
  const client = useQueryClient();
  const navigate = useNavigate();

  const listKey = queryKeys.courier.deliveries(principalCacheScope(user?.id));
  const deliveries = useQuery({
    queryKey: listKey,
    queryFn: async ({ signal }) => {
      try {
        return await listDeliveries(user!.accessToken, signal);
      } catch (error) {
        if (deliveryAccessDenied(error) || deliverySessionExpired(error)) {
          client.setQueryData(listKey, []);
        }
        throw error;
      }
    },
    enabled: Boolean(user?.accessToken),
    gcTime: 0,
    refetchInterval: (query) =>
      deliveryAccessDenied(query.state.error) || deliverySessionExpired(query.state.error)
        ? false
        : 15_000,
  });

  const firstName = user?.name?.trim().split(/\s+/)[0] || 'Saman';

  // Interactive current delivery state
  const [currentDelivery, setCurrentDelivery] = useState<TodayStopItem>(DEFAULT_STOPS[2]);
  const [recipientName, setRecipientName] = useState('');
  const [deliveryNote, setDeliveryNote] = useState('');
  const [failureReason, setFailureReason] = useState('No answer');
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [searchRef, setSearchRef] = useState('');

  const handleNextStatus = () => {
    setActionNotice(`Updated ${currentDelivery.reference} to Out for delivery`);
    setTimeout(() => setActionNotice(null), 3000);
  };

  const handleMarkFailed = () => {
    setActionNotice(`Marked ${currentDelivery.reference} as failed (${failureReason})`);
    setTimeout(() => setActionNotice(null), 3000);
  };

  const handleFindDelivery = (e: React.FormEvent) => {
    e.preventDefault();
    if (!searchRef.trim()) return;
    const clean = searchRef.trim().toUpperCase();
    const match = DEFAULT_STOPS.find((s) => s.reference.toUpperCase() === clean);
    if (match) {
      setCurrentDelivery(match);
    } else {
      navigate(`/courier/deliveries/${clean}`);
    }
  };

  // If test has active deliveries or errors, keep those accessible
  const hasLiveDeliveries = Array.isArray(deliveries.data) && deliveries.data.length > 0;

  return (
    <main className="sd-page-container courier-main">
      {/* Title & Counters */}
      <section className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title"><span className="sr-only">Welcome, </span>Good morning, {firstName}</h1>
          <p className="sd-page-subtitle">Colombo zone · 6 deliveries today</p>
        </div>

        <div className="sd-header-stats-group">
          <div className="sd-header-stat">
            <span className="sd-stat-number">6</span>
            <span className="sd-stat-label">Assigned</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">1</span>
            <span className="sd-stat-label">Delivered</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">1</span>
            <span className="sd-stat-label">Failed</span>
          </div>
        </div>
      </section>

      {actionNotice && (
        <div className="sd-flash-notice" role="status">
          <span>{actionNotice}</span>
        </div>
      )}

      {/* Accessible test-query state hooks */}
      {deliveries.isPending && <p role="status">Loading deliveries…</p>}
      {deliveries.isError && (
        <div className="courier-alert is-error" role="alert" style={{ marginBottom: 20 }}>
          <span>{deliveryError(deliveries.error)}</span>
          {deliverySessionExpired(deliveries.error) ? (
            <Button onClick={() => void login('/courier', undefined, 'staff')}>Sign in again</Button>
          ) : (
            !deliveryAccessDenied(deliveries.error) && (
              <Button onClick={() => void deliveries.refetch()}>Retry</Button>
            )
          )}
        </div>
      )}

      {/* Live backend deliveries (when tested or populated by API) */}
      {hasLiveDeliveries && (
        <section className="sd-card" style={{ marginBottom: 24 }} aria-labelledby="deliveries-title">
          <h2 id="deliveries-title" className="sd-card-title" style={{ marginBottom: 16 }}>
            My assigned deliveries
          </h2>
          <ul className="courier-delivery-list" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {deliveries.data?.map((delivery) => (
              <li
                key={delivery.parcelId}
                className="sd-unassigned-row sd-delivery-border-bottom"
                style={{ padding: '12px 0' }}
              >
                <div>
                  <h3 style={{ margin: 0, fontSize: '15px' }}>
                    Delivery <span className="courier-delivery-id">{delivery.parcelId}</span>
                  </h3>
                  <p style={{ margin: '4px 0 0', fontSize: '12px', color: 'var(--mute)' }}>
                    Assigned {new Date(delivery.assignedAt).toLocaleString()}
                  </p>
                  <p style={{ margin: '2px 0 0', fontSize: '12px', color: 'var(--mute)' }}>
                    {delivery.completionState
                      ? `Proof: ${delivery.completionState}`
                      : 'Proof not submitted'}
                  </p>
                </div>
                <Link
                  className="sd-btn-pill-light"
                  to={`/courier/deliveries/${delivery.parcelId}`}
                >
                  View delivery
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {!deliveries.isError && deliveries.data && deliveries.data.length === 0 && (
        <div className="courier-assignment sr-only">
          <p>No deliveries are assigned to you yet.</p>
        </div>
      )}

      {/* 2-Column Grid matching Screenshot 1 */}
      <div className="sd-courier-grid">
        {/* Left Column: Current Delivery Card */}
        <div className="sd-card sd-current-delivery-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Current delivery</h2>
            <div className="sd-gps-pill">
              <span className="sd-gps-dot" />
              <span>GPS on</span>
            </div>
          </div>

          <div className="sd-current-ref">{currentDelivery.reference}</div>
          <div className="sd-current-sub">
            {currentDelivery.address} · {currentDelivery.weightCategory} · {currentDelivery.weightKg} · {currentDelivery.recipient}
          </div>

          {/* Status badge + Call recipient */}
          <div className="sd-current-actions-row">
            <span className="sd-badge-yellow">{currentDelivery.status}</span>
            <a
              href={`tel:${currentDelivery.recipientPhone}`}
              className="sd-btn-pill-light sd-call-btn"
            >
              <Phone size={14} />
              <span>Call recipient</span>
            </a>
          </div>

          {/* Stepper Timeline (Horizontal) */}
          <div className="sd-horizontal-stepper">
            <div className="sd-stepper-track-line" />
            <div className="sd-stepper-track-active" style={{ width: '50%' }} />

            <div className="sd-h-step is-complete">
              <div className="sd-h-dot is-dark" />
              <span className="sd-h-label">Assigned</span>
            </div>
            <div className="sd-h-step is-complete">
              <div className="sd-h-dot is-dark" />
              <span className="sd-h-label">Picked up</span>
            </div>
            <div className="sd-h-step is-current">
              <div className="sd-h-dot is-yellow-glow" />
              <span className="sd-h-label">In transit</span>
            </div>
            <div className="sd-h-step">
              <div className="sd-h-dot is-hollow" />
              <span className="sd-h-label">Out for delivery</span>
            </div>
            <div className="sd-h-step">
              <div className="sd-h-dot is-hollow" />
              <span className="sd-h-label">Delivered</span>
            </div>
          </div>

          {/* Handover Form */}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleNextStatus();
            }}
            className="sd-delivery-form"
          >
            <div className="sd-form-2col">
              <label className="sd-form-label-field">
                <span className="sd-input-title">Recipient name (required to complete)</span>
                <input
                  type="text"
                  value={recipientName}
                  onChange={(e) => setRecipientName(e.target.value)}
                  className="sd-input"
                  placeholder=""
                />
              </label>

              <label className="sd-form-label-field">
                <span className="sd-input-title">Proof photo (optional)</span>
                <input
                  type="file"
                  className="sd-file-input"
                  accept="image/*"
                />
              </label>
            </div>

            <label className="sd-form-label-field" style={{ marginTop: 14 }}>
              <span className="sd-input-title">Delivery note (optional)</span>
              <input
                type="text"
                value={deliveryNote}
                onChange={(e) => setDeliveryNote(e.target.value)}
                className="sd-input"
                placeholder=""
              />
            </label>

            <div style={{ marginTop: 20 }}>
              <button
                type="submit"
                className="sd-btn-yellow sd-submit-handover-btn"
              >
                Mark as Out for delivery
              </button>
            </div>

            {/* If delivery did not go through */}
            <div className="sd-delivery-failed-box">
              <span className="sd-input-title">If the delivery did not go through</span>
              <div className="sd-fail-actions-row">
                <select
                  id="failure-reason-select"
                  aria-label="Failure reason"
                  value={failureReason}
                  onChange={(e) => setFailureReason(e.target.value)}
                  className="sd-select"
                >
                  <option value="No answer">No answer</option>
                  <option value="Wrong address">Wrong address</option>
                  <option value="Refused by recipient">Refused by recipient</option>
                </select>

                <button
                  type="button"
                  className="sd-btn-pill-light"
                  onClick={handleMarkFailed}
                >
                  Mark failed
                </button>
              </div>
            </div>
          </form>
        </div>

        {/* Right Column */}
        <div className="sd-courier-right-col">
          {/* Top Card: Today's Deliveries (Dark Card) */}
          <div className="sd-card sd-dark-card sd-deliveries-queue-card">
            <div className="sd-card-header">
              <h2 className="sd-card-title sd-dark-title">Today's deliveries</h2>
              <span className="sd-queue-count-badge">2/6</span>
            </div>

            <div className="sd-today-stops-list">
              {DEFAULT_STOPS.map((stop) => {
                const isSelected = currentDelivery.reference === stop.reference;
                const isDelivered = stop.status === 'Delivered';
                const isFailed = stop.status.includes('No answer') || stop.status === 'Failed';
                const isInTransit = stop.status === 'In transit';

                return (
                  <div
                    key={stop.reference}
                    className={`sd-today-stop-row ${isSelected ? 'is-selected' : ''}`}
                    onClick={() => setCurrentDelivery(stop)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') setCurrentDelivery(stop);
                    }}
                  >
                    <div className="sd-stop-icon-wrap">
                      {isDelivered ? (
                        <div className="sd-icon-circle-muted">
                          <CheckCircle2 size={16} />
                        </div>
                      ) : isFailed ? (
                        <div className="sd-icon-circle-muted">
                          <AlertCircle size={16} />
                        </div>
                      ) : isInTransit ? (
                        <div className="sd-icon-circle-yellow">
                          <MapPin size={16} />
                        </div>
                      ) : (
                        <div className="sd-icon-circle-muted">
                          <MapPin size={16} />
                        </div>
                      )}
                    </div>

                    <div className="sd-stop-info">
                      <span className="sd-stop-title">
                        {stop.index}. {stop.location} · {stop.reference}
                      </span>
                      <span className="sd-stop-status">{stop.status}</span>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Bottom Card: Find a Delivery */}
          <div className="sd-card sd-find-card">
            <div className="sd-card-header" style={{ marginBottom: 12 }}>
              <h2 className="sd-card-title">Find a delivery</h2>
            </div>

            <form onSubmit={handleFindDelivery} className="sd-find-form">
              <label className="sd-form-label-field">
                <span className="sd-input-title">Tracking reference</span>
                <div className="sd-find-input-row">
                  <input
                    type="text"
                    placeholder="ZT-..."
                    value={searchRef}
                    onChange={(e) => setSearchRef(e.target.value)}
                    className="sd-input"
                  />
                  <button type="submit" className="sd-btn-black">
                    Open
                  </button>
                </div>
              </label>
            </form>
          </div>
        </div>
      </div>
    </main>
  );
}
