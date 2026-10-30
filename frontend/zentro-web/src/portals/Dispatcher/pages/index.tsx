import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, friendlyApiError } from '@/shared/api/apiClient';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, Input } from '@/shared/ui';

type MerchantApplication = {
  id: string;
  businessName: string;
  businessRegNo: string;
  contactPhone: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  createdAt: string;
};

const applicationsKey = ['dispatcher', 'pending-merchant-applications'];

function usePendingApplications() {
  const { user } = useAuth();
  return useQuery({
    queryKey: applicationsKey,
    enabled: Boolean(user?.accessToken),
    retry: false,
    queryFn: async () => (await apiClient.get<MerchantApplication[]>('/api/v1/admin/merchants', {
      headers: { Authorization: `Bearer ${user?.accessToken}` },
    })).data,
  });
}

function displayDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Date unavailable' : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date);
}

function ApplicationRows({ applications }: { applications: MerchantApplication[] }) {
  if (!applications.length) return <div className="staff-empty">There are no applications waiting for review.</div>;
  return <div className="staff-application-list">{applications.map((application) => <div className="staff-application-row" key={application.id}>
    <div><strong>{application.businessName}</strong><small>Submitted {displayDate(application.createdAt)} · {application.contactPhone}</small></div>
    <span className="staff-review-button">Pending</span>
  </div>)}</div>;
}

export function UnassignedParcelsPage() {
  const { user } = useAuth();
  const applications = usePendingApplications();
  const firstName = user?.name?.trim().split(/\s+/)[0] || 'Dispatcher';

  return <main className="staff-main">
    <section className="staff-hero">
      <div><span className="staff-eyebrow">ZENTRO OPERATIONS</span><h1>Good day, {firstName}.</h1><p>Your dispatch desk is ready. Review merchant applications and keep operations moving.</p></div>
      <span className="staff-hero-status">Dispatcher workspace</span>
    </section>

    <section className="staff-section" aria-label="Operations overview">
      <div className="staff-stat-grid">
        <article className="staff-stat-card"><span>Merchant applications</span><strong>{applications.isPending ? '—' : applications.data?.length ?? '—'}</strong><small>Waiting for your review</small></article>
        <article className="staff-stat-card"><span>Dispatch queue</span><strong>Not connected</strong><small>Parcel dispatch data is not available yet</small></article>
        <article className="staff-stat-card"><span>Courier workload</span><strong>Not connected</strong><small>Courier job data is not available yet</small></article>
      </div>
    </section>

    <section className="staff-section">
      <div className="staff-section-heading"><div><h2>Merchant applications</h2><p>Review business details submitted for Zentro access.</p></div><Link className="staff-text-link" to="/dispatcher/merchant-approvals">Open review queue →</Link></div>
      <div className="staff-panel">
        {applications.isPending ? <div className="staff-empty">Loading applications…</div>
          : applications.isError ? <div className="staff-error">{friendlyApiError(applications.error, 'Could not load merchant applications. Please try again.')}</div>
            : <ApplicationRows applications={(applications.data ?? []).slice(0, 5)} />}
      </div>
    </section>

    <section className="staff-section">
      <div className="staff-section-heading"><div><h2>Quick access</h2><p>Continue with common dispatcher tasks.</p></div></div>
      <div className="staff-action-grid">
        <Link className="staff-action-card" to="/dispatcher/merchant-approvals"><strong>Review business applications <span aria-hidden="true">↗</span></strong><p>Check submitted details and record an approval decision.</p></Link>
        <Link className="staff-action-card" to="/dispatcher/couriers"><strong>Courier fleet & profiles <span aria-hidden="true">↗</span></strong><p>Manage courier eligibility, assigned delivery zones, and vehicle categories.</p></Link>
      </div>
    </section>
  </main>;
}

export function MerchantApprovalsPage() {
  const applications = usePendingApplications();
  const queryClient = useQueryClient();
  const [rejectionReasons, setRejectionReasons] = useState<Record<string, string>>({});
  const [actionError, setActionError] = useState('');
  const review = useMutation({
    mutationFn: async (input: { id: string; decision: 'APPROVED' | 'REJECTED'; rejectionReason?: string }) => {
      const { id, ...body } = input;
      return (await apiClient.patch<MerchantApplication>(`/api/v1/admin/merchants/${id}`, body)).data;
    },
    onSuccess: async () => {
      setActionError('');
      await queryClient.invalidateQueries({ queryKey: applicationsKey });
    },
    onError: (error) => setActionError(friendlyApiError(error, 'Could not save the review decision. Please try again.')),
  });

  return <main className="staff-main">
    <section className="staff-section">
      <div className="staff-section-heading"><div><span className="staff-eyebrow">DISPATCH DESK</span><h1>Merchant applications</h1><p>Review pending business profiles.</p></div><Link className="staff-text-link" to="/dispatcher">← Back to overview</Link></div>
      {actionError && <p className="staff-review-notice" role="alert">{actionError}</p>}
      {applications.isPending ? <div className="staff-panel staff-empty">Loading applications…</div>
        : applications.isError ? <div className="staff-panel staff-error">{friendlyApiError(applications.error, 'Could not load merchant applications. Please try again.')}</div>
          : applications.data?.length ? <div className="staff-review-list">{applications.data.map((application) => <article className="staff-review-card" key={application.id}>
            <div className="staff-review-top"><div><span className="staff-eyebrow">PENDING REVIEW</span><h2>{application.businessName}</h2></div><small>{displayDate(application.createdAt)}</small></div>
            <div className="staff-review-meta">
              <span>Registration number<strong>{application.businessRegNo || 'Not provided'}</strong></span>
              <span>Contact phone<strong>{application.contactPhone}</strong></span>
            </div>
            <div className="staff-review-actions">
              <Input aria-label={`Reason for rejecting ${application.businessName}`} placeholder="Rejection reason (required to reject)" value={rejectionReasons[application.id] ?? ''} onChange={(event) => setRejectionReasons((current) => ({ ...current, [application.id]: event.target.value }))} />
              <Button variant="secondary" className="staff-review-button" type="button" disabled={review.isPending} onClick={() => {
                const reason = rejectionReasons[application.id]?.trim();
                if (!reason) { setActionError('Add a rejection reason before rejecting an application.'); return; }
                setActionError('');
                review.mutate({ id: application.id, decision: 'REJECTED', rejectionReason: reason });
              }}>Reject</Button>
              <Button className="staff-review-button is-approve" type="button" disabled={review.isPending} onClick={() => { setActionError(''); review.mutate({ id: application.id, decision: 'APPROVED' }); }}>Approve</Button>
            </div>
          </article>)}</div>
            : <div className="staff-panel staff-empty">No merchant applications are waiting for review.</div>}
      <p className="staff-review-notice">Recording approval updates the application status. Merchant role access is managed separately by an administrator.</p>
    </section>
  </main>;
}

export const WorkloadPage = () => <section className="staff-main"><div className="staff-panel staff-empty">Courier workload data will appear when the dispatch service is available.</div></section>;
export const ReportsPage = () => <section className="staff-main"><div className="staff-panel staff-empty">Dispatch reports will appear when reporting data is available.</div></section>;
export { CouriersPage as UsersPage } from './CouriersPage';
export { CouriersPage } from './CouriersPage';
export const ZonesPricingPage = () => <section className="staff-main"><div className="staff-panel staff-empty">Zone and pricing tools are not connected yet.</div></section>;

