import { useCallback, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { friendlyApiError } from '@/shared/api/apiClient';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, Input } from '@/shared/ui';
import { defaultQueueFilters, dispatchQueueKey, useDispatchQueue, type DispatchQueue, type QueueFilters, type QueueParcel } from '../api/dispatchApi';
import { AssignmentDialog } from './AssignmentDialog';

export function DispatchQueuePage() {
  const { user } = useAuth();
  const client = useQueryClient();
  const [draft, setDraft] = useState(defaultQueueFilters);
  const [filters, setFilters] = useState(defaultQueueFilters);
  const [selected, setSelected] = useState<QueueParcel | null>(null);
  const [notice, setNotice] = useState('');
  const heading = useRef<HTMLHeadingElement>(null);
  const queue = useDispatchQueue(filters);
  const filtered = Boolean(filters.search || filters.from || filters.to);
  const invalidDates = Boolean(draft.from && draft.to && draft.from > draft.to);
  const refresh = useCallback(async () => {
    await client.invalidateQueries({ queryKey: dispatchQueueKey });
  }, [client]);
  const stale = useCallback((message: string) => {
    setSelected(null); setNotice(message); void refresh(); heading.current?.focus();
  }, [refresh]);
  const completed = () => {
    const parcelId = selected?.parcelId;
    client.setQueriesData<DispatchQueue>({ queryKey: dispatchQueueKey }, data => data ? {
      ...data, items: data.items.filter(item => item.parcelId !== parcelId), total: null, matchingTotal: null, complete: false,
    } : data);
    setSelected(null); setNotice('Delivery assigned successfully.'); heading.current?.focus(); void refresh();
  };
  const change = (field: keyof QueueFilters, value: string) => setDraft(current => ({ ...current, [field]: value }));
  return <main className="staff-main">
    <section className="staff-section">
      <div className="staff-section-heading">
        <div><span className="staff-eyebrow">DISPATCH DESK</span><h1 tabIndex={-1} ref={heading}>Dispatch Queue</h1><p>Review deliveries waiting for courier assignment.</p></div>
        <Button variant="secondary" disabled={queue.isFetching || !user?.accessToken} onClick={() => void refresh()}>{queue.isFetching ? 'Refreshing…' : 'Refresh'}</Button>
      </div>
      {notice && <p className="staff-review-notice" role="status">{notice}</p>}
      <form className="staff-queue-filters staff-panel" onSubmit={event => { event.preventDefault(); if (!invalidDates) setFilters({ ...draft, search: draft.search.trim() }); }}>
        <label>Search<Input maxLength={200} value={draft.search} onChange={event => change('search', event.target.value)} placeholder="Tracking reference, pickup or drop-off" /></label>
        <label>Created from (UTC)<Input type="date" value={draft.from} onChange={event => change('from', event.target.value)} /></label>
        <label>Created to (UTC)<Input type="date" value={draft.to} onChange={event => change('to', event.target.value)} /></label>
        <label>Sort<select value={draft.sort} onChange={event => change('sort', event.target.value)}><option value="newest">Newest first</option><option value="oldest">Oldest first</option></select></label>
        <Button type="submit" disabled={invalidDates}>Apply filters</Button>
        <Button variant="secondary" type="button" onClick={() => { setDraft(defaultQueueFilters); setFilters(defaultQueueFilters); }}>Clear filters</Button>
        {invalidDates && <p role="alert">Created from must be on or before Created to.</p>}
      </form>
      <div className="staff-panel staff-queue-results" aria-busy={queue.isFetching}>
        {queue.isPending ? <p className="staff-empty" role="status">Loading dispatch queue…</p>
          : queue.isError ? <div className="staff-error" role="alert"><h2>Dispatch queue unavailable</h2><p>{friendlyApiError(queue.error, 'We cannot load the dispatch queue right now. The queue total is unavailable. Please try again later.')}</p><Button variant="secondary" onClick={() => void refresh()}>Retry</Button></div>
            : <>
              <p role="status">{queue.data.total === null ? 'Queue total unavailable; the source is incomplete.' : `${queue.data.total} unassigned deliveries`}{queue.isFetching ? ' · Refreshing…' : ''}</p>
              {!queue.data.items.length ? <p className="staff-empty">{!queue.data.complete ? 'No deliveries are available in the current source snapshot.' : filtered ? 'No deliveries match your filters.' : 'The dispatch queue is empty.'}</p>
                : <div className="staff-queue-scroll"><table className="staff-queue-table"><caption className="sr-only">Eligible unassigned deliveries</caption><thead><tr><th>Tracking reference</th><th>Pickup</th><th>Drop-off</th><th>Created</th><th>Action</th></tr></thead><tbody>
                  {queue.data.items.map(parcel => <tr key={parcel.parcelId}><td>{parcel.trackingReference || 'Reference unavailable'}</td><td>{parcel.pickupSummary || 'Pickup unavailable'}</td><td>{parcel.dropoffSummary || 'Drop-off unavailable'}</td><td>{new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(parcel.createdAt))}</td><td><Button disabled={queue.isFetching} aria-label={`Assign ${parcel.trackingReference || parcel.parcelId}`} onClick={() => { setNotice(''); setSelected(parcel); }}>Assign</Button></td></tr>)}
                </tbody></table></div>}
            </>}
      </div>
      <Link className="staff-text-link" to="/dispatcher">Back to overview</Link>
    </section>
    {selected && <AssignmentDialog key={selected.parcelId} parcel={selected} close={() => setSelected(null)} completed={completed} stale={stale} />}
  </main>;
}
