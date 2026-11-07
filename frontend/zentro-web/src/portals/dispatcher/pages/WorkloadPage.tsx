import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { getCourierWorkload, workloadQueryKey, type CourierWorkload } from '../api/workloadApi';

type AvailabilityFilter = 'ALL' | 'AVAILABLE' | 'UNAVAILABLE';

function WorkloadCard({ courier }: { courier: CourierWorkload }) {
  const available = courier.availability === 'AVAILABLE';
  return (
    <article className="staff-courier-card" data-testid="workload-card">
      <div className="staff-courier-header">
        <div>
          <h3>{courier.name || 'Unnamed Courier'}</h3>
          <p>Courier profile {courier.courierId}</p>
        </div>
        <div className="staff-courier-tags">
          <span className={`courier-badge ${available ? 'is-active' : 'is-inactive'}`} role="status">
            {available ? 'Available' : 'Unavailable'}
          </span>
          <span className="courier-badge is-status">Overload rule unavailable</span>
        </div>
      </div>
      <div className="staff-courier-grid">
        <div className="staff-courier-cell">
          <span>Assigned deliveries</span>
          <strong>{courier.assignmentCount}</strong>
        </div>
        <div className="staff-courier-cell">
          <span>Account</span>
          <strong>{courier.accountStatus}</strong>
        </div>
        <div className="staff-courier-cell">
          <span>Eligibility</span>
          <strong>{courier.active ? 'Active' : 'Inactive'}</strong>
        </div>
      </div>
    </article>
  );
}

export function WorkloadPage() {
  const { user } = useAuth();
  const [search, setSearch] = useState('');
  const [availability, setAvailability] = useState<AvailabilityFilter>('ALL');
  const params = {
    search: search.trim() || undefined,
    availability: availability === 'ALL' ? undefined : availability.toLowerCase() as 'available' | 'unavailable',
  };
  const workload = useQuery({
    queryKey: workloadQueryKey(params),
    enabled: Boolean(user?.accessToken),
    queryFn: ({ signal }) => getCourierWorkload(params, user?.accessToken, signal),
  });
  const items = workload.data?.items ?? [];

  return (
    <main className="staff-main" aria-labelledby="workload-title">
      <section className="staff-hero">
        <div>
          <span className="staff-eyebrow">FLEET &amp; DISPATCH OPERATIONS</span>
          <h1 id="workload-title">Courier workload</h1>
          <p>Review assignment counts and current dispatch availability from live service data.</p>
        </div>
        <span className="staff-hero-status">
          {workload.isPending ? 'Loading workload…' : `${workload.data?.total ?? 0} couriers`}
        </span>
      </section>

      <section className="staff-section" aria-label="Workload filters">
        <div className="staff-filter-bar">
          <div className="staff-filter-group">
            <input
              type="search"
              className="staff-filter-input"
              placeholder="Search couriers…"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              aria-label="Search workload"
            />
            <select
              className="staff-filter-select"
              value={availability}
              onChange={(event) => setAvailability(event.target.value as AvailabilityFilter)}
              aria-label="Filter by availability"
            >
              <option value="ALL">All availability</option>
              <option value="AVAILABLE">Available</option>
              <option value="UNAVAILABLE">Unavailable</option>
            </select>
          </div>
          <button
            type="button"
            className="staff-review-button"
            onClick={() => void workload.refetch()}
            disabled={workload.isFetching}
          >
            {workload.isFetching ? 'Refreshing…' : 'Refresh workload'}
          </button>
        </div>
      </section>

      <section className="staff-section" aria-label="Courier workload results">
        {workload.isPending ? (
          <div className="staff-panel staff-empty" role="status">Loading courier workload…</div>
        ) : workload.isError ? (
          <div className="staff-panel staff-error" role="alert">
            {friendlyApiError(workload.error, 'Could not load courier workload. Please try again.')}
          </div>
        ) : items.length === 0 ? (
          <div className="staff-panel staff-empty">No couriers found matching your filters.</div>
        ) : (
          <div className="staff-courier-list">
            {items.map((courier) => <WorkloadCard key={courier.courierId} courier={courier} />)}
          </div>
        )}
      </section>
    </main>
  );
}
