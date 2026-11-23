import axios from 'axios';
import { ExternalLink, RefreshCw, Search } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import {
  completeProofReview,
  decideProofReview,
  listProofReviews,
  proofPhotoUrl,
  type ProofReview,
  type ProofReviewStatus,
} from '../api/proofReviewsApi';
import { useDispatcherParcels } from '../api/parcelsApi';
import './ProofReviewsPage.css';

const STATES: ProofReviewStatus[] = [
  'PENDING_REVIEW',
  'COMPLETION_PENDING',
  'REJECTED',
  'COMPLETION_PROCESSING',
  'COMPLETED',
  'BLOCKED',
];

const labels: Record<ProofReviewStatus, string> = {
  PENDING_REVIEW: 'Pending',
  COMPLETION_PENDING: 'Approved',
  REJECTED: 'Rejected',
  COMPLETION_PROCESSING: 'Completion pending',
  COMPLETED: 'Completed',
  COMPLETION_FAILED: 'Failed',
  BLOCKED: 'Blocked',
};

export function ProofReviewsPage() {
  const { user } = useAuth();
  const token = user?.accessToken || '';
  const client = useQueryClient();
  const parcelData = useDispatcherParcels();

  const [state, setState] = useState<ProofReviewStatus | 'ALL'>('ALL');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<ProofReview | null>(null);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const queries = useQueries({
    queries: STATES.map((decision) => ({
      queryKey: ['proof-reviews', decision],
      enabled: Boolean(token),
      queryFn: () => listProofReviews(decision, token),
    })),
  });

  const records = useMemo(
    () =>
      Object.fromEntries(
        STATES.map((s, i) => [s, queries[i].data ?? []]),
      ) as Record<ProofReviewStatus, ProofReview[]>,
    [queries],
  );

  const list = useMemo(() => {
    const raw = state === 'ALL' ? STATES.flatMap((s) => records[s]) : records[state] ?? [];
    return raw.filter((r) => {
      const p = parcelData.parcels.find((x) => x.parcelId === r.parcelId);
      const needle = `${p?.reference ?? ''} ${p?.route ?? ''} ${r.courierName ?? ''} ${r.recipientName}`.toLowerCase();
      return needle.includes(search.trim().toLowerCase());
    });
  }, [records, state, search, parcelData.parcels]);

  const unavailable = queries.some(
    (q) => axios.isAxiosError(q.error) && q.error.response?.status === 404,
  );
  const refresh = () => void client.invalidateQueries({ queryKey: ['proof-reviews'] });

  useEffect(() => {
    if (!selected && list.length > 0) {
      setSelected(list[0]);
    } else if (selected && !list.some((r) => r.id === selected.id) && list.length > 0) {
      setSelected(list[0]);
    }
  }, [list, selected]);

  const photoQuery = useQuery({
    queryKey: ['proof-photo', selected?.id],
    enabled: Boolean(selected?.id && token),
    queryFn: () => proofPhotoUrl(selected!.id, token),
    staleTime: 5 * 60 * 1000,
    retry: 2,
  });

  const decide = async (review: ProofReview, decision: 'approve' | 'reject') => {
    if (decision === 'reject' && !reason.trim()) {
      setNotice('Enter a rejection reason before rejecting.');
      return;
    }
    setSubmitting(true);
    setNotice('');
    try {
      await decideProofReview(review, decision, token, reason.trim());
      if (decision === 'approve') {
        try {
          await completeProofReview(review.id, token);
        } catch {
          // completion can also be triggered manually
        }
      }
      setNotice(`Proof ${decision}d successfully.`);
      setReason('');
      refresh();
    } catch {
      setNotice('The review status changed or could not be saved. Refresh and try again.');
    } finally {
      setSubmitting(false);
    }
  };

  const parcel = selected ? parcelData.parcels.find((x) => x.parcelId === selected.parcelId) : undefined;

  return (
    <main className="sd-page-container proof-reviews-page">
      <header className="proof-heading">
        <div>
          <span className="dispatch-eyebrow">SWIFTDROP OPERATIONS</span>
          <h1>Proof Reviews</h1>
          <p>Review delivery photos and recipient signatures before completing assignments.</p>
        </div>
        <button type="button" className="staff-review-button" onClick={refresh}>
          <RefreshCw size={14} style={{ marginRight: 6 }} /> Refresh
        </button>
      </header>

      {notice && (
        <div className="sd-parcels-notice" role="status" style={{ marginBottom: 16 }}>
          {notice}
        </div>
      )}

      {unavailable ? (
        <div className="sd-parcels-notice is-error" role="alert">
          <strong>POD review service is not active</strong>
          <p>
            V7 private proof storage and POD services must be active before submitted proofs can appear
            here.
          </p>
        </div>
      ) : (
        <>
          <div className="proof-summary" role="tablist" aria-label="Review status filters">
            {(
              [
                {
                  key: 'ALL',
                  text: 'All',
                  count: STATES.reduce((n, s) => n + records[s].length, 0),
                },
                ...STATES.map((s) => ({ key: s, text: labels[s], count: records[s].length })),
              ] as { key: string; text: string; count: number }[]
            ).map((item) => (
              <button
                key={item.key}
                type="button"
                role="tab"
                aria-selected={state === item.key}
                className={`proof-kpi-btn ${state === item.key ? 'is-selected' : ''}`}
                onClick={() => setState(item.key as ProofReviewStatus | 'ALL')}
              >
                <span className="proof-kpi-label">{item.text}</span>
                <strong className="proof-kpi-count">{item.count}</strong>
              </button>
            ))}
          </div>

          <div className="proof-toolbar">
            <div className="proof-search-box">
              <Search size={16} />
              <input
                aria-label="Search proofs"
                placeholder="Search tracking, route, courier or recipient…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
            <select
              className="proof-filter-select"
              aria-label="Filter status"
              value={state}
              onChange={(e) => setState(e.target.value as ProofReviewStatus | 'ALL')}
            >
              <option value="ALL">
                All ({STATES.reduce((n, s) => n + records[s].length, 0)})
              </option>
              {STATES.map((s) => (
                <option key={s} value={s}>
                  {labels[s]} ({records[s].length})
                </option>
              ))}
            </select>
          </div>

          <div className="proof-layout">
            <section className="proof-table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Tracking</th>
                    <th>Route</th>
                    <th>Courier</th>
                    <th>Recipient</th>
                    <th>Submitted</th>
                    <th>Status</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((r) => {
                    const p = parcelData.parcels.find((x) => x.parcelId === r.parcelId);
                    return (
                      <tr
                        key={r.id}
                        className={selected?.id === r.id ? 'is-selected' : ''}
                        onClick={() => setSelected(r)}
                      >
                        <td>
                          <strong>{p?.reference ?? r.parcelId}</strong>
                        </td>
                        <td>{p?.route ?? 'Route unavailable'}</td>
                        <td>{r.courierName ?? p?.courier ?? 'Unavailable'}</td>
                        <td>{r.recipientName}</td>
                        <td style={{ whiteSpace: 'nowrap' }}>
                          {new Date(r.submittedAt).toLocaleDateString([], {
                            month: 'short',
                            day: 'numeric',
                            hour: '2-digit',
                            minute: '2-digit',
                          })}
                        </td>
                        <td>
                          <span
                            className={
                              r.decision === 'COMPLETED'
                                ? 'sd-badge-dark'
                                : r.decision === 'REJECTED'
                                  ? 'sd-badge-danger'
                                  : 'sd-badge-yellow'
                            }
                          >
                            {labels[r.decision]}
                          </span>
                        </td>
                        <td>
                          <div className="proof-table-actions">
                            {r.decision === 'PENDING_REVIEW' && (
                              <>
                                <button
                                  type="button"
                                  className="staff-review-button is-approve"
                                  disabled={submitting}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    void decide(r, 'approve');
                                  }}
                                >
                                  Approve
                                </button>
                                <button
                                  type="button"
                                  className="staff-review-button is-reject"
                                  disabled={submitting}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setSelected(r);
                                  }}
                                >
                                  Reject
                                </button>
                              </>
                            )}
                            {r.decision === 'COMPLETION_PENDING' && (
                              <button
                                type="button"
                                className="staff-review-button is-approve"
                                disabled={submitting}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  void completeProofReview(r.id, token)
                                    .then(refresh)
                                    .catch(() => setNotice('Completion could not be requested.'));
                                }}
                              >
                                Complete
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                  {!list.length && (
                    <tr>
                      <td colSpan={7} style={{ textAlign: 'center', padding: '32px 16px', color: 'var(--mute)' }}>
                        No {state === 'ALL' ? '' : labels[state].toLowerCase()} proof reviews found.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </section>

            <aside className="proof-detail-card">
              {selected ? (
                <>
                  <div className="proof-detail-header">
                    <h2 className="proof-detail-ref">{parcel?.reference ?? 'Proof Detail'}</h2>
                    <div className="proof-detail-meta">
                      <span className="sd-badge-yellow">{labels[selected.decision]}</span>
                      <span>·</span>
                      <time dateTime={selected.submittedAt}>
                        {new Date(selected.submittedAt).toLocaleString()}
                      </time>
                    </div>
                  </div>

                  <dl className="proof-facts-grid">
                    <dt>Courier</dt>
                    <dd>{selected.courierName ?? parcel?.courier ?? 'Unavailable'}</dd>
                    <dt>Recipient</dt>
                    <dd>{selected.recipientName}</dd>
                    <dt>Notes</dt>
                    <dd>{selected.notes || 'None'}</dd>
                    <dt>Proof Reference</dt>
                    <dd>{selected.proofReference || 'Unavailable'}</dd>
                  </dl>

                  {/* Directly displayed delivery photo */}
                  <div className="proof-photo-box">
                    {photoQuery.isLoading && (
                      <div className="proof-photo-loading">Loading secure delivery photo…</div>
                    )}
                    {photoQuery.data && (
                      <>
                        <img
                          src={photoQuery.data}
                          alt="Delivery proof evidence"
                          onClick={() => window.open(photoQuery.data, '_blank')}
                          title="Click to view full size photo"
                        />
                        <div style={{ padding: '8px 12px', background: 'var(--paper)', textAlign: 'right', borderTop: '1px solid var(--line)' }}>
                          <a
                            href={photoQuery.data}
                            target="_blank"
                            rel="noopener noreferrer"
                            style={{
                              fontSize: 12,
                              color: 'var(--ink)',
                              display: 'inline-flex',
                              alignItems: 'center',
                              gap: 4,
                              textDecoration: 'none',
                              fontWeight: 600,
                            }}
                          >
                            Open full photo <ExternalLink size={12} />
                          </a>
                        </div>
                      </>
                    )}
                    {photoQuery.isError && (
                      <div className="proof-photo-error">
                        <p style={{ margin: '0 0 8px' }}>Delivery photo unavailable.</p>
                        <button
                          type="button"
                          className="staff-review-button"
                          style={{ minHeight: 30, fontSize: 12, padding: '0 12px' }}
                          onClick={() => void photoQuery.refetch()}
                        >
                          Retry
                        </button>
                      </div>
                    )}
                  </div>

                  {selected.rejectionReason && (
                    <div style={{ margin: '14px 0', padding: '10px 14px', background: 'var(--badge-danger-bg)', borderRadius: 10, border: '1px solid var(--badge-danger-border)', fontSize: 13, color: 'var(--err)' }}>
                      <strong>Rejection reason:</strong> {selected.rejectionReason}
                    </div>
                  )}

                  {selected.completionErrorCode && (
                    <div style={{ margin: '14px 0', padding: '10px 14px', background: 'var(--paper)', borderRadius: 10, fontSize: 13 }}>
                      <strong>Completion outcome:</strong> {selected.completionErrorCode}
                    </div>
                  )}

                  {selected.decision === 'PENDING_REVIEW' && (
                    <div className="proof-decision-form">
                      <input
                        className="proof-reason-input"
                        placeholder="Rejection reason (required if rejecting)"
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                      />
                      <div className="proof-decision-actions">
                        <button
                          type="button"
                          className="staff-review-button is-approve"
                          disabled={submitting}
                          onClick={() => void decide(selected, 'approve')}
                        >
                          Approve
                        </button>
                        <button
                          type="button"
                          className="staff-review-button is-reject"
                          disabled={submitting}
                          onClick={() => void decide(selected, 'reject')}
                        >
                          Reject
                        </button>
                      </div>
                    </div>
                  )}

                  {selected.decision === 'COMPLETION_PENDING' && (
                    <div className="proof-decision-form">
                      <button
                        type="button"
                        className="staff-review-button is-approve"
                        style={{ width: '100%' }}
                        disabled={submitting}
                        onClick={() =>
                          void completeProofReview(selected.id, token)
                            .then(refresh)
                            .catch(() => setNotice('Completion could not be requested.'))
                        }
                      >
                        Complete Delivery
                      </button>
                    </div>
                  )}
                </>
              ) : (
                <p style={{ textAlign: 'center', padding: '40px 16px', color: 'var(--mute)', fontSize: 13 }}>
                  Select a proof review from the list to see delivery photo and review actions.
                </p>
              )}
            </aside>
          </div>
        </>
      )}
    </main>
  );
}
