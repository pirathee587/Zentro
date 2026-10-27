import React, { useEffect, useState, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Input } from '@/shared/ui';
import { Card, CardHeader, CardTitle, CardContent } from './Card';
import { Button } from './Button';
import { Field } from './Field';
import { StatusChip } from './StatusChip';
import { Skeleton, Spinner } from './Skeleton';
import { EmptyState } from './EmptyState';
import {
  SearchIcon,
  PackageIcon,
  CircleCheckIcon,
  CircleAlertIcon,
  PhoneIcon,
  ClockIcon,
} from './icons';
import { useToast } from './Toast';
import { getParcel, subscribeToParcel, type Parcel } from '../services/customerApi';

export interface TrackViewProps {
  initialTrackingRef?: string;
  isPublic?: boolean;
}

interface StageItem {
  id: string;
  name: string;
  status: 'done' | 'current' | 'pending' | 'failed';
  dateTime?: string;
  description?: string;
  reason?: string;
}

function getParcelSize(parcel: Parcel): string {
  if (parcel.parcel.size) {
    if (typeof parcel.parcel.size === 'string') {
      const lower = parcel.parcel.size.toLowerCase();
      if (lower === 'small') return 'Small';
      if (lower === 'medium') return 'Medium';
      if (lower === 'large') return 'Large';
      return parcel.parcel.size;
    }
  }
  const weight = parcel.parcel.weightKg || 1;
  if (weight <= 1) return 'Small';
  if (weight <= 5) return 'Medium';
  return 'Large';
}

function computeStages(parcel: Parcel): {
  stages: StageItem[];
  progressPercent: number;
  etaText: string;
  isFailed: boolean;
} {
  const isFailed = parcel.status === 'Failed';
  const isDelivered = parcel.status === 'Delivered';
  const failureReason =
    parcel.failureReason ||
    parcel.timeline?.find((e) => e.status === 'failed' || /fail/i.test(e.title))?.description ||
    'Delivery could not be completed.';

  const findEvent = (pattern: RegExp) =>
    parcel.timeline?.find(
      (e) => pattern.test(e.title) || (e.description && pattern.test(e.description))
    );

  const createdEv = findEvent(/create|confirm|book/i);
  const pickedUpEv = findEvent(/pick|collect/i);
  const inTransitEv = findEvent(/transit|dispatch|expressway|hub/i);
  const outForDeliveryEv = findEvent(/out for delivery|final mile/i);
  const deliveredEv = findEvent(/deliver/i);

  const createdTime =
    createdEv?.formattedDate || createdEv?.timestamp || parcel.formattedCreatedAt || parcel.createdAt;
  const pickedUpTime = pickedUpEv?.formattedDate || pickedUpEv?.timestamp;
  const inTransitTime = inTransitEv?.formattedDate || inTransitEv?.timestamp;
  const outForDeliveryTime = outForDeliveryEv?.formattedDate || outForDeliveryEv?.timestamp;
  const deliveredTime =
    parcel.formattedDeliveredAt || deliveredEv?.formattedDate || deliveredEv?.timestamp;

  let stages: StageItem[] = [];
  let progressPercent = 0;
  let etaText = '';

  if (isFailed) {
    stages = [
      {
        id: 'stage-1',
        name: 'Created',
        status: 'done',
        dateTime: createdTime,
        description: createdEv?.description || 'Delivery booked and registered',
      },
      {
        id: 'stage-2',
        name: 'Picked up',
        status: pickedUpEv ? 'done' : 'pending',
        dateTime: pickedUpTime,
        description: pickedUpEv?.description,
      },
      {
        id: 'stage-3',
        name: 'In transit',
        status: inTransitEv ? 'done' : 'pending',
        dateTime: inTransitTime,
        description: inTransitEv?.description,
      },
      {
        id: 'stage-4',
        name: 'Out for delivery',
        status: outForDeliveryEv ? 'done' : 'pending',
        dateTime: outForDeliveryTime,
        description: outForDeliveryEv?.description,
      },
      {
        id: 'stage-5',
        name: 'Delivery failed',
        status: 'failed',
        reason: failureReason,
        dateTime:
          findEvent(/fail/i)?.formattedDate ||
          findEvent(/fail/i)?.timestamp ||
          parcel.formattedCreatedAt,
      },
    ];
    progressPercent = inTransitEv ? 60 : pickedUpEv ? 40 : 20;
    etaText = failureReason;
  } else if (isDelivered) {
    stages = [
      {
        id: 'stage-1',
        name: 'Created',
        status: 'done',
        dateTime: createdTime,
        description: createdEv?.description || 'Delivery booked and registered',
      },
      {
        id: 'stage-2',
        name: 'Picked up',
        status: 'done',
        dateTime: pickedUpTime || createdTime,
        description: pickedUpEv?.description || 'Collected by courier',
      },
      {
        id: 'stage-3',
        name: 'In transit',
        status: 'done',
        dateTime: inTransitTime || createdTime,
        description: inTransitEv?.description || 'Processed through sorting facility',
      },
      {
        id: 'stage-4',
        name: 'Out for delivery',
        status: 'done',
        dateTime: outForDeliveryTime || deliveredTime,
        description: outForDeliveryEv?.description || 'Courier on final delivery run',
      },
      {
        id: 'stage-5',
        name: 'Delivered',
        status: 'done',
        dateTime: deliveredTime,
        description: deliveredEv?.description || 'Package received and signed',
      },
    ];
    progressPercent = 100;
    etaText = `Delivered on ${parcel.formattedDeliveredAt || deliveredTime || 'recent date'}`;
  } else {
    const isOutForDelivery = Boolean(
      outForDeliveryEv && (outForDeliveryEv.status === 'current' || outForDeliveryEv.status === 'completed')
    );
    const isInTransit = Boolean(inTransitEv && inTransitEv.status === 'current');
    const isPickedUp = Boolean(pickedUpEv && pickedUpEv.status === 'current');

    if (isOutForDelivery) {
      stages = [
        { id: 'stage-1', name: 'Created', status: 'done', dateTime: createdTime, description: createdEv?.description },
        { id: 'stage-2', name: 'Picked up', status: 'done', dateTime: pickedUpTime, description: pickedUpEv?.description },
        { id: 'stage-3', name: 'In transit', status: 'done', dateTime: inTransitTime, description: inTransitEv?.description },
        {
          id: 'stage-4',
          name: 'Out for delivery',
          status: 'current',
          dateTime: outForDeliveryTime,
          description: outForDeliveryEv?.description || 'With courier for doorstep delivery',
        },
        { id: 'stage-5', name: 'Delivered', status: 'pending' },
      ];
      progressPercent = 85;
    } else if (isInTransit || (parcel.status === 'In progress' && !isPickedUp)) {
      stages = [
        { id: 'stage-1', name: 'Created', status: 'done', dateTime: createdTime, description: createdEv?.description },
        { id: 'stage-2', name: 'Picked up', status: 'done', dateTime: pickedUpTime, description: pickedUpEv?.description },
        {
          id: 'stage-3',
          name: 'In transit',
          status: 'current',
          dateTime: inTransitTime,
          description: inTransitEv?.description || 'Moving between distribution hubs',
        },
        { id: 'stage-4', name: 'Out for delivery', status: 'pending' },
        { id: 'stage-5', name: 'Delivered', status: 'pending' },
      ];
      progressPercent = 55;
    } else if (isPickedUp) {
      stages = [
        { id: 'stage-1', name: 'Created', status: 'done', dateTime: createdTime, description: createdEv?.description },
        {
          id: 'stage-2',
          name: 'Picked up',
          status: 'current',
          dateTime: pickedUpTime,
          description: pickedUpEv?.description || 'Courier collected package',
        },
        { id: 'stage-3', name: 'In transit', status: 'pending' },
        { id: 'stage-4', name: 'Out for delivery', status: 'pending' },
        { id: 'stage-5', name: 'Delivered', status: 'pending' },
      ];
      progressPercent = 30;
    } else {
      // Created
      stages = [
        {
          id: 'stage-1',
          name: 'Created',
          status: 'current',
          dateTime: createdTime,
          description: createdEv?.description || 'Booking confirmed, awaiting pickup',
        },
        { id: 'stage-2', name: 'Picked up', status: 'pending' },
        { id: 'stage-3', name: 'In transit', status: 'pending' },
        { id: 'stage-4', name: 'Out for delivery', status: 'pending' },
        { id: 'stage-5', name: 'Delivered', status: 'pending' },
      ];
      progressPercent = 12;
    }

    etaText = parcel.formattedEstimatedDeliveryDate
      ? `Estimated arrival ${parcel.formattedEstimatedDeliveryDate}`
      : 'Estimated arrival today, 4:30 PM';
  }

  return { stages, progressPercent, etaText, isFailed };
}

export const TrackView: React.FC<TrackViewProps> = ({ initialTrackingRef, isPublic = false }) => {
  const params = useParams<{ trackingRef?: string; trackingNumber?: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const activeRef = (initialTrackingRef || params.trackingRef || params.trackingNumber || '').trim();

  const [inputRef, setInputRef] = useState(activeRef);
  const [searchedRef, setSearchedRef] = useState(activeRef);
  const [parcel, setParcel] = useState<Parcel | null>(null);
  const [loading, setLoading] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const [networkError, setNetworkError] = useState('');
  const [clipboardBlocked, setClipboardBlocked] = useState(false);
  const [liveAnnouncement, setLiveAnnouncement] = useState('');

  const fetchTracking = useCallback(async (ref: string) => {
    const clean = ref.trim();
    if (!clean) {
      setParcel(null);
      setNotFound(false);
      setNetworkError('');
      return;
    }

    setLoading(true);
    setNotFound(false);
    setNetworkError('');
    setSearchedRef(clean);

    try {
      const data = await getParcel(clean);
      if (!data) {
        setNotFound(true);
        setParcel(null);
      } else {
        setParcel(data);
        setNotFound(false);
      }
    } catch {
      setNetworkError('Unable to load parcel details right now. Please check your connection.');
      setParcel(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (activeRef) {
      setInputRef(activeRef);
      void fetchTracking(activeRef);

      // Subscribe to real-time parcel updates
      const unsubscribe = subscribeToParcel(activeRef, (updated) => {
        setParcel((prev) => {
          if (prev) {
            if (prev.status !== updated.status) {
              setLiveAnnouncement(`Shipment status updated to ${updated.status}.`);
            } else if (prev.timeline?.length !== updated.timeline?.length) {
              const last = updated.timeline?.[updated.timeline.length - 1];
              setLiveAnnouncement(`New milestone added: ${last?.title || 'update'}.`);
            }
          }
          return updated;
        });
      });
      return unsubscribe;
    }
  }, [activeRef, fetchTracking]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    const clean = inputRef.trim();
    if (!clean) return;
    setClipboardBlocked(false);

    if (isPublic) {
      navigate(`/track/${encodeURIComponent(clean)}`);
    } else {
      navigate(`/customer/track/${encodeURIComponent(clean)}`);
    }
    void fetchTracking(clean);
  };

  const shareUrl = useMemo(() => {
    if (!parcel?.trackingRef) return '';
    if (typeof window !== 'undefined' && window.location?.origin) {
      return `${window.location.origin}/track/${encodeURIComponent(parcel.trackingRef)}`;
    }
    return `/track/${parcel.trackingRef}`;
  }, [parcel?.trackingRef]);

  const handleCopyLink = async () => {
    if (!shareUrl) return;
    setClipboardBlocked(false);
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard API unavailable');
      }
      await navigator.clipboard.writeText(shareUrl);
      if (showToast) {
        showToast({
          title: 'Link copied',
          message: 'Tracking link copied to clipboard.',
          type: 'success',
        });
      }
    } catch {
      setClipboardBlocked(true);
    }
  };

  const stageData = useMemo(() => {
    if (!parcel) return null;
    return computeStages(parcel);
  }, [parcel]);

  const routeSubtitle = useMemo(() => {
    if (!parcel) return '';
    // Privacy: On public page and in summary, only show city-level route
    const fromCity = parcel.sender?.city || 'Colombo';
    const toCity = parcel.recipient?.city || 'Kandy';
    const weightStr = `${parcel.parcel?.weightKg || 1} kg`;
    const sizeStr = getParcelSize(parcel);
    return `${fromCity} to ${toCity} • ${weightStr} • ${sizeStr}`;
  }, [parcel]);

  const courier = useMemo(() => {
    return (
      parcel?.courier || {
        name: 'Nuwan Jayasuriya',
        vehicle: 'Delivery Van (WP CAS-8821)',
        zone: 'Colombo Metro & Western',
        phone: '+94 77 800 1234',
      }
    );
  }, [parcel?.courier]);

  return (
    <div className="sd-grid-12">
      {/* Screen reader live updates container */}
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {liveAnnouncement}
      </div>

      {/* Search card at the top */}
      <Card span={12}>
        <form onSubmit={handleSearch} className="sd-track-search-form" noValidate>
          <div style={{ display: 'flex', gap: '12px', alignItems: 'flex-start', flexWrap: 'wrap' }}>
            <div style={{ flex: '1', minWidth: '240px' }}>
              <Field
                label="Tracking number"
                id="tracking-search-input"
                placeholder="e.g. ZTR-SD8941K2M7"
                value={inputRef}
                onChange={(e) => {
                  setInputRef(e.target.value);
                  if (notFound) setNotFound(false);
                }}
                hint="Press Enter or click Track to view shipment milestones"
              />
              {notFound && (
                <div
                  role="alert"
                  className="sd-field-error"
                  style={{ marginTop: '6px', fontSize: '13px' }}
                >
                  No parcel found for that number
                </div>
              )}
            </div>
            <div style={{ alignSelf: 'flex-start', marginTop: '24px' }}>
              <Button
                type="submit"
                variant="accent"
                icon={<SearchIcon size={18} />}
                disabled={loading}
              >
                Track
              </Button>
            </div>
          </div>
        </form>
      </Card>

      {/* Loading Skeleton */}
      {loading && (
        <Card span={12}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px', padding: '16px 0' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <Spinner size="normal" label="Fetching tracking details" />
              <span style={{ fontSize: '14px', color: 'var(--mute)' }}>Checking live dispatch network...</span>
            </div>
            <div className="sd-grid-12" style={{ marginTop: '8px' }}>
              <div className="sd-col-span-7">
                <Skeleton variant="card" height={320} />
              </div>
              <div className="sd-col-span-5" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                <Skeleton variant="card" height={160} />
                <Skeleton variant="card" height={120} />
              </div>
            </div>
          </div>
        </Card>
      )}

      {/* Error State with Retry */}
      {!loading && networkError && (
        <Card span={12}>
          <EmptyState
            title="Unable to load tracking details"
            description={networkError}
            icon={<CircleAlertIcon size={34} />}
            action={
              <Button
                variant="accent"
                size="small"
                onClick={() => void fetchTracking(searchedRef || inputRef.trim() || activeRef)}
              >
                Retry
              </Button>
            }
          />
        </Card>
      )}

      {/* Not Found State */}
      {!loading && notFound && (
        <Card span={12}>
          <EmptyState
            title="No parcel found for that number"
            description={`We could not locate any parcel matching reference "${searchedRef}". Please verify your tracking number and try again.`}
            icon={<SearchIcon size={34} />}
            action={
              <Button
                variant="outline"
                size="small"
                onClick={() => {
                  setInputRef('ZTR-SD8941K2M7');
                  setNotFound(false);
                  void fetchTracking('ZTR-SD8941K2M7');
                }}
              >
                Try sample: ZTR-SD8941K2M7
              </Button>
            }
          />
        </Card>
      )}

      {/* Parcel Content */}
      {!loading && parcel && stageData && (
        <>
          {/* Parcel Card (span 7) */}
          <Card span={7}>
            {/* Header: Tracking number (large) + Live indicator + Status chip */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
                <h2 className="sd-track-ref-large">{parcel.trackingRef}</h2>
                <span className="sd-live-badge">
                  <span className="sd-live-dot" aria-hidden="true" />
                  <span>Live</span>
                </span>
              </div>
              <StatusChip status={parcel.status} />
            </div>

            {/* Route subtitle: From to To • weight • size */}
            <p className="sd-route-subtitle" style={{ margin: '8px 0 16px' }}>
              {routeSubtitle}
            </p>

            {/* Route progress bar: from-place, track with --y fill, to-place */}
            <div className="sd-route-bar-wrap">
              <span className="sd-route-place">{parcel.sender?.city || 'From'}</span>
              <div
                className="sd-route-track"
                role="progressbar"
                aria-valuenow={stageData.progressPercent}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-label="Route progress"
              >
                <div
                  className="sd-route-fill"
                  style={{ width: `${stageData.progressPercent}%` }}
                />
              </div>
              <span className="sd-route-place">{parcel.recipient?.city || 'To'}</span>
            </div>

            {/* Vertical timeline of the 5 stages */}
            <div className="sd-stage-timeline" role="list" aria-label="Delivery journey stages">
              {stageData.stages.map((stage, idx) => {
                const isLast = idx === stageData.stages.length - 1;
                const isDone = stage.status === 'done';
                const isCurrent = stage.status === 'current';
                const isFailed = stage.status === 'failed';
                const isPending = stage.status === 'pending';

                let nodeClass = 'sd-stage-node';
                if (isDone) nodeClass += ' is-done';
                else if (isCurrent) nodeClass += ' is-current';
                else if (isFailed) nodeClass += ' is-failed';

                const lineClass = `sd-stage-line ${isDone ? 'is-done' : ''}`;

                return (
                  <div key={stage.id} className="sd-stage-item" role="listitem">
                    <div className="sd-stage-marker-col">
                      <div className={nodeClass} aria-label={`${stage.name}: ${stage.status}`}>
                        {isDone ? (
                          <CircleCheckIcon size={16} />
                        ) : isFailed ? (
                          <CircleAlertIcon size={16} />
                        ) : isCurrent ? (
                          <span
                            style={{
                              width: '8px',
                              height: '8px',
                              borderRadius: '50%',
                              background: 'var(--ink)',
                            }}
                          />
                        ) : (
                          <span>{idx + 1}</span>
                        )}
                      </div>
                      {!isLast && <div className={lineClass} aria-hidden="true" />}
                    </div>

                    <div className="sd-stage-body">
                      <div className="sd-stage-header">
                        <span className="sd-stage-name">{stage.name}</span>
                        {isDone && stage.dateTime && (
                          <span className="sd-stage-time">{stage.dateTime}</span>
                        )}
                        {isCurrent && stage.dateTime && (
                          <span className="sd-stage-time">{stage.dateTime}</span>
                        )}
                        {isPending && (
                          <span className="sd-stage-pending-tag">Pending</span>
                        )}
                      </div>

                      {isFailed && stage.reason && (
                        <p className="sd-stage-desc sd-stage-error-reason">{stage.reason}</p>
                      )}
                      {!isFailed && stage.description && (
                        <p className="sd-stage-desc">{stage.description}</p>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Footer with ETA and Simulate update */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginTop: '20px',
                paddingTop: '16px',
                borderTop: '1px solid var(--line)',
                flexWrap: 'wrap',
                gap: '12px',
              }}
            >
              <div className={`sd-eta-banner ${stageData.isFailed ? 'is-failed' : ''}`} style={{ margin: 0, padding: 0, background: 'transparent', border: 'none' }}>
                <ClockIcon size={16} />
                <span style={{ fontSize: '13px', color: 'var(--mute)' }}>
                  {stageData.etaText}
                </span>
              </div>

              <Button
                type="button"
                variant="outline"
                size="small"
                style={{ borderRadius: '9999px', padding: '6px 16px', fontSize: '13px' }}
                onClick={() => {
                  const nextStatus = parcel.status === 'In progress' ? 'Delivered' : 'In progress';
                  setParcel((prev) => (prev ? { ...prev, status: nextStatus } : null));
                }}
              >
                Simulate next update
              </Button>
            </div>
          </Card>

          {/* Right column (span 5): Your courier panel + Share tracking link card */}
          <div className="sd-col-span-5" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            {/* "Your courier" panel (dark --panel card) */}
            <Card variant="panel">
              <CardHeader>
                <div>
                  <span
                    style={{
                      fontSize: '11px',
                      fontWeight: '700',
                      letterSpacing: '0.08em',
                      color: 'var(--mute)',
                      textTransform: 'uppercase',
                    }}
                    className="sr-only"
                  >
                    Assigned Driver
                  </span>
                  <CardTitle style={{ color: 'var(--onDark)', marginTop: '2px', fontSize: '18px' }}>
                    Your courier
                  </CardTitle>
                </div>
              </CardHeader>
              <CardContent>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <div>
                    <div style={{ fontSize: '20px', fontWeight: '600', color: 'var(--onDark)' }}>
                      {courier.name || 'Saman Perera'}
                    </div>
                    <div className="sd-courier-vehicle" style={{ color: 'var(--mute)', fontSize: '13px', marginTop: '2px' }}>
                      <span>{courier.vehicle || 'Motorbike • Colombo zone'}</span>
                      {courier.zone && !courier.vehicle?.includes(courier.zone) ? (
                        <>
                          <span aria-hidden="true"> • </span>
                          <span>{courier.zone}</span>
                        </>
                      ) : null}
                    </div>
                  </div>

                  <div style={{ marginTop: '12px' }}>
                    <Button
                      type="button"
                      variant="accent"
                      icon={<PhoneIcon size={15} />}
                      style={{
                        borderRadius: '9999px',
                        padding: '8px 20px',
                      }}
                      onClick={() => {
                        if (courier.phone) {
                          window.location.href = `tel:${courier.phone.replace(/\s+/g, '')}`;
                        }
                      }}
                    >
                      Call courier
                    </Button>
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* "Share tracking link" card */}
            <Card>
              <CardHeader>
                <div>
                  <span
                    style={{
                      fontSize: '11px',
                      fontWeight: '700',
                      letterSpacing: '0.08em',
                      color: 'var(--mute)',
                      textTransform: 'uppercase',
                    }}
                    className="sr-only"
                  >
                    Public URL
                  </span>
                  <CardTitle style={{ fontSize: '18px' }}>
                    Share tracking link
                  </CardTitle>
                </div>
              </CardHeader>
              <CardContent>
                <div className="sd-share-row" style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                  <Input
                    type="text"
                    readOnly
                    value={shareUrl}
                    className="sd-input sd-pill-input"
                    style={{ flex: 1, minWidth: 0, borderRadius: '9999px', padding: '10px 18px', fontSize: '13px' }}
                    aria-label="Public tracking link"
                    onClick={(e) => (e.target as HTMLInputElement).select()}
                  />
                  <Button
                    type="button"
                    variant="primary"
                    style={{ borderRadius: '9999px', padding: '10px 20px' }}
                    onClick={handleCopyLink}
                  >
                    Copy
                  </Button>
                </div>
                <p style={{ fontSize: '12px', color: 'var(--mute)', margin: '10px 0 0' }}>
                  Receive updates by email or SMS at every status change.
                </p>
                {clipboardBlocked && (
                  <p
                    className="sd-field-error"
                    role="alert"
                    style={{ fontSize: '13px', marginTop: '8px' }}
                  >
                    Select the link and copy it
                  </p>
                )}
              </CardContent>
            </Card>
          </div>
        </>
      )}

      {/* Initial empty state when no search reference was specified */}
      {!loading && !parcel && !notFound && !networkError && !activeRef && (
        <Card span={12}>
          <EmptyState
            title="Track any SwiftDrop delivery"
            description="Enter a tracking reference above to view live progress, courier pickup, dispatch checkpoints, and estimated arrival."
            icon={<PackageIcon size={34} />}
            action={
              <Button
                variant="outline"
                size="small"
                onClick={() => {
                  setInputRef('ZTR-SD8941K2M7');
                  void fetchTracking('ZTR-SD8941K2M7');
                }}
              >
                Track sample shipment (ZTR-SD8941K2M7)
              </Button>
            }
          />
        </Card>
      )}
    </div>
  );
};

export default TrackView;
