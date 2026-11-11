import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  listMerchantApplications,
  reviewMerchantApplication,
} from '../api/adminApi';
import {
  listCouriersAdmin,
  updateCourierAdmin,
} from '../api/courierAdminApi';
import {
  mapMerchantToPartner,
  mapCourierToPartner,
  type PartnerType,
  type PartnerStatus,
  type UnifiedPartnerItem,
} from '../api/partnerApprovalApi';
import { PartnerDetailModal } from './PartnerDetailModal';
import { ActionConfirmDialog } from './ActionConfirmDialog';

export const applicationsKey = ['dispatcher', 'merchant-applications'];
export const couriersApprovalKey = ['dispatcher', 'couriers', 'partner-approvals'];

function displayDate(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? 'Date unavailable'
    : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date);
}

function StatusChip({ status }: { status: UnifiedPartnerItem['status'] }) {
  const map: Record<string, string> = {
    PENDING: 'staff-badge-pending',
    APPROVED: 'staff-badge-active',
    REJECTED: 'staff-badge-disabled',
    SUSPENDED: 'staff-badge-suspended',
  };
  return <span className={`staff-badge ${map[status] ?? ''}`}>{status}</span>;
}

export function useMerchantApplications(enabled = true) {
  const { user } = useAuth();
  return useQuery({
    queryKey: applicationsKey,
    enabled: Boolean(user?.accessToken) && enabled,
    queryFn: ({ signal }) => listMerchantApplications(signal),
  });
}

export function useCourierPartnerApplications(enabled = true) {
  const { user } = useAuth();
  return useQuery({
    queryKey: couriersApprovalKey,
    enabled: Boolean(user?.accessToken) && enabled,
    queryFn: ({ signal }) => listCouriersAdmin(undefined, user?.accessToken, signal),
  });
}

export function MerchantApprovalsPage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  // Filter states
  const [partnerTypeFilter, setPartnerTypeFilter] = useState<PartnerType>('MERCHANT');
  const [statusFilter, setStatusFilter] = useState<PartnerStatus>('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  const isMerchantTab = partnerTypeFilter === 'MERCHANT' || partnerTypeFilter === 'ALL';
  const isCourierTab = partnerTypeFilter === 'COURIER' || partnerTypeFilter === 'ALL';

  const applications = useMerchantApplications(isMerchantTab);
  const couriers = useCourierPartnerApplications(isCourierTab);

  // Form & Action states
  const [rejectionReasons, setRejectionReasons] = useState<Record<string, string>>({});
  const [actionError, setActionError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  // Modal inspection & confirmation states
  const [inspectedPartner, setInspectedPartner] = useState<UnifiedPartnerItem | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<{
    isOpen: boolean;
    title: string;
    message: string;
    confirmLabel: string;
    isDestructive: boolean;
    onConfirm: () => void;
  }>({
    isOpen: false,
    title: '',
    message: '',
    confirmLabel: '',
    isDestructive: false,
    onConfirm: () => undefined,
  });

  // Review Merchant Mutation
  const reviewMerchant = useMutation({
    mutationFn: async (input: {
      id: string;
      decision: 'APPROVED' | 'REJECTED';
      rejectionReason?: string;
    }) => {
      const { id, ...body } = input;
      return reviewMerchantApplication(id, body);
    },
    onSuccess: async (updated) => {
      setActionError('');
      setSuccessMessage(
        `${updated.businessName} has been ${updated.status.toLowerCase()} successfully.`,
      );
      await queryClient.invalidateQueries({ queryKey: applicationsKey });
      setTimeout(() => setSuccessMessage(''), 5000);
    },
    onError: (error) =>
      setActionError(
        friendlyApiError(error, 'Could not save the review decision. Please try again.'),
      ),
  });

  // Courier Approval / Eligibility Mutation
  const reviewCourier = useMutation({
    mutationFn: async (input: { id: string; active: boolean; name: string }) => {
      return updateCourierAdmin(input.id, { active: input.active }, user?.accessToken);
    },
    onSuccess: async (updated, variables) => {
      setActionError('');
      const statusLabel = variables.active ? 'activated' : 'deactivated';
      setSuccessMessage(
        `Courier ${variables.name} has been ${statusLabel} successfully.`,
      );
      await queryClient.invalidateQueries({ queryKey: couriersApprovalKey });
      setTimeout(() => setSuccessMessage(''), 5000);
    },
    onError: (error) =>
      setActionError(
        friendlyApiError(error, 'Could not update courier eligibility. Please try again.'),
      ),
  });

  const isActionPending = reviewMerchant.isPending || reviewCourier.isPending;

  // Combine and map applications
  const merchantItems: UnifiedPartnerItem[] = (applications.data ?? []).map(mapMerchantToPartner);
  const courierItems: UnifiedPartnerItem[] = (couriers.data ?? []).map(mapCourierToPartner);

  let allPartners: UnifiedPartnerItem[] = [];
  if (partnerTypeFilter === 'MERCHANT') {
    allPartners = merchantItems;
  } else if (partnerTypeFilter === 'COURIER') {
    allPartners = courierItems;
  } else {
    allPartners = [...merchantItems, ...courierItems].sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    );
  }

  // Filter by status
  if (statusFilter !== 'ALL') {
    allPartners = allPartners.filter((item) => item.status === statusFilter);
  }

  // Filter by search query
  if (searchQuery.trim()) {
    const q = searchQuery.trim().toLowerCase();
    allPartners = allPartners.filter(
      (item) =>
        item.name.toLowerCase().includes(q) ||
        item.identifier.toLowerCase().includes(q) ||
        item.phone.toLowerCase().includes(q) ||
        (item.assignedZone && item.assignedZone.toLowerCase().includes(q)),
    );
  }

  const isPendingLoading =
    partnerTypeFilter === 'MERCHANT'
      ? applications.isPending
      : partnerTypeFilter === 'COURIER'
        ? couriers.isPending
        : applications.isPending && couriers.isPending;

  const queryError =
    partnerTypeFilter === 'COURIER'
      ? couriers.error
      : applications.error || couriers.error;

  return (
    <main className="staff-main">
      <section className="staff-section">
        <div className="staff-section-heading">
          <div>
            <span className="staff-eyebrow">DISPATCH DESK</span>
            <h1>Merchant applications</h1>
            <p>Review and action pending business and courier fleet profiles.</p>
          </div>
          <Link className="staff-text-link" to="/dispatcher">
            ← Back to overview
          </Link>
        </div>

        {/* Filter bar for partner type, status, and search */}
        <div className="staff-filter-bar" style={{ marginBottom: '16px' }}>
          <div className="staff-filter-group" role="tablist" aria-label="Partner type filters">
            <button
              type="button"
              role="tab"
              aria-selected={partnerTypeFilter === 'MERCHANT'}
              className={`staff-review-button ${partnerTypeFilter === 'MERCHANT' ? 'is-approve' : ''}`}
              onClick={() => {
                setPartnerTypeFilter('MERCHANT');
                setActionError('');
              }}
            >
              Merchants ({merchantItems.length})
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={partnerTypeFilter === 'COURIER'}
              className={`staff-review-button ${partnerTypeFilter === 'COURIER' ? 'is-approve' : ''}`}
              onClick={() => {
                setPartnerTypeFilter('COURIER');
                setActionError('');
              }}
            >
              Couriers ({courierItems.length})
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={partnerTypeFilter === 'ALL'}
              className={`staff-review-button ${partnerTypeFilter === 'ALL' ? 'is-approve' : ''}`}
              onClick={() => {
                setPartnerTypeFilter('ALL');
                setActionError('');
              }}
            >
              All Partners ({merchantItems.length + courierItems.length})
            </button>
          </div>

          <div className="staff-filter-group">
            <input
              type="search"
              className="staff-filter-input"
              placeholder="Search by name, phone, reg no..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              aria-label="Search partner applications"
            />
            <select
              className="staff-filter-select"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as PartnerStatus)}
              aria-label="Filter by approval status"
            >
              <option value="ALL">All Statuses</option>
              <option value="PENDING">Pending Review</option>
              <option value="APPROVED">Approved / Active</option>
              <option value="REJECTED">Rejected</option>
              <option value="SUSPENDED">Suspended</option>
            </select>
          </div>
        </div>

        {actionError && (
          <p className="staff-review-notice" role="alert" id="merchant-action-error">
            {actionError}
          </p>
        )}
        {successMessage && (
          <p className="staff-review-success" role="status" id="merchant-action-success">
            {successMessage}
          </p>
        )}

        {isPendingLoading ? (
          <div className="staff-panel staff-empty">Loading applications…</div>
        ) : queryError ? (
          <div className="staff-panel staff-error" role="alert">
            {friendlyApiError(
              queryError,
              partnerTypeFilter === 'COURIER'
                ? 'Could not load courier profiles. Please try again.'
                : 'Could not load merchant applications. Please try again.',
            )}
          </div>
        ) : !allPartners.length ? (
          <div className="staff-panel staff-empty">
            {partnerTypeFilter === 'COURIER'
              ? 'No courier applications are waiting for review.'
              : 'No merchant applications are waiting for review.'}
          </div>
        ) : (
          <div className="staff-review-list">
            {allPartners.map((partner) => (
              <article className="staff-review-card" key={`${partner.partnerType}-${partner.id}`}>
                <div className="staff-review-top">
                  <div>
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '6px' }}>
                      <StatusChip status={partner.status} />
                      <span className="staff-role-pill">
                        {partner.partnerType === 'MERCHANT' ? 'Merchant' : 'Courier Fleet'}
                      </span>
                    </div>
                    <h2>{partner.name}</h2>
                  </div>
                  <small>Submitted {displayDate(partner.createdAt)}</small>
                </div>

                <div className="staff-review-meta">
                  <span>
                    {partner.partnerType === 'MERCHANT' ? 'Registration number' : 'Courier Email'}
                    <strong>{partner.identifier}</strong>
                  </span>
                  <span>
                    Contact phone
                    <strong>{partner.phone}</strong>
                  </span>
                  {partner.partnerType === 'COURIER' && (
                    <>
                      <span>
                        Vehicle Type
                        <strong>{partner.vehicleType || 'Unassigned'}</strong>
                      </span>
                      <span>
                        Assigned Zone
                        <strong>{partner.assignedZone || 'Unassigned'}</strong>
                      </span>
                    </>
                  )}
                  {partner.reviewedAt && (
                    <span>
                      Reviewed on
                      <strong>{displayDate(partner.reviewedAt)}</strong>
                    </span>
                  )}
                  {partner.status === 'REJECTED' && partner.rejectionReason && (
                    <span style={{ gridColumn: '1 / -1' }}>
                      Rejection reason
                      <strong>{partner.rejectionReason}</strong>
                    </span>
                  )}
                </div>

                <div className="staff-review-actions" style={{ justifyContent: 'space-between', marginTop: '16px' }}>
                  <button
                    type="button"
                    className="staff-text-link"
                    style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}
                    onClick={() => setInspectedPartner(partner)}
                  >
                    View details →
                  </button>

                  <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                    {/* Merchant Actions */}
                    {partner.partnerType === 'MERCHANT' && partner.status === 'PENDING' && (
                      <>
                        <input
                          aria-label={`Reason for rejecting ${partner.name}`}
                          placeholder="Rejection reason (required to reject)"
                          value={rejectionReasons[partner.id] ?? ''}
                          disabled={isActionPending}
                          onChange={(event) =>
                            setRejectionReasons((current) => ({
                              ...current,
                              [partner.id]: event.target.value,
                            }))
                          }
                        />
                        <button
                          id={`reject-${partner.id}`}
                          className="staff-review-button"
                          type="button"
                          disabled={isActionPending}
                          onClick={() => {
                            const reason = rejectionReasons[partner.id]?.trim();
                            if (!reason) {
                              setActionError('Add a rejection reason before rejecting an application.');
                              return;
                            }
                            setActionError('');
                            reviewMerchant.mutate({
                              id: partner.id,
                              decision: 'REJECTED',
                              rejectionReason: reason,
                            });
                          }}
                        >
                          Reject
                        </button>
                        <button
                          id={`approve-${partner.id}`}
                          className="staff-review-button is-approve"
                          type="button"
                          disabled={isActionPending}
                          onClick={() => {
                            setActionError('');
                            reviewMerchant.mutate({ id: partner.id, decision: 'APPROVED' });
                          }}
                        >
                          Approve
                        </button>
                      </>
                    )}

                    {/* Courier Actions */}
                    {partner.partnerType === 'COURIER' && (
                      <>
                        {partner.status === 'APPROVED' ? (
                          <button
                            id={`deactivate-courier-${partner.id}`}
                            className="staff-review-button"
                            type="button"
                            disabled={isActionPending}
                            onClick={() => {
                              setActionError('');
                              reviewCourier.mutate({ id: partner.id, active: false, name: partner.name });
                            }}
                          >
                            Deactivate
                          </button>
                        ) : (
                          <button
                            id={`activate-courier-${partner.id}`}
                            className="staff-review-button is-approve"
                            type="button"
                            disabled={isActionPending}
                            onClick={() => {
                              setActionError('');
                              reviewCourier.mutate({ id: partner.id, active: true, name: partner.name });
                            }}
                          >
                            Activate
                          </button>
                        )}
                      </>
                    )}
                  </div>
                </div>
              </article>
            ))}
          </div>
        )}

        <p className="staff-review-notice" style={{ marginTop: '18px' }}>
          Recording approval updates the application status. Merchant and courier role access is managed
          separately in Keycloak by an administrator.
        </p>
      </section>

      {/* Details inspection modal */}
      <PartnerDetailModal
        partner={inspectedPartner}
        onClose={() => setInspectedPartner(null)}
      />

      {/* Action confirmation dialog */}
      <ActionConfirmDialog
        isOpen={pendingConfirm.isOpen}
        title={pendingConfirm.title}
        message={pendingConfirm.message}
        confirmLabel={pendingConfirm.confirmLabel}
        isDestructive={pendingConfirm.isDestructive}
        isPending={isActionPending}
        onConfirm={pendingConfirm.onConfirm}
        onCancel={() => setPendingConfirm((prev) => ({ ...prev, isOpen: false }))}
      />
    </main>
  );
}
