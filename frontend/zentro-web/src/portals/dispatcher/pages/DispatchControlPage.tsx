import { useCallback, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Package, RefreshCw, Truck, Users } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { invalidateAfterDispatchAssignment } from '@/shared/api/queryInvalidation';
import { useDispatchQueue, type QueueParcel } from '../api/dispatchApi';
import { useCourierWorkload } from '../api/workloadApi';
import { AssignmentDialog } from './AssignmentDialog';
import { useMerchantApplications } from './MerchantApprovalsPage';
import './DispatchControlPage.css';

function Metric({
  label,
  value,
  detail,
  icon: Icon,
  pending,
  error,
}: {
  label: string;
  value?: number;
  detail: string;
  icon: LucideIcon;
  pending: boolean;
  error: boolean;
}) {
  return (
    <article className="dispatch-metric" aria-label={label} aria-busy={pending}>
      <div>
        <span>{label}</span>
        <span className="dispatch-metric-icon">
          <Icon size={22} aria-hidden="true" />
        </span>
      </div>
      <strong className={pending || error || value === undefined ? 'dispatch-unavailable' : ''}>
        {pending
          ? 'Loading…'
          : error || value === undefined
            ? 'Unavailable'
            : value.toLocaleString()}
      </strong>
      <p>
        <span className={`dispatch-dot ${pending || error || value === undefined ? 'is-muted' : ''}`} aria-hidden="true" />
        {error ? 'Could not load data' : pending ? 'Fetching service data' : detail}
      </p>
    </article>
  );
}
function State({
  loading,
  error,
  empty,
  retry,
}: {
  loading?: boolean;
  error?: boolean;
  empty: string;
  retry: () => void;
}) {
  return (
    <div className="dispatch-state" role={error ? 'alert' : 'status'}>
      <Package size={25} aria-hidden="true" />
      <strong>{loading ? 'Loading data…' : error ? 'Data unavailable' : empty}</strong>
      {error && (
        <>
          <p>This section could not be loaded.</p>
          <button className="sd-btn-pill-light" onClick={retry}>
            Retry
          </button>
        </>
      )}
    </div>
  );
}
export function DispatchControlPage() {
  const queue = useDispatchQueue();
  const workload = useCourierWorkload();
  const merchants = useMerchantApplications();
  const client = useQueryClient();
  const [selected, setSelected] = useState<QueueParcel | null>(null);
  const [notice, setNotice] = useState('');
  const heading = useRef<HTMLHeadingElement>(null);
  const couriers = workload.data?.items ?? [];
  const complete = workload.data && workload.data.total === couriers.length;
  const assignments = complete
    ? couriers.reduce((sum, courier) => sum + courier.assignmentCount, 0)
    : undefined;
  const available = complete
    ? couriers.filter(
        (c) => c.active && c.accountStatus === 'ACTIVE' && c.availability === 'AVAILABLE',
      ).length
    : undefined;
  const applications = merchants.data?.filter((m) => m.status === 'PENDING') ?? [];
  const fetching = queue.isFetching || workload.isFetching || merchants.isFetching;
  const refresh = () => {
    void queue.refetch();
    void workload.refetch();
    void merchants.refetch();
  };
  const finish = useCallback(
    (message: string) => {
      setSelected(null);
      setNotice(message);
      heading.current?.focus();
      void invalidateAfterDispatchAssignment(client);
    },
    [client],
  );
  return (
    <main className="sd-page-container dispatch-dashboard" aria-labelledby="dashboard-title">
      <header className="dispatch-heading">
        <div>
          <span className="dispatch-eyebrow">SWIFTDROP OPERATIONS</span>
          <h1 id="dashboard-title" tabIndex={-1} ref={heading}>
            Dispatch control
          </h1>
          <p>Your daily overview of parcels, assignments and courier availability.</p>
        </div>
        <button
          className="sd-btn-pill-light dispatch-refresh"
          disabled={fetching}
          onClick={refresh}
        >
          <RefreshCw size={15} aria-hidden="true" />
          {fetching ? 'Refreshing…' : 'Refresh overview'}
        </button>
      </header>
      {notice && (
        <p className="dispatch-notice" role="status">
          {notice}
        </p>
      )}
      <section className="dispatch-metrics" aria-label="Operations overview">
        <Metric
          label="Unassigned parcels"
          icon={Package}
          value={queue.data?.total ?? undefined}
          pending={queue.isPending}
          error={queue.isError}
          detail={
            queue.isFetching
              ? 'Refreshing…'
              : queue.data?.complete
                ? 'Waiting for courier assignment'
                : 'Total unavailable · incomplete source'
          }
        />
        <Metric
          label="Recorded assignments"
          icon={Truck}
          value={assignments}
          pending={workload.isPending}
          error={workload.isError}
          detail={
            workload.isFetching ? 'Refreshing…' : 'All assignments reported by the workload service'
          }
        />
        <Metric
          label="Available couriers"
          icon={Users}
          value={available}
          pending={workload.isPending}
          error={workload.isError}
          detail={workload.isFetching ? 'Refreshing…' : 'Active accounts marked available'}
        />
      </section>
      <div className="dispatch-grid">
        <section
          className="sd-card dispatch-panel dispatch-parcels"
          aria-labelledby="queue-title"
          aria-busy={queue.isFetching}
        >
          <div className="dispatch-panel-heading">
            <div>
              <h2 id="queue-title">Unassigned parcels</h2>
              <p>Review the queue and allocate the next delivery.</p>
            </div>
            <Link className="sd-text-link" to="/dispatcher/queue">
              Full queue <ArrowRight size={14} aria-hidden="true" />
            </Link>
          </div>
          {queue.isPending || queue.isError ? (
            <State
              loading={queue.isPending}
              error={queue.isError}
              empty="Queue unavailable"
              retry={() => void queue.refetch()}
            />
          ) : (
            <>
              {!queue.data.complete && (
                <p className="dispatch-notice" role="status">
                  Incomplete source snapshot. Queue total is unavailable.
                </p>
              )}
              {!queue.data.items.length ? (
                <State
                  empty={
                    queue.data.complete
                      ? 'All clear — no parcels waiting.'
                      : 'No parcels in the current snapshot.'
                  }
                  retry={() => void queue.refetch()}
                />
              ) : (
                <>
                  <div
                    className="dispatch-table-scroll"
                    role="region"
                    aria-label="Unassigned parcels table"
                    tabIndex={0}
                  >
                    <table className="dispatch-table">
                      <caption className="sr-only">Unassigned parcels</caption>
                      <thead>
                        <tr>
                          <th scope="col">Parcel ID</th>
                          <th scope="col">Route</th>
                          <th scope="col">Size / weight</th>
                          <th scope="col">Courier</th>
                          <th scope="col">Action</th>
                        </tr>
                      </thead>
                      <tbody>
                        {queue.data.items.slice(0, 6).map((parcel) => (
                          <tr key={parcel.parcelId}>
                            <td>
                              <strong className="dispatch-reference">
                                {parcel.trackingReference || parcel.parcelId}
                              </strong>
                              <small>Awaiting assignment</small>
                            </td>
                            <td>
                              <div className="dispatch-route">
                                <span>{parcel.pickupSummary || 'Pickup unavailable'}</span>
                                <ArrowRight size={12} aria-hidden="true" />
                                <span>{parcel.dropoffSummary || 'Drop-off unavailable'}</span>
                              </div>
                            </td>
                            <td>
                              <small>Unavailable</small>
                            </td>
                            <td>
                              <button
                                className="dispatch-picker"
                                disabled={queue.isFetching}
                                aria-label={`Select courier for ${parcel.trackingReference || parcel.parcelId}`}
                                onClick={() => {
                                  setNotice('');
                                  setSelected(parcel);
                                }}
                              >
                                Select courier ↗
                              </button>
                            </td>
                            <td>
                              <button
                                className="sd-btn-yellow"
                                disabled={queue.isFetching}
                                aria-label={`Assign ${parcel.trackingReference || parcel.parcelId}`}
                                onClick={() => {
                                  setNotice('');
                                  setSelected(parcel);
                                }}
                              >
                                Assign
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <p className="dispatch-footnote">
                    Showing {Math.min(queue.data.items.length, 6)} of {queue.data.items.length}{' '}
                    parcels in this snapshot. Size and weight unavailable. Courier eligibility is
                    checked before confirmation.
                  </p>
                </>
              )}
            </>
          )}
        </section>
        <section
          className="sd-card dispatch-panel"
          aria-labelledby="workload-title"
          aria-busy={workload.isFetching}
        >
          <div className="dispatch-panel-heading">
            <div>
              <h2 id="workload-title">Courier workload</h2>
              <p>Recorded assignments by courier.</p>
            </div>
            <Link className="sd-text-link" to="/dispatcher/workload">
              View all <ArrowRight size={14} aria-hidden="true" />
            </Link>
          </div>
          {workload.isPending || workload.isError || !couriers.length ? (
            <State
              loading={workload.isPending}
              error={workload.isError}
              empty="No couriers to display."
              retry={() => void workload.refetch()}
            />
          ) : (
            <ul className="dispatch-list">
              {couriers.slice(0, 6).map((courier) => (
                <li key={courier.courierId}>
                  <span className="dispatch-avatar" aria-hidden="true">
                    <Truck size={18} />
                  </span>
                  <div>
                    <strong>{courier.name || 'Unnamed courier'}</strong>
                    <small>
                      <span
                        className={`dispatch-dot ${courier.availability !== 'AVAILABLE' ? 'is-muted' : ''}`}
                        aria-hidden="true"
                      />
                      {courier.availability === 'AVAILABLE' ? 'Available' : 'Unavailable'}
                    </small>
                  </div>
                  <span className="dispatch-count">
                    <strong>{courier.assignmentCount}</strong>
                    <small>assigned</small>
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="dispatch-footnote">
            Counts include all recorded assignments. Active-only counts and capacity are
            unavailable.
          </p>
        </section>
        <section
          className="sd-card dispatch-panel"
          aria-labelledby="applications-title"
          aria-busy={merchants.isFetching}
        >
          <div className="dispatch-panel-heading">
            <div>
              <h2 id="applications-title">Merchant applications</h2>
              <p>Businesses waiting for your review.</p>
            </div>
            <Link className="sd-text-link" to="/dispatcher/merchant-approvals">
              View all <ArrowRight size={14} aria-hidden="true" />
            </Link>
          </div>
          {merchants.isPending || merchants.isError || !applications.length ? (
            <State
              loading={merchants.isPending}
              error={merchants.isError}
              empty="No applications waiting for review."
              retry={() => void merchants.refetch()}
            />
          ) : (
            <ul className="dispatch-list">
              {applications.slice(0, 3).map((m) => (
                <li key={m.id}>
                  <div>
                    <strong>{m.businessName}</strong>
                    <small>Pending review</small>
                  </div>
                  <Link className="sd-btn-pill-light" to="/dispatcher/merchant-approvals">
                    Review
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
        <section
          className="sd-card dispatch-panel dispatch-activity"
          aria-labelledby="activity-title"
        >
          <div className="dispatch-panel-heading">
            <div>
              <h2 id="activity-title">Operations desk</h2>
              <p>Keep exceptions and daily performance in view.</p>
            </div>
            <Truck size={22} aria-hidden="true" />
          </div>
          <p className="dispatch-activity-note">
            Activity feed unavailable on this overview. Open the operational logs for recorded
            events.
          </p>
          <Link to="/dispatcher/integration-attempts">
            Notification &amp; webhook logs <ArrowRight size={16} aria-hidden="true" />
          </Link>
          <Link to="/dispatcher/reports">
            Delivery reports <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </section>
      </div>
      {selected && (
        <AssignmentDialog
          key={selected.parcelId}
          parcel={selected}
          close={() => setSelected(null)}
          completed={() => finish('Delivery assigned successfully.')}
          stale={finish}
        />
      )}
    </main>
  );
}
