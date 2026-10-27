import React, { useState, useEffect, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Card } from '../components/Card';
import { Select as BaseSelect, Input as BaseInput } from '@/shared/ui';
import { Button } from '../components/Button';
import { StatusChip } from '../components/StatusChip';
import { EmptyState } from '../components/EmptyState';
import { Skeleton, Spinner } from '../components/Skeleton';
import {
  PackageIcon,
  TruckIcon,
  CircleCheckIcon,
  SendIcon,
  SearchIcon,
  DownloadIcon,
  PhoneIcon,
  CircleAlertIcon,
  BellIcon,
} from '../components/icons';
import {
  getDashboard,
  getParcel,
  estimateFee,
  subscribeToParcel,
  type DashboardData,
  type Parcel,
  type Notification,
  type FeeEstimateResponse,
} from '../services/customerApi';

const STEPPER_STEPS = [
  'Created',
  'Picked up',
  'In transit',
  'Out for delivery',
  'Delivered',
] as const;

function getStepperIndex(parcel: Parcel | null): number {
  if (!parcel) return 2;
  const status = parcel.status?.toLowerCase() || '';
  if (status === 'delivered') return 4;
  if (status === 'created') return 0;

  const timeline = parcel.timeline || [];
  const currentEvent = timeline.find((e) => e.status === 'current') || timeline[timeline.length - 1];
  if (currentEvent) {
    const title = currentEvent.title.toLowerCase();
    if (title.includes('delivered')) return 4;
    if (title.includes('out for delivery') || title.includes('final mile')) return 3;
    if (title.includes('transit') || title.includes('expressway') || title.includes('hub')) return 2;
    if (title.includes('picked up') || title.includes('collected')) return 1;
    if (title.includes('confirmed') || title.includes('booking') || title.includes('created')) return 0;
  }
  return 2;
}

function renderNotifIcon(icon: Notification['icon']) {
  switch (icon) {
    case 'truck':
      return <TruckIcon size={16} />;
    case 'check':
      return <CircleCheckIcon size={16} />;
    case 'package':
      return <PackageIcon size={16} />;
    case 'alert':
      return <CircleAlertIcon size={16} />;
    default:
      return <BellIcon size={16} />;
  }
}

export const CustomerDashboard: React.FC = () => {
  const navigate = useNavigate();

  // Primary dashboard state
  const [dashboard, setDashboard] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Live tracked parcel
  const [liveParcel, setLiveParcel] = useState<Parcel | null>(null);

  // Notifications state (with real-time updates)
  const [notifications, setNotifications] = useState<Notification[]>([]);

  // Quick Fee Estimate state
  const [estimateCategory, setEstimateCategory] = useState('Medium (1–5 kg)');
  const [estimateWeight, setEstimateWeight] = useState('2');
  const [estimateDest, setEstimateDest] = useState('Another district');
  const [feeEstimate, setFeeEstimate] = useState<FeeEstimateResponse | null>({
    fee: 1072,
    formattedFee: 'LKR 1,072',
    currency: 'LKR',
    estimatedDays: 2,
    breakdown: { baseFee: 550, weightFee: 120, distanceSurcharge: 402 },
  });
  const [estimating, setEstimating] = useState(false);
  const [estimateError, setEstimateError] = useState<string | null>(null);

  // Track any parcel state
  const [trackRefInput, setTrackRefInput] = useState('');
  const [trackingLoading, setTrackingLoading] = useState(false);
  const [trackedParcelResult, setTrackedParcelResult] = useState<Parcel | null | 'NOT_FOUND'>(null);
  const [trackErrorMsg, setTrackErrorMsg] = useState<string | null>(null);

  // Load dashboard data
  const loadDashboardData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getDashboard();
      setDashboard(data);
      setNotifications(data.notifications || []);
      // Choose first active parcel or latest parcel
      const active = data.recentParcels?.find((p) => p.status === 'In progress') || data.recentParcels?.[0] || null;
      setLiveParcel(active);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to connect to SwiftDrop services.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadDashboardData();
  }, [loadDashboardData]);

  // Real-time subscription to the live parcel
  useEffect(() => {
    if (!liveParcel?.trackingRef) return;
    const unsub = subscribeToParcel(liveParcel.trackingRef, (updated) => {
      setLiveParcel(updated);
      setNotifications((prev) => [
        {
          id: `live-notif-${Date.now()}`,
          icon: updated.status === 'Delivered' ? 'check' : 'truck',
          title: `Parcel ${updated.trackingRef}`,
          body: `Live status: ${updated.status} • ${updated.sender.city} to ${updated.recipient.city}`,
          time: 'Just now',
          isNew: true,
        },
        ...prev.map((n) => ({ ...n, isNew: false })),
      ]);
    });
    return () => unsub();
  }, [liveParcel?.trackingRef]);

  // Quick fee estimate calculator
  const calculateEstimate = useCallback(async () => {
    const weightNum = parseFloat(estimateWeight);
    if (isNaN(weightNum) || weightNum <= 0) {
      setEstimateError('Please enter a valid weight in kg');
      return;
    }

    setEstimating(true);
    setEstimateError(null);
    try {
      const result = await estimateFee({
        pickupCity: 'Colombo 07',
        deliveryCity: estimateDest === 'Another district' ? 'Kandy' : 'Colombo',
        destinationType: estimateDest === 'Another district' ? 'OTHER_DISTRICT' : 'SAME_CITY',
        weightKg: weightNum,
        parcelCategory: estimateCategory,
      });
      setFeeEstimate(result);
    } catch {
      setEstimateError('Could not calculate rate. Tap retry.');
    } finally {
      setEstimating(false);
    }
  }, [estimateCategory, estimateWeight, estimateDest]);

  // Auto-calculate quick estimate
  useEffect(() => {
    void calculateEstimate();
  }, [calculateEstimate]);
  const handleSimulateNextUpdate = () => {
    if (!liveParcel) return;
    const nextStatus = liveParcel.status === 'In progress' ? 'Delivered' : 'In progress';
    setLiveParcel((prev) => (prev ? { ...prev, status: nextStatus } : null));
  };

  // Handler for track parcel search
  const handleTrackSearch = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const query = trackRefInput.trim();
    if (!query) {
      setTrackErrorMsg('Please enter a tracking reference number.');
      setTrackedParcelResult(null);
      return;
    }
    setTrackErrorMsg(null);
    setTrackingLoading(true);
    setTrackedParcelResult(null);

    try {
      const found = await getParcel(query);
      if (found) {
        setTrackedParcelResult(found);
      } else {
        setTrackedParcelResult('NOT_FOUND');
      }
    } catch {
      setTrackErrorMsg('Network error while looking up parcel.');
    } finally {
      setTrackingLoading(false);
    }
  };

  // 1. Loading State (Full Skeleton)
  if (loading) {
    return (
      <div className="sd-grid-12" aria-busy="true" aria-label="Loading Customer Dashboard">
        {/* Top row skeleton */}
        <div className="sd-col-span-12" style={{ marginBottom: '20px' }}>
          <Skeleton variant="text" width={260} height={42} style={{ marginBottom: 16 }} />
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: '24px' }}>
            <Skeleton variant="pill" height={44} style={{ width: '55%' }} />
            <div style={{ display: 'flex', gap: '20px' }}>
              <Skeleton variant="card" width={80} height={70} />
              <Skeleton variant="card" width={80} height={70} />
              <Skeleton variant="card" width={80} height={70} />
            </div>
          </div>
        </div>

        {/* Row 2 skeleton */}
        <Card span={3}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Skeleton variant="circle" width={52} height={52} />
            <Skeleton variant="text" width={120} height={20} />
            <Skeleton variant="text" width={160} height={14} />
            <Skeleton variant="pill" width={110} height={32} />
          </div>
        </Card>

        <Card span={6}>
          <Skeleton variant="text" width={180} height={24} style={{ marginBottom: 6 }} />
          <Skeleton variant="text" width={120} height={16} style={{ marginBottom: 24 }} />
          <Skeleton variant="pill" height={36} style={{ marginBottom: 20 }} />
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Skeleton variant="text" width={160} height={16} />
            <Skeleton variant="pill" width={140} height={36} />
          </div>
        </Card>

        <Card span={3}>
          <Skeleton variant="text" width={140} height={22} style={{ marginBottom: 14 }} />
          <Skeleton variant="pill" height={36} style={{ marginBottom: 10 }} />
          <Skeleton variant="pill" height={36} style={{ marginBottom: 10 }} />
          <Skeleton variant="pill" height={36} />
        </Card>

        {/* Row 3 skeleton */}
        <Card span={6}>
          <Skeleton variant="text" width={160} height={24} style={{ marginBottom: 16 }} />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Skeleton variant="text" height={32} />
            <Skeleton variant="text" height={32} />
            <Skeleton variant="text" height={32} />
          </div>
        </Card>

        <Card span={3}>
          <Skeleton variant="text" width={130} height={22} style={{ marginBottom: 14 }} />
          <Skeleton variant="pill" height={40} style={{ marginBottom: 10 }} />
          <Skeleton variant="pill" height={36} />
        </Card>

        <Card span={3} variant="panel">
          <Skeleton variant="text" width={120} height={22} style={{ marginBottom: 14 }} />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Skeleton variant="text" height={28} />
            <Skeleton variant="text" height={28} />
            <Skeleton variant="text" height={28} />
          </div>
        </Card>
      </div>
    );
  }

  // 2. Error State with Retry
  if (error || !dashboard) {
    return (
      <div className="sd-grid-12">
        <Card span={12}>
          <div className="dash-error-card" role="alert">
            <div className="dash-error-icon" aria-hidden="true">
              <CircleAlertIcon size={28} />
            </div>
            <h2 className="dash-error-title">Unable to load dashboard</h2>
            <p className="dash-error-desc">
              {error || 'An unexpected error occurred while fetching your delivery details.'}
            </p>
            <Button variant="primary" onClick={() => void loadDashboardData()}>
              Retry
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  // 3. Empty State (when customer has no parcels)
  const hasNoParcels = dashboard.totalParcels === 0;
  if (hasNoParcels) {
    const firstName = dashboard.userName ? dashboard.userName.split(' ')[0] : 'there';
    return (
      <div className="sd-grid-12">
        <div className="sd-col-span-12 sd-dash-hero">
          <h1 className="sd-dash-hero-title">Welcome back, {firstName}</h1>
          <p style={{ color: 'var(--mute)', margin: '6px 0 0', fontSize: '15px' }}>
            You have no active shipments in transit right now.
          </p>
        </div>

        <Card span={12}>
          <EmptyState
            title="No parcels yet"
            description="Book your first doorstep pickup and track it live with milestone updates across Sri Lanka."
            icon={<PackageIcon size={40} />}
            action={
              <Link to="/customer/send" style={{ textDecoration: 'none' }}>
                <Button variant="accent" icon={<SendIcon size={16} />}>
                  Send your first parcel
                </Button>
              </Link>
            }
          />
        </Card>
      </div>
    );
  }

  // Computed Values for Top Row
  const firstName = dashboard.userName ? dashboard.userName.split(' ')[0] : 'there';

  // Stepper calculations for live parcel
  const currentStepIndex = getStepperIndex(liveParcel);

  return (
    <div className="sd-grid-12">
      {/* =====================================================================
          1. Top Hero Section: Welcome + Segmented Bar + Spend + Large Stats
          ===================================================================== */}
      <div className="sd-col-span-12 sd-dash-hero">
        <h1 className="sd-dash-hero-title">Welcome back, {firstName}</h1>

        <div className="sd-dash-hero-row">
          {/* Left: Segmented Bar + Spend */}
          <div className="sd-dash-hero-left">
            <div className="sd-capsule-labels-row">
              <span style={{ flex: '62' }}>
                Delivered
                <span className="dash-seg-label sr-only">Delivered</span>
              </span>
              <span style={{ flex: '23' }}>
                In transit
                <span className="dash-seg-label sr-only">In transit</span>
              </span>
              <span style={{ flex: '10' }}>
                Awaiting
                <span className="dash-seg-label sr-only">Awaiting</span>
              </span>
              <span style={{ flex: '5' }}>
                Failed
                <span className="dash-seg-label sr-only">Failed</span>
              </span>
            </div>

            <div
              className="sd-capsules-bar"
              role="img"
              aria-label="Parcel share: Delivered 62%, In transit 23%, Awaiting 10%, Failed 5%"
            >
              <div className="sd-capsule-seg sd-capsule-delivered" style={{ flex: '62' }}>
                62%
              </div>
              <div className="sd-capsule-seg sd-capsule-transit" style={{ flex: '23' }}>
                23%
              </div>
              <div className="sd-capsule-seg sd-capsule-awaiting" style={{ flex: '10' }}>
                10%
              </div>
              <div className="sd-capsule-seg sd-capsule-failed" style={{ flex: '5' }}>
                5%
              </div>
            </div>

            <div className="sd-hero-spend-block">
              <div className="sd-hero-spend-label">This month's spend</div>
              <div className="sd-hero-spend-amount">
                {dashboard.formattedMonthlySpent || 'LKR 14,200'}
              </div>
            </div>
          </div>

          {/* Right: Three Large Stats */}
          <div className="sd-dash-hero-stats">
            <div className="sd-dash-hero-stat-item">
              <div className="sd-dash-hero-stat-val dash-big-stat-value">{dashboard.activeCount ?? 3}</div>
              <div className="sd-dash-hero-stat-lbl">Active parcels</div>
            </div>
            <div className="sd-dash-hero-stat-item">
              <div className="sd-dash-hero-stat-val dash-big-stat-value">{dashboard.deliveredCount ?? 41}</div>
              <div className="sd-dash-hero-stat-lbl">Delivered</div>
            </div>
            <div className="sd-dash-hero-stat-item">
              <div className="sd-dash-hero-stat-val dash-big-stat-value">{dashboard.awaitingPickupCount ?? 2}</div>
              <div className="sd-dash-hero-stat-lbl">Awaiting pickup</div>
            </div>
          </div>
        </div>
      </div>

      {/* =====================================================================
          2. Courier Card (span 3) - Rich Brown Card
          ===================================================================== */}
      <div className="sd-col-span-3 sd-card-courier-brown">
        <div className="sd-courier-avatar-yellow" aria-hidden="true">
          {dashboard.courier?.initials || 'SP'}
        </div>

        <div style={{ marginTop: 'auto', paddingTop: '28px' }}>
          <h3 className="sd-courier-brown-name">
            {dashboard.courier?.name || 'Saman Perera'}
          </h3>
          <p className="sd-courier-brown-meta">
            {dashboard.courier?.role || 'Courier'} • {dashboard.courier?.zone || 'Colombo zone'}
          </p>
          <a
            href={`tel:${dashboard.courier?.phone || '+94771234567'}`}
            className="sd-courier-brown-btn"
            role="button"
            aria-label="Call courier"
          >
            <PhoneIcon size={14} />
            <span>Call courier</span>
          </a>
        </div>
      </div>

      {/* =====================================================================
          3. Live Tracking Card (span 6)
          ===================================================================== */}
      <Card span={6}>
        {liveParcel ? (
          <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ fontSize: '18px', fontWeight: 600 }}>Live tracking</div>
              <div className="sd-live-badge-green">
                <span className="sd-live-dot-green" aria-hidden="true" />
                <span>Live</span>
              </div>
            </div>

            <div style={{ marginTop: '14px' }}>
              <h3 style={{ fontSize: '32px', fontWeight: 700, margin: 0, letterSpacing: '-0.5px' }}>
                {liveParcel.trackingRef}
              </h3>
              <p style={{ color: 'var(--mute)', fontSize: '14px', margin: '4px 0 0' }}>
                {liveParcel.sender.city} → {liveParcel.recipient.city} • 2 kg • Medium
              </p>
            </div>

            {/* 5-Step Horizontal Stepper */}
            <div className="sd-h-stepper" role="progressbar" aria-valuenow={currentStepIndex + 1} aria-valuemin={1} aria-valuemax={5}>
              <div className="sd-h-step-connector">
                <div
                  className="sd-h-step-connector-fill"
                  style={{ width: `${(currentStepIndex / 4) * 100}%` }}
                />
              </div>

              {STEPPER_STEPS.map((stepLabel, idx) => {
                const isDone = idx < currentStepIndex || (liveParcel.status === 'Delivered' && idx <= currentStepIndex);
                const isCurrent = idx === currentStepIndex && liveParcel.status !== 'Delivered';

                const nodeClass = isDone
                  ? 'sd-h-step-node is-done'
                  : isCurrent
                  ? 'sd-h-step-node is-current'
                  : 'sd-h-step-node';

                return (
                  <div key={stepLabel} className="sd-h-step-item">
                    <div className={nodeClass} aria-label={`${stepLabel}: ${isDone ? 'completed' : isCurrent ? 'current' : 'upcoming'}`}>
                      {isDone ? (
                        <CircleCheckIcon size={14} />
                      ) : isCurrent ? (
                        <span style={{ width: 8, height: 8, borderRadius: '50%', background: 'currentColor' }} />
                      ) : null}
                    </div>
                    <span className="sd-h-step-label dash-step-label">{stepLabel}</span>
                  </div>
                );
              })}
            </div>

            {/* Live Footer */}
            <div className="sd-live-card-footer">
              <div className="sd-live-eta-text">
                Estimated arrival today,<br />
                4:30 PM
              </div>

              <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                <Button
                  variant="outline"
                  size="small"
                  onClick={handleSimulateNextUpdate}
                >
                  Simulate next update
                </Button>
                <Link to="/customer/receipts" style={{ textDecoration: 'none' }}>
                  <Button variant="accent" size="small" icon={<DownloadIcon size={15} />}>
                    Download receipt
                  </Button>
                </Link>
              </div>
            </div>
          </>
        ) : (
          <EmptyState
            title="No active live tracking"
            description="All scheduled parcels have been delivered or await pickup."
            icon={<TruckIcon size={32} />}
          />
        )}
      </Card>

      {/* =====================================================================
          4. Send a Parcel Quick Card (span 3)
          ===================================================================== */}
      <Card span={3}>
        <div style={{ fontSize: '18px', fontWeight: 600, marginBottom: '16px' }}>
          Send a parcel
          <span className="sr-only">Quick Fee Estimate</span>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          <div className="sd-field">
            <label htmlFor="quick-size-select" className="sd-label" style={{ fontSize: '12px' }}>
              Parcel size
            </label>
            <BaseSelect
              id="quick-size-select"
              className="sd-input sd-select"
              style={{ borderRadius: '9999px', padding: '9px 16px', fontSize: '13px' }}
              value={estimateCategory}
              onChange={(e) => {
                setEstimateCategory(e.target.value);
                void calculateEstimate();
              }}
            >
              <option value="Medium (1–5 kg)">Medium (1–5 kg)</option>
              <option value="Small (up to 1 kg)">Small (up to 1 kg)</option>
              <option value="Large (5–20 kg)">Large (5–20 kg)</option>
            </BaseSelect>
          </div>

          <div className="sd-field">
            <label htmlFor="quick-weight-input" className="sd-label" style={{ fontSize: '12px' }}>
              Weight (kg)
            </label>
            <BaseInput
              id="quick-weight-input"
              className="sd-input"
              style={{ borderRadius: '9999px', padding: '9px 16px', fontSize: '13px' }}
              type="number"
              step="0.5"
              min="0.1"
              value={estimateWeight}
              onChange={(e) => {
                setEstimateWeight(e.target.value);
                void calculateEstimate();
              }}
            />
          </div>

          <div className="sd-field">
            <label htmlFor="quick-dest-select" className="sd-label" style={{ fontSize: '12px' }}>
              Destination
            </label>
            <BaseSelect
              id="quick-dest-select"
              className="sd-input sd-select"
              style={{ borderRadius: '9999px', padding: '9px 16px', fontSize: '13px' }}
              value={estimateDest}
              onChange={(e) => {
                setEstimateDest(e.target.value);
                void calculateEstimate();
              }}
            >
              <option value="Another district">Another district</option>
              <option value="Same city">Same city</option>
            </BaseSelect>
          </div>

          <div>
            <div className="sd-quick-fee-amount">
              {estimating ? 'Calculating...' : feeEstimate?.formattedFee || 'LKR 1,072'}
            </div>
            {estimateError ? (
              <div className="sd-quick-fee-sub" style={{ color: 'var(--err)' }}>
                {estimateError}
              </div>
            ) : (
              <div className="sd-quick-fee-sub">
                Estimated fee. Confirm to book pickup.
              </div>
            )}
          </div>

          <Button
            variant="primary"
            style={{ width: '100%', borderRadius: '9999px' }}
            onClick={() => {
              navigate(
                `/customer/send?category=${encodeURIComponent(estimateCategory)}&weight=${encodeURIComponent(
                  estimateWeight
                )}&dest=${encodeURIComponent(estimateDest)}`
              );
            }}
          >
            Continue to send
          </Button>
        </div>
      </Card>

      {/* =====================================================================
          5. Bottom Row: Delivery History (span 5)
          ===================================================================== */}
      <Card span={6}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
          <h2 style={{ fontSize: '18px', fontWeight: 600, margin: 0 }}>Delivery History</h2>
          <Link
            to="/customer/history"
            style={{ textDecoration: 'none' }}
          >
            <Button
              variant="outline"
              size="small"
              style={{ borderRadius: '9999px', padding: '4px 14px', fontSize: '13px' }}
            >
              View all
            </Button>
          </Link>
        </div>

        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
            <thead>
              <tr style={{ color: 'var(--mute)', textAlign: 'left', borderBottom: '1px solid var(--line)' }}>
                <th style={{ padding: '8px 4px', fontWeight: 500 }}>Tracking no.</th>
                <th style={{ padding: '8px 4px', fontWeight: 500 }}>To</th>
                <th style={{ padding: '8px 4px', fontWeight: 500 }}>Status</th>
                <th style={{ padding: '8px 4px', fontWeight: 500, textAlign: 'right' }}>Fee</th>
              </tr>
            </thead>
            <tbody>
              {dashboard.recentParcels?.slice(0, 4).map((p) => (
                <tr key={p.id} style={{ borderBottom: '1px solid var(--line)' }}>
                  <td style={{ padding: '12px 4px', fontWeight: 500 }}>
                    <Link
                      to={`/customer/track/${p.trackingRef}`}
                      style={{ color: 'var(--ink)', textDecoration: 'none' }}
                    >
                      {p.trackingRef}
                    </Link>
                  </td>
                  <td style={{ padding: '12px 4px', color: 'var(--mute)' }}>
                    {p.recipient.city}
                  </td>
                  <td style={{ padding: '12px 4px' }}>
                    <StatusChip status={p.status} />
                  </td>
                  <td style={{ padding: '12px 4px', textAlign: 'right', fontWeight: 500 }}>
                    {p.formattedFee}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* =====================================================================
          6. Bottom Row: Track Any Parcel (span 4)
          ===================================================================== */}
      <Card span={3}>
        <div style={{ fontSize: '18px', fontWeight: 600, marginBottom: '14px' }}>
          Track Any Parcel
        </div>

        <form onSubmit={handleTrackSearch}>
          <div className="sd-field" style={{ marginBottom: '14px' }}>
            <label htmlFor="dash-track-input" className="sd-label" style={{ fontSize: '12px', color: 'var(--mute)' }}>
              Tracking number
            </label>
            <BaseInput
              id="dash-track-input"
              className="sd-pill-input"
              placeholder="e.g. SD-20377"
              value={trackRefInput}
              onChange={(e) => setTrackRefInput(e.target.value)}
            />
          </div>

          <Button
            type="submit"
            variant="primary"
            icon={<SearchIcon size={15} />}
            disabled={trackingLoading}
            aria-label="Track parcel"
            style={{ borderRadius: '9999px', padding: '9px 20px' }}
          >
            Track
          </Button>
        </form>

        {trackingLoading && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginTop: '12px' }}>
            <Spinner size="small" />
            <span style={{ fontSize: '12px', color: 'var(--mute)' }}>Searching shipments...</span>
          </div>
        )}

        {trackErrorMsg && (
          <p style={{ color: 'var(--err)', fontSize: '12px', marginTop: '10px' }} role="alert">
            {trackErrorMsg}
          </p>
        )}

        {trackedParcelResult === 'NOT_FOUND' && (
          <div className="dash-track-result" role="alert" style={{ marginTop: '12px' }}>
            <p style={{ margin: 0, color: 'var(--mute)', fontSize: '12px' }}>
              No parcel found for &ldquo;{trackRefInput}&rdquo;. Please verify the reference code.
            </p>
          </div>
        )}

        {trackedParcelResult && trackedParcelResult !== 'NOT_FOUND' && (
          <div className="dash-track-result" style={{ marginTop: '12px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontWeight: 600 }}>{trackedParcelResult.trackingRef}</span>
              <StatusChip status={trackedParcelResult.status} />
            </div>
            <p style={{ margin: '4px 0 0', fontSize: '12px', color: 'var(--mute)' }}>
              {trackedParcelResult.sender.city} → {trackedParcelResult.recipient.city}
            </p>
            <Link
              to={`/customer/track/${trackedParcelResult.trackingRef}`}
              style={{
                display: 'inline-block',
                marginTop: '8px',
                fontSize: '12px',
                fontWeight: 600,
                color: 'var(--ink)',
                textDecoration: 'none',
              }}
            >
              View full progress →
            </Link>
          </div>
        )}
      </Card>

      {/* =====================================================================
          7. Bottom Row: Notifications Panel (span 3, Dark Slate)
          ===================================================================== */}
      <div className="sd-col-span-3 sd-card-notif-dark">
        <h3 className="sd-notif-dark-title">Notifications</h3>

        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {(notifications.length > 0 ? notifications : dashboard.notifications || []).map((n) => (
            <div key={n.id} className="sd-notif-item-row">
              <div
                className={n.icon === 'truck' ? 'sd-notif-circle-icon-yellow' : 'sd-notif-circle-icon-dark'}
                aria-hidden="true"
              >
                {renderNotifIcon(n.icon)}
              </div>
              <div>
                <div className="sd-notif-text-title">{n.title}</div>
                <div className="sd-notif-text-time">
                  {n.body && <span>{n.body}</span>}
                  {n.time && n.time !== n.body && (
                    <span style={{ marginLeft: '6px' }}>{n.time}</span>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
