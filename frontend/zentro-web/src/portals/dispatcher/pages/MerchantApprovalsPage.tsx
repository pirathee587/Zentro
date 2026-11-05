import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  listMerchantApplications,
  reviewMerchantApplication,
  type MerchantApplication,
} from '../api/adminApi';

export const applicationsKey = ['dispatcher', 'merchant-applications'];

function displayDate(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? 'Date unavailable'
    : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date);
}

function StatusChip({ status }: { status: MerchantApplication['status'] }) {
  const map: Record<string, string> = {
    PENDING: 'staff-badge-pending',
    APPROVED: 'staff-badge-active',
    REJECTED: 'staff-badge-disabled',
  };
  return <span className={`staff-badge ${map[status] ?? ''}`}>{status}</span>;
}

export function useMerchantApplications() {
  const { user } = useAuth();
  return useQuery({
    queryKey: applicationsKey,
    enabled: Boolean(user?.accessToken),
    retry: false,
    queryFn: listMerchantApplications,
  });
}

export function MerchantApprovalsPage() {
  const applications = useMerchantApplications();
  const queryClient = useQueryClient();
  const [rejectionReasons, setRejectionReasons] = useState<Record<string, string>>({});
  const [actionError, setActionError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  const review = useMutation({
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

  return (
    <main className="staff-main">
      <section className="staff-section">
        <div className="staff-section-heading">
          <div>
            <span className="staff-eyebrow">DISPATCH DESK</span>
            <h1>Merchant applications</h1>
            <p>Review and action pending business profiles.</p>
          </div>
          <Link className="staff-text-link" to="/dispatcher">
            ← Back to overview
          </Link>
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

        {applications.isPending ? (
          <div className="staff-panel staff-empty">Loading applications…</div>
        ) : applications.isError ? (
          <div className="staff-panel staff-error" role="alert">
            {friendlyApiError(
              applications.error,
              'Could not load merchant applications. Please try again.',
            )}
          </div>
        ) : !applications.data?.length ? (
          <div className="staff-panel staff-empty">
            No merchant applications are waiting for review.
          </div>
        ) : (
          <div className="staff-review-list">
            {applications.data.map((application) => (
              <article className="staff-review-card" key={application.id}>
                <div className="staff-review-top">
                  <div>
                    <StatusChip status={application.status} />
                    <h2>{application.businessName}</h2>
                  </div>
                  <small>Submitted {displayDate(application.createdAt)}</small>
                </div>

                <div className="staff-review-meta">
                  <span>
                    Registration number
                    <strong>{application.businessRegNo || 'Not provided'}</strong>
                  </span>
                  <span>
                    Contact phone
                    <strong>{application.contactPhone}</strong>
                  </span>
                  {application.reviewedAt && (
                    <span>
                      Reviewed on
                      <strong>{displayDate(application.reviewedAt)}</strong>
                    </span>
                  )}
                  {application.status === 'REJECTED' && application.rejectionReason && (
                    <span style={{ gridColumn: '1 / -1' }}>
                      Rejection reason
                      <strong>{application.rejectionReason}</strong>
                    </span>
                  )}
                </div>

                {application.status === 'PENDING' && (
                  <div className="staff-review-actions">
                    <input
                      aria-label={`Reason for rejecting ${application.businessName}`}
                      placeholder="Rejection reason (required to reject)"
                      value={rejectionReasons[application.id] ?? ''}
                      onChange={(event) =>
                        setRejectionReasons((current) => ({
                          ...current,
                          [application.id]: event.target.value,
                        }))
                      }
                    />
                    <button
                      id={`reject-${application.id}`}
                      className="staff-review-button"
                      type="button"
                      disabled={review.isPending}
                      onClick={() => {
                        const reason = rejectionReasons[application.id]?.trim();
                        if (!reason) {
                          setActionError(
                            'Add a rejection reason before rejecting an application.',
                          );
                          return;
                        }
                        setActionError('');
                        review.mutate({ id: application.id, decision: 'REJECTED', rejectionReason: reason });
                      }}
                    >
                      Reject
                    </button>
                    <button
                      id={`approve-${application.id}`}
                      className="staff-review-button is-approve"
                      type="button"
                      disabled={review.isPending}
                      onClick={() => {
                        setActionError('');
                        review.mutate({ id: application.id, decision: 'APPROVED' });
                      }}
                    >
                      Approve
                    </button>
                  </div>
                )}
              </article>
            ))}
          </div>
        )}

        <p className="staff-review-notice" style={{ marginTop: '18px' }}>
          Recording approval updates the application status. Merchant role access is managed
          separately in Keycloak by an administrator.
        </p>
      </section>
    </main>
  );
}
