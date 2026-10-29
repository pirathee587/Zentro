import { Link } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { useMerchantApplications } from './MerchantApprovalsPage';

type ApplicationPreviewProps = {
  businessName: string;
  createdAt: string;
  contactPhone: string;
};

function displayDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? 'Date unavailable'
    : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date);
}

function ApplicationRow({ businessName, createdAt, contactPhone }: ApplicationPreviewProps) {
  return (
    <div className="staff-application-row">
      <div>
        <strong>{businessName}</strong>
        <small>
          Submitted {displayDate(createdAt)} · {contactPhone}
        </small>
      </div>
      <span className="staff-review-button">Pending</span>
    </div>
  );
}

function ApplicationRows({ applications }: { applications: ApplicationPreviewProps[] }) {
  if (!applications.length)
    return <div className="staff-empty">There are no applications waiting for review.</div>;
  return (
    <div className="staff-application-list">
      {applications.map((a, i) => (
        <ApplicationRow key={i} {...a} />
      ))}
    </div>
  );
}

export function UnassignedParcelsPage() {
  const { user } = useAuth();
  const applications = useMerchantApplications();
  const firstName = user?.name?.trim().split(/\s+/)[0] || 'Dispatcher';
  const pendingCount = applications.data?.filter((a) => a.status === 'PENDING').length;

  return (
    <main className="staff-main">
      <section className="staff-hero">
        <div>
          <span className="staff-eyebrow">SWIFTDROP OPERATIONS</span>
          <h1>Good day, {firstName}.</h1>
          <p>Your dispatch desk is ready. Review merchant applications and keep operations moving.</p>
        </div>
        <span className="staff-hero-status">Dispatcher workspace</span>
      </section>

      <section className="staff-section" aria-label="Operations overview">
        <div className="staff-stat-grid">
          <article className="staff-stat-card">
            <span>Pending applications</span>
            <strong>{applications.isPending ? '—' : pendingCount ?? '—'}</strong>
            <small>Waiting for your review</small>
          </article>
          <article className="staff-stat-card">
            <span>Dispatch queue</span>
            <strong>Not connected</strong>
            <small>Parcel dispatch data is not available yet</small>
          </article>
          <article className="staff-stat-card">
            <span>Courier workload</span>
            <strong>Not connected</strong>
            <small>Courier job data is not available yet</small>
          </article>
        </div>
      </section>

      <section className="staff-section">
        <div className="staff-section-heading">
          <div>
            <h2>Merchant applications</h2>
            <p>Review business details submitted for SwiftDrop access.</p>
          </div>
          <Link className="staff-text-link" to="/dispatcher/merchant-approvals">
            Open review queue →
          </Link>
        </div>
        <div className="staff-panel">
          {applications.isPending ? (
            <div className="staff-empty">Loading applications…</div>
          ) : applications.isError ? (
            <div className="staff-error">
              {friendlyApiError(
                applications.error,
                'Could not load merchant applications. Please try again.',
              )}
            </div>
          ) : (
            <ApplicationRows
              applications={(applications.data ?? [])
                .filter((a) => a.status === 'PENDING')
                .slice(0, 5)}
            />
          )}
        </div>
      </section>

      <section className="staff-section">
        <div className="staff-section-heading">
          <div>
            <h2>Quick access</h2>
            <p>Continue with common dispatcher tasks.</p>
          </div>
        </div>
        <div className="staff-action-grid">
          <Link className="staff-action-card" to="/dispatcher/merchant-approvals">
            <strong>
              Review business applications <span aria-hidden="true">↗</span>
            </strong>
            <p>Check submitted details and record an approval decision.</p>
          </Link>
          <Link className="staff-action-card" to="/dispatcher/users">
            <strong>
              Manage users <span aria-hidden="true">↗</span>
            </strong>
            <p>Search registered accounts, view roles and statuses, update account details.</p>
          </Link>
          <article className="staff-action-card">
            <strong>Dispatch and courier tools</strong>
            <p>These work queues will appear when the dispatch service is available.</p>
          </article>
        </div>
      </section>
    </main>
  );
}

export { MerchantApprovalsPage } from './MerchantApprovalsPage';
export { UsersPage } from './UsersPage';

export const WorkloadPage = () => (
  <section className="staff-main">
    <div className="staff-panel staff-empty">
      Courier workload data will appear when the dispatch service is available.
    </div>
  </section>
);

export const ReportsPage = () => (
  <section className="staff-main">
    <div className="staff-panel staff-empty">
      Dispatch reports will appear when reporting data is available.
    </div>
  </section>
);

export const ZonesPricingPage = () => (
  <section className="staff-main">
    <div className="staff-panel staff-empty">Zone and pricing tools are not connected yet.</div>
  </section>
);
