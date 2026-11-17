import { useState } from 'react';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { Button, Input, Select } from '@/shared/ui';
import { useAuth } from '@/shared/auth/useAuth';
import { useCourierAssignments } from '../hooks/useCourierAssignments';
import { CourierPage, ReadState, ProofBadge } from './CourierPageParts';
import { assignedDate, assignedDay, completionLabels } from './courierPageUtils';

export function CourierHistoryPage() {
  const { user } = useAuth();
  return <HistoryRecords key={user?.id || 'anonymous'} />;
}
function HistoryRecords() {
  const { user } = useAuth();
  const query = useCourierAssignments();
  const [search, setSearch] = useState('');
  const [state, setState] = useState('ALL');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(1);
  const invalidRange = Boolean(from && to && from > to);
  const records = (query.data ?? []).filter((row) => row.proofId !== null);
  const matches = records
    .filter(
      (row) =>
        !invalidRange &&
        row.parcelId.toLowerCase().includes(search.trim().toLowerCase()) &&
        (state === 'ALL' || state === row.completionState) &&
        (!from || assignedDay(row.assignedAt) >= from) &&
        (!to || assignedDay(row.assignedAt) <= to),
    )
    .sort(
      (a, b) => b.assignedAt.localeCompare(a.assignedAt) || a.parcelId.localeCompare(b.parcelId),
    );
  const pages = Math.max(1, Math.ceil(matches.length / 10));
  const current = Math.min(page, pages);
  const visible = matches.slice((current - 1) * 10, current * 10);
  return (
    <CourierPage
      title="History"
      description="Proof records for deliveries currently assigned to you."
      action={
        user?.accessToken && (
          <Button disabled={query.isFetching} onClick={() => void query.refetch()}>
            <RefreshCw size={16} aria-hidden="true" /> Refresh
          </Button>
        )
      }
    >
      <p className="cs-notice">
        A complete delivery archive is not available yet. Reassigned or removed deliveries may not
        appear. Proof states are not failed-delivery statuses; completion dates and tracking
        references are not supplied by this list.
      </p>
      {!user?.accessToken ? (
        <p role="status">Sign in to view your delivery records.</p>
      ) : query.isPending ? (
        <ReadState loading retry={() => void query.refetch()} />
      ) : query.isError ? (
        <ReadState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <section className="cs-card" aria-labelledby="history-records">
          <h2 id="history-records">Available proof records</h2>
          <div className="cs-filter">
            <label>
              Search parcel ID
              <Input
                type="search"
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value);
                  setPage(1);
                }}
              />
            </label>
            <label>
              Proof state
              <Select
                value={state}
                onChange={(e) => {
                  setState(e.target.value);
                  setPage(1);
                }}
              >
                <option value="ALL">All proof states</option>
                {Object.entries(completionLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </Select>
            </label>
            <label>
              Assigned from
              <Input
                type="date"
                value={from}
                onChange={(e) => {
                  setFrom(e.target.value);
                  setPage(1);
                }}
              />
            </label>
            <label>
              Assigned through
              <Input
                type="date"
                value={to}
                onChange={(e) => {
                  setTo(e.target.value);
                  setPage(1);
                }}
              />
            </label>
          </div>
          <p className="cs-muted">
            Dates use Asia/Colombo and refer to assignment, not completion.
          </p>
          <Button
            onClick={() => {
              setSearch('');
              setState('ALL');
              setFrom('');
              setTo('');
              setPage(1);
            }}
          >
            Clear filters
          </Button>
          {invalidRange && <p role="alert">The start date must be on or before the end date.</p>}
          <p role="status">{matches.length} matching proof records</p>
          {visible.length ? (
            <ul className="cs-list">
              {visible.map((row) => (
                <li key={row.parcelId}>
                  <div>
                    <h3>Parcel {row.parcelId}</h3>
                    <p className="cs-muted">Assigned {assignedDate(row.assignedAt)}</p>
                    <ProofBadge state={row.completionState} />
                  </div>
                  <Link to={`/courier/deliveries/${row.parcelId}`}>View delivery and proof</Link>
                </li>
              ))}
            </ul>
          ) : (
            <div className="cs-state">
              <h3>
                {records.length ? 'No records match your filters' : 'No proof records available'}
              </h3>
              <p className="cs-muted">
                {records.length
                  ? 'Try another proof state, parcel ID or assignment date.'
                  : 'Proof recorded for your current assignments will appear here.'}
              </p>
            </div>
          )}
          {pages > 1 && (
            <nav className="cs-pagination" aria-label="Pages of loaded proof records">
              <Button disabled={current <= 1} onClick={() => setPage(current - 1)}>
                Previous
              </Button>
              <span aria-live="polite">
                Page {current} of {pages}
              </span>
              <Button disabled={current >= pages} onClick={() => setPage(current + 1)}>
                Next
              </Button>
            </nav>
          )}
        </section>
      )}
    </CourierPage>
  );
}
