import { useState, useMemo, useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Search } from 'lucide-react';
import { friendlyApiError } from '@/shared/api/apiClient';
import { useDispatcherParcels, type DispatcherParcel, type ParcelStatus } from '../api/parcelsApi';
import { useAuth } from '@/shared/auth/useAuth';
import { completeProofReview, decideProofReview, listProofReviews, proofPhotoUrl, type ProofReview } from '../api/proofReviewsApi';
import './ParcelsPage.css';

export type { ParcelStatus } from '../api/parcelsApi';

type FilterTab = 'All' | 'Unassigned' | 'Active' | 'Delivered' | 'Failed';
const ACTIVE_STATUSES: ParcelStatus[] = ['Assigned', 'Picked up', 'In transit', 'Out for delivery'];
const ASSIGNMENT_LABELS: Record<string, string> = {
  PENDING_ACCEPTANCE: 'Awaiting courier acceptance',
  ACCEPTED: 'Accepted by courier',
};

export function ParcelsPage() {
  const { user } = useAuth();
  const token = user?.accessToken || '';
  const client = useQueryClient();
  const data = useDispatcherParcels();
  const { parcels } = data;
  const [activeTab, setActiveTab] = useState<FilterTab>('All');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [rejectionReason, setRejectionReason] = useState('');
  const [proofNotice, setProofNotice] = useState('');
  const reviews = useQuery({
    queryKey: ['proof-reviews', 'ALL'],
    enabled: Boolean(token),
    queryFn: async () => {
      const [pending, approved, completed] = await Promise.all([
        listProofReviews('PENDING_REVIEW', token).catch(() => []),
        listProofReviews('COMPLETION_PENDING', token).catch(() => []),
        listProofReviews('COMPLETED', token).catch(() => []),
      ]);
      return [...pending, ...approved, ...completed];
    }
  });
  const selectedParcel = parcels.find(parcel => parcel.parcelId === selectedId) ?? null;
  const selectedReview = reviews.data?.find(review => review.parcelId === selectedId) ?? null;

  const photoQuery = useQuery({
    queryKey: ['proof-photo', selectedReview?.id],
    enabled: Boolean(selectedReview?.id && token),
    queryFn: () => proofPhotoUrl(selectedReview!.id, token),
    staleTime: 5 * 60 * 1000,
    retry: 2,
  });

  const decide = async (review: ProofReview, choice: 'approve' | 'reject') => {
    if (choice === 'reject' && !rejectionReason.trim()) { setProofNotice('A rejection reason is required.'); return; }
    try {
      if (review.decision === 'COMPLETION_PENDING') {
        await completeProofReview(review.id, token);
        setProofNotice('Delivery completed.');
      } else {
        await decideProofReview(review, choice, token, rejectionReason.trim());
        if (choice === 'approve') {
          try {
            await completeProofReview(review.id, token);
          } catch {
            // completion might take a moment
          }
        }
        setProofNotice(`Proof ${choice}d.`);
      }
      setRejectionReason('');
      void client.invalidateQueries({ queryKey: ['proof-reviews'] });
      void data.refresh();
    }
    catch { setProofNotice('The proof review could not be saved. Refresh and try again.'); }
  };
  const loaded = !data.isPending && !data.error;

  const filteredParcels = useMemo(() => {
    return parcels.filter((p) => {
      if (activeTab === 'Unassigned' && p.status !== 'Unassigned') return false;
      if (activeTab === 'Active' && !ACTIVE_STATUSES.includes(p.status)) return false;
      if (activeTab === 'Delivered' && p.status !== 'Delivered') return false;
      if (activeTab === 'Failed' && p.status !== 'Failed') return false;

      if (searchQuery.trim()) {
        const q = searchQuery.trim().toLowerCase();
        return (
          p.reference.toLowerCase().includes(q) ||
          p.route.toLowerCase().includes(q) ||
          (p.courier ?? '').toLowerCase().includes(q)
        );
      }
      return true;
    });
  }, [parcels, activeTab, searchQuery]);

  const getStatusBadge = (status: ParcelStatus) => {
    switch (status) {
      case 'Unassigned':
        return <span className="sd-status-text">Unassigned</span>;
      case 'Assigned':
      case 'Picked up':
      case 'In transit':
      case 'Out for delivery':
        return <span className="sd-badge-yellow">{status}</span>;
      case 'Delivered':
        return <span className="sd-badge-dark">{status}</span>;
      case 'Failed':
        return <span className="sd-badge-danger">{status}</span>;
      default:
        return <span>{status}</span>;
    }
  };

  const isMilestoneReached = (milestone: string, status: ParcelStatus) => {
    const sequence = ['Created', 'Picked up', 'In transit', 'Out for delivery', 'Delivered'];
    const currentIdx =
      status === 'Unassigned' || status === 'Assigned'
        ? 0
        : status === 'Picked up'
          ? 1
          : status === 'In transit'
            ? 2
            : status === 'Out for delivery'
              ? 3
              : status === 'Delivered'
                ? 4
                : 0;
    const targetIdx = sequence.indexOf(milestone);
    return targetIdx <= currentIdx;
  };

  const subtitle = data.isPending
    ? 'Loading parcels…'
    : data.error
      ? 'Parcel totals unavailable'
      : `${parcels.length} parcels · ${parcels.filter((parcel) => parcel.status === 'Unassigned').length} unassigned${data.isFetching ? ' · Refreshing…' : ''}`;

  return (
    <main className="sd-page-container sd-parcels-page">
      <section className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Parcels</h1>
          <p className="sd-page-subtitle" role="status">{subtitle}</p>
        </div>
        <button
          type="button"
          className="sd-filter-pill"
          disabled={data.isFetching}
          onClick={() => void data.refresh()}
        >
          {data.isFetching ? 'Refreshing…' : 'Refresh'}
        </button>
      </section>

      {data.error && (
        <div className="sd-parcels-notice is-error" role="alert">
          <strong>Parcels unavailable</strong>
          <p>{friendlyApiError(data.error, 'We cannot load dispatcher parcels right now. Parcel totals are unavailable.')}</p>
          <button type="button" className="sd-btn-yellow" onClick={() => void data.refresh()}>Retry</button>
        </div>
      )}
      {loaded && data.failedDetails > 0 && (
        <div className="sd-parcels-notice" role="alert">
          <strong>Some parcel details are unavailable</strong>
          <p>
            Details could not be loaded for {data.failedDetails} parcel{data.failedDetails === 1 ? '' : 's'}. Their
            status uses dispatch data only.
          </p>
          <button type="button" className="sd-btn-yellow" onClick={() => void data.refresh()}>Retry</button>
        </div>
      )}
      {loaded && !data.queueComplete && (
        <p className="sd-parcels-notice" role="note">
          The dispatch queue snapshot is incomplete; some unassigned parcels may be missing.
        </p>
      )}
      {proofNotice && <p className="sd-parcels-notice" role="status">{proofNotice}</p>}

      <div className="sd-parcels-layout">
        <div className="sd-card sd-parcels-card">
          <div className="sd-filter-pills-row" role="tablist" aria-label="Parcel status filters">
            {(['All', 'Unassigned', 'Active', 'Delivered', 'Failed'] as FilterTab[]).map((tab) => (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={activeTab === tab}
                className={`sd-filter-pill ${activeTab === tab ? 'is-active' : ''}`}
                onClick={() => setActiveTab(tab)}
              >
                {tab}
              </button>
            ))}
          </div>

          <div className="sd-search-box">
            <Search size={16} className="sd-search-icon" />
            <input
              type="text"
              placeholder="Search by reference or place"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="sd-search-input"
              aria-label="Search by reference or place"
            />
          </div>

          <div className="sd-table-wrap" role="region" aria-label="Parcels table" aria-busy={data.isFetching} tabIndex={0}>
            <table className="sd-parcels-table">
              <caption className="sr-only">
                Parcels matching the selected status and search. Select a reference to view details.
              </caption>
              <thead>
                <tr>
                  <th scope="col">Reference</th>
                  <th scope="col">Route</th>
                  <th scope="col">Status</th>
                  <th scope="col">Courier</th><th scope="col">POD review</th>
                </tr>
              </thead>
              <tbody>
                {!loaded && (
                  <tr>
                    <td colSpan={5} className="sd-table-empty">
                      {data.isPending ? 'Loading parcels…' : 'Parcels could not be loaded.'}
                    </td>
                  </tr>
                )}
                {loaded && !filteredParcels.length && (
                  <tr>
                    <td colSpan={5} className="sd-table-empty">
                      {parcels.length ? 'No parcels match your filters and search.' : 'There are no parcels yet.'}
                    </td>
                  </tr>
                )}
                {loaded && filteredParcels.map((parcel) => {
                  const isSelected = selectedParcel?.parcelId === parcel.parcelId;
                  return (
                    <tr
                      key={parcel.parcelId}
                      onClick={() => setSelectedId(parcel.parcelId)}
                      className={`sd-clickable-row ${isSelected ? 'is-selected' : ''}`}
                    >
                      <td className="sd-cell-ref">
                        <button
                          type="button"
                          className="sd-ref-link"
                          aria-pressed={isSelected}
                          aria-controls="parcel-detail-panel"
                          onClick={(e) => {
                            e.stopPropagation();
                            setSelectedId(parcel.parcelId);
                          }}
                        >
                          {parcel.reference}
                        </button>
                      </td>
                      <td className="sd-cell-route">{parcel.route}</td>
                      <td className="sd-cell-status">{getStatusBadge(parcel.status)}</td>
                      <td className="sd-cell-courier">{parcel.courier ?? '—'}</td>
                      <td>{reviews.data?.some(review => review.parcelId === parcel.parcelId) ? <span className="sd-badge-yellow">Pending review</span> : '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>

        <section
          className="sd-card sd-detail-card"
          id="parcel-detail-panel"
          aria-labelledby="parcel-detail-title"
          aria-label="Parcel detail"
        >
          {selectedParcel ? (
            <ParcelDetailPanel parcel={selectedParcel} review={selectedReview} rejectionReason={rejectionReason} setRejectionReason={setRejectionReason} photoUrl={photoQuery.data} photoLoading={photoQuery.isLoading} photoError={photoQuery.isError} onRetryPhoto={() => void photoQuery.refetch()} onDecide={decide} isMilestoneReached={isMilestoneReached} />
          ) : (
            <div className="sd-detail-empty">
              <h2 className="sd-detail-title" id="parcel-detail-title">
                Parcel detail
              </h2>
              <p className="sd-detail-empty-msg">Select a parcel to view its details.</p>
            </div>
          )}
        </section>
      </div>
    </main>
  );
}

function ParcelDetailPanel({
  parcel,
  review,
  rejectionReason,
  setRejectionReason,
  photoUrl,
  photoLoading,
  photoError,
  onRetryPhoto,
  onDecide,
  isMilestoneReached,
}: {
  parcel: DispatcherParcel;
  review: ProofReview | null;
  rejectionReason: string;
  setRejectionReason: (value: string) => void;
  photoUrl?: string;
  photoLoading?: boolean;
  photoError?: boolean;
  onRetryPhoto: () => void;
  onDecide: (review: ProofReview, choice: 'approve' | 'reject') => Promise<void>;
  isMilestoneReached: (milestone: string, status: ParcelStatus) => boolean;
}) {
  return (
    <>
      <div className="sd-detail-header">
        <h2 className="sd-detail-title" id="parcel-detail-title">
          Parcel detail
        </h2>
        <div className="sd-detail-ref">{parcel.reference}</div>
        <div className="sd-detail-tags">
          <span className="sd-tag-status">{parcel.status}</span>
        </div>
      </div>

      <dl className="sd-detail-facts">
        <div className="sd-detail-route">
          <dt>Route</dt>
          <dd>{parcel.route}</dd>
        </div>
        <div>
          <dt>Size</dt>
          <dd>{parcel.weightCategory || 'Unavailable'}</dd>
        </div>
        <div>
          <dt>Weight</dt>
          <dd>{parcel.weightKg || 'Unavailable'}</dd>
        </div>
      </dl>
      <div className="sd-detail-courier-row">
        <span className="sd-courier-label">Courier</span>
        <span className="sd-courier-val">{parcel.courier ?? 'Unassigned'}</span>
      </div>
      {parcel.assignmentStatus && (
        <p className="sd-timeline-note">
          {ASSIGNMENT_LABELS[parcel.assignmentStatus] ?? `Assignment ${parcel.assignmentStatus.toLowerCase().replace(/_/g, ' ')}`}
        </p>
      )}
      {!parcel.detailAvailable && (
        <p className="sd-timeline-note">Parcel details are unavailable; showing dispatch data only.</p>
      )}
      <section className="sd-proof-panel">
        <h3>Proof review</h3>
        {review ? <>
          <p>
            <span className="sd-badge-yellow">
              {review.decision === 'COMPLETED' ? 'Completed' : review.decision === 'COMPLETION_PENDING' ? 'Approved' : 'Pending review'}
            </span> Recipient: {review.recipientName}
          </p>
          <p>Proof reference: {review.proofReference || 'Unavailable'}</p>
          <p>Notes: {review.notes || 'None'}</p>
          {photoLoading && (
            <div style={{ padding: '12px', background: 'var(--warm-pale)', borderRadius: '8px', textAlign: 'center', margin: '8px 0', fontSize: '13px', color: 'var(--stone)' }}>
              Loading proof photo…
            </div>
          )}
          {photoUrl && (
            <img className="proof-photo" src={photoUrl} alt="Private delivery proof" style={{ maxWidth: '100%', maxHeight: '260px', objectFit: 'contain', borderRadius: '8px', margin: '8px 0', display: 'block' }} />
          )}
          {photoError && (
            <div style={{ margin: '8px 0', fontSize: '13px', color: 'var(--err)' }}>
              Photo unavailable. <button type="button" onClick={onRetryPhoto} style={{ marginLeft: '8px', fontSize: '12px' }}>Retry</button>
            </div>
          )}
          {review.decision === 'PENDING_REVIEW' && (
            <>
              <input value={rejectionReason} placeholder="Rejection reason" onChange={e => setRejectionReason(e.target.value)}/>
              <div className="sd-proof-actions"><button className="sd-btn-yellow" onClick={() => void onDecide(review, 'approve')}>Approve & Complete</button><button onClick={() => void onDecide(review, 'reject')}>Reject</button></div>
            </>
          )}
          {review.decision === 'COMPLETION_PENDING' && (
            <div className="sd-proof-actions"><button className="sd-btn-yellow" onClick={() => void onDecide(review, 'approve')}>Complete delivery</button></div>
          )}
        </> : <p className="sd-timeline-note">No persisted pending proof review for this parcel.</p>}
      </section>

      <h3 className="sd-timeline-heading">Delivery progress</h3>
      <div className="sd-timeline-stepper" role="list" aria-label="Delivery progress">
        {['Created', 'Picked up', 'In transit', 'Out for delivery', 'Delivered'].map((step, i, arr) => {
          const reached = isMilestoneReached(step, parcel.status);
          return (
            <div
              key={step}
              className="sd-stepper-node"
              role="listitem"
              aria-current={
                step === parcel.status ||
                (step === 'Created' && ['Unassigned', 'Assigned'].includes(parcel.status))
                  ? 'step'
                  : undefined
              }
            >
              <div className="sd-node-bullet-col" aria-hidden="true">
                <div className={`sd-bullet ${reached ? 'is-reached' : ''}`} />
                {i < arr.length - 1 && <div className={`sd-bullet-line ${reached ? 'is-reached' : ''}`} />}
              </div>
              <div className="sd-node-text-col">
                <span className={`sd-step-name ${reached ? 'is-bold' : ''}`}>{step}</span>
                {step === parcel.status && <span className="sd-current-step">Current</span>}
              </div>
            </div>
          );
        })}
      </div>

      <p className="sd-timeline-note">
        Progress reflects the current parcel status. Event timestamps are unavailable.
      </p>
      {parcel.status === 'Unassigned' && (
        <div className="sd-detail-assign-section">
          <Link to="/dispatcher/queue" className="sd-btn-yellow">
            Assign in Dispatch Queue
          </Link>
        </div>
      )}
    </>
  );
}
