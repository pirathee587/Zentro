import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button as BaseButton, Input as BaseInput } from '@/shared/ui';
import { Card, CardContent } from '../components/Card';
import { Button } from '../components/Button';
import { StatusChip } from '../components/StatusChip';
import { Skeleton } from '../components/Skeleton';
import { EmptyState } from '../components/EmptyState';
import { SearchIcon, CircleAlertIcon } from '../components/icons';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  listParcels,
  type Parcel,
  type ParcelStatus,
  formatMoney,
  formatDate,
  normalizeParcelStatus,
} from '../services/customerApi';

type FilterTab = 'ALL' | 'In progress' | 'Delivered' | 'Failed';

interface FilterOption {
  label: string;
  value: FilterTab;
}

const FILTER_OPTIONS: FilterOption[] = [
  { label: 'All', value: 'ALL' },
  { label: 'In progress', value: 'In progress' },
  { label: 'Delivered', value: 'Delivered' },
  { label: 'Failed', value: 'Failed' },
];

const PAGE_SIZE = 10;

export const HistoryPage: React.FC = () => {
  const navigate = useNavigate();

  const [statusFilter, setStatusFilter] = useState<FilterTab>('ALL');
  const [searchTerm, setSearchTerm] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');

  const [parcels, setParcels] = useState<Parcel[]>([]);
  const [page, setPage] = useState(1);
  const [totalCount, setTotalCount] = useState(0);
  const [totalSpent, setTotalSpent] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState('');

  // Track request sequence to prevent stale responses from overwriting newer search/filter results
  const activeRequestIdRef = useRef(0);

  // Debounce search input by 300ms
  useEffect(() => {
    const handler = setTimeout(() => {
      setDebouncedQuery(searchTerm.trim());
    }, 300);
    return () => clearTimeout(handler);
  }, [searchTerm]);

  const fetchParcels = useCallback(
    async (targetPage: number, append = false) => {
      const requestId = ++activeRequestIdRef.current;
      if (append) {
        setLoadingMore(true);
      } else {
        setLoading(true);
      }
      setError('');

      try {
        const res = await listParcels({
          page: targetPage,
          limit: PAGE_SIZE,
          status: statusFilter === 'ALL' ? undefined : (statusFilter as ParcelStatus),
          q: debouncedQuery || undefined,
          search: debouncedQuery || undefined,
        });

        // Discard stale response if a newer request was initiated
        if (requestId !== activeRequestIdRef.current) {
          return;
        }

        const incoming = res.parcels || [];

        if (append) {
          setParcels((prev) => {
            const existingKeys = new Set(prev.map((p) => p.id || p.trackingRef));
            const uniqueIncoming = incoming.filter(
              (p) => !existingKeys.has(p.id || p.trackingRef)
            );
            const combined = [...prev, ...uniqueIncoming];

            if (typeof res.totalSpent !== 'number') {
              const sum = combined.reduce((acc: number, p: Parcel) => acc + (p.fee || 0), 0);
              setTotalSpent(sum);
            }
            return combined;
          });
        } else {
          setParcels(incoming);
          if (typeof res.totalSpent !== 'number') {
            const sum = incoming.reduce((acc: number, p: Parcel) => acc + (p.fee || 0), 0);
            setTotalSpent(sum);
          }
        }

        if (typeof res.total === 'number') {
          setTotalCount(res.total);
        } else {
          setTotalCount((prevCount) => (append ? prevCount + incoming.length : incoming.length));
        }
        if (typeof res.totalSpent === 'number') {
          setTotalSpent(res.totalSpent);
        }
      } catch (err: unknown) {
        if (requestId !== activeRequestIdRef.current) {
          return;
        }
        if (append) {
          setPage((prevPage) => Math.max(1, prevPage - 1));
        }
        const friendlyMessage = friendlyApiError(
          err,
          'Unable to load deliveries right now. Please try again.'
        );
        setError(friendlyMessage);
      } finally {
        if (requestId === activeRequestIdRef.current) {
          setLoading(false);
          setLoadingMore(false);
        }
      }
    },
    [statusFilter, debouncedQuery]
  );

  // Trigger initial fetch when filter or debounced query changes
  useEffect(() => {
    setPage(1);
    void fetchParcels(1, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, debouncedQuery]);

  const handleLoadMore = () => {
    if (loadingMore || loading) return;
    const nextPage = page + 1;
    setPage(nextPage);
    void fetchParcels(nextPage, true);
  };

  const handleClearFilters = () => {
    setStatusFilter('ALL');
    setSearchTerm('');
    setDebouncedQuery('');
  };

  const subtitle = useMemo(() => {
    if (loading && parcels.length === 0) {
      return 'Loading delivery summary...';
    }
    return `${totalCount} parcels • ${formatMoney(totalSpent)} total`;
  }, [totalCount, totalSpent, loading, parcels.length]);

  return (
    <div>
      {/* Header */}
      <div className="sd-page-header">
        <h1 className="sd-page-title">Delivery history</h1>
        <p className="sd-page-subtitle">{subtitle}</p>
      </div>

      {/* Main Container Card */}
      <Card span={12}>
        <CardContent>
          {/* Controls: Filter chips + Search input */}
          <div className="sd-history-controls">
            <div
              className="sd-filter-chips"
              role="group"
              aria-label="Filter deliveries by status"
            >
              {FILTER_OPTIONS.map((opt) => {
                const isActive = statusFilter === opt.value;
                return (
                  <BaseButton
                    key={opt.value}
                    type="button"
                    aria-pressed={isActive}
                    className={`sd-filter-chip ${isActive ? 'is-active' : ''}`}
                    onClick={() => setStatusFilter(opt.value)}
                  >
                    {opt.label}
                  </BaseButton>
                );
              })}
            </div>

            <div className="sd-history-search-wrap">
              <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
                <span
                  style={{
                    position: 'absolute',
                    left: '14px',
                    color: 'var(--mute)',
                    display: 'flex',
                    alignItems: 'center',
                    pointerEvents: 'none',
                  }}
                  aria-hidden="true"
                >
                  <SearchIcon size={16} />
                </span>
                <BaseInput
                  type="text"
                  id="history-search-input"
                  className="sd-input sd-pill-input"
                  placeholder="Search tracking no., from or to place..."
                  aria-label="Search deliveries"
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  style={{ paddingLeft: '38px', width: '100%' }}
                />
              </div>
            </div>
          </div>

          {/* Error State with Retry and Auth Expiry Handling */}
          {!loading && error && (
            <EmptyState
              title="Unable to load deliveries"
              description={error}
              icon={<CircleAlertIcon size={34} />}
              action={
                <div style={{ display: 'inline-flex', gap: '8px' }}>
                  <Button
                    variant="accent"
                    size="small"
                    onClick={() => void fetchParcels(1, false)}
                  >
                    Retry
                  </Button>
                  {/session has expired/i.test(error) && (
                    <Button
                      variant="outline"
                      size="small"
                      onClick={() => navigate('/login')}
                    >
                      Sign in again
                    </Button>
                  )}
                </div>
              }
            />
          )}

          {/* Empty State */}
          {!loading && !error && parcels.length === 0 && (
            <EmptyState
              title="No parcels match"
              description="No deliveries found matching your current filter and search criteria."
              icon={<SearchIcon size={34} />}
              action={
                statusFilter !== 'ALL' || searchTerm.trim() ? (
                  <Button
                    variant="outline"
                    size="small"
                    onClick={handleClearFilters}
                  >
                    Clear filters
                  </Button>
                ) : undefined
              }
            />
          )}

          {/* Table Container */}
          {(loading || parcels.length > 0) && !error && (
            <div className="sd-table-wrap">
              <table className="sd-table" aria-label="Deliveries table">
                <thead>
                  <tr>
                    <th>Tracking no.</th>
                    <th className="sd-col-route">Route</th>
                    <th className="sd-col-date">Date</th>
                    <th>Status</th>
                    <th>Fee</th>
                    <th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>

                {loading ? (
                  <tbody>
                    {[1, 2, 3, 4, 5].map((idx) => (
                      <tr key={idx} className="sd-table-row sd-table-skeleton-row">
                        <td>
                          <Skeleton height={18} width="110px" />
                        </td>
                        <td className="sd-col-route">
                          <Skeleton height={18} width="160px" />
                        </td>
                        <td className="sd-col-date">
                          <Skeleton height={18} width="90px" />
                        </td>
                        <td>
                          <Skeleton
                            height={24}
                            width="85px"
                            style={{ borderRadius: '9999px' }}
                          />
                        </td>
                        <td>
                          <Skeleton height={18} width="70px" />
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <div
                            style={{
                              display: 'inline-flex',
                              justifyContent: 'flex-end',
                              gap: '6px',
                            }}
                          >
                            <Skeleton
                              height={28}
                              width="60px"
                              style={{ borderRadius: '9999px' }}
                            />
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                ) : (
                  <tbody>
                    {parcels.map((parcel, idx) => {
                      const normalizedStatus = normalizeParcelStatus(parcel.status || parcel.rawStatus);
                      const isDelivered = normalizedStatus === 'Delivered';
                      const isFailed = normalizedStatus === 'Failed';
                      const failureReason =
                        parcel.failureReason ||
                        parcel.timeline?.find(
                          (e) => e.status === 'failed' || /fail/i.test(e.title)
                        )?.description ||
                        'Delivery failed to complete.';

                      const routeText = `${parcel.sender?.city || parcel.sender?.address || 'Origin'} → ${
                        parcel.recipient?.city || parcel.recipient?.address || 'Destination'
                      }`;
                      const dateText =
                        parcel.formattedCreatedAt || (parcel.createdAt ? formatDate(parcel.createdAt) : '—');
                      const feeText =
                        parcel.formattedFee || (typeof parcel.fee === 'number' ? formatMoney(parcel.fee) : '—');
                      const trackingRef = parcel.trackingRef || parcel.id || '—';

                      return (
                        <tr key={parcel.id || parcel.trackingRef || `pcl-${idx}`} className="sd-table-row">
                          {/* Tracking No. & Secondary Failure Reason if Failed */}
                          <td>
                            <div className="sd-history-ref">{trackingRef}</div>
                            {isFailed && (
                              <div
                                className="sd-history-failed-reason"
                                tabIndex={0}
                                title={`Reason: ${failureReason}`}
                                aria-label={`Failure reason: ${failureReason}`}
                              >
                                Failed: {failureReason}
                              </div>
                            )}
                          </td>

                          {/* Route (Hidden on mobile) */}
                          <td className="sd-col-route">{routeText}</td>

                          {/* Date (Hidden on mobile) */}
                          <td className="sd-col-date">{dateText}</td>

                          {/* Status */}
                          <td>
                            <StatusChip status={normalizedStatus} />
                          </td>

                          {/* Fee */}
                          <td style={{ fontWeight: '500' }}>
                            {feeText}
                          </td>

                          {/* Actions */}
                          <td style={{ textAlign: 'right' }}>
                            <div
                              className="sd-table-actions"
                              style={{ justifyContent: 'flex-end' }}
                            >
                              <Button
                                variant="outline"
                                size="small"
                                onClick={() =>
                                  navigate(
                                    `/customer/track/${encodeURIComponent(
                                      trackingRef
                                    )}`
                                  )
                                }
                              >
                                Track
                              </Button>

                              {isDelivered && (
                                <Button
                                  variant="accent"
                                  size="small"
                                  onClick={() =>
                                    navigate(
                                      `/customer/receipts?trackingRef=${encodeURIComponent(
                                        trackingRef
                                      )}`
                                    )
                                  }
                                >
                                  Receipt
                                </Button>
                              )}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                )}
              </table>
            </div>
          )}

          {/* Pagination / Load more */}
          {!loading && !error && parcels.length > 0 && (
            <div className="sd-history-pagination">
              <span style={{ fontSize: '13px', color: 'var(--mute)' }}>
                Showing {parcels.length} of {totalCount} parcels
              </span>

              {parcels.length < totalCount && (
                <Button
                  variant="outline"
                  size="small"
                  onClick={handleLoadMore}
                  disabled={loadingMore}
                >
                  {loadingMore ? 'Loading more...' : 'Load more'}
                </Button>
              )}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
};

export default HistoryPage;
