import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, CalendarDays, CheckCircle2, MapPin, Package, RefreshCw, Truck } from 'lucide-react';
import { Button } from '@/shared/ui';
import { useAuth } from '@/shared/auth/useAuth';
import { principalCacheScope } from '@/shared/api/queryKeys';
import { CourierDashboardError, dashboardErrorMessage, type DashboardDelivery, type DashboardStatus } from '../api/courierDashboardApi';
import { useCourierTodayDashboard } from '../hooks/useCourierTodayDashboard';
import './TodayDeliveriesPage.css';

const statusLabels: Record<DashboardStatus, string> = {
  CREATED: 'Created', PICKED_UP: 'Picked up', IN_TRANSIT: 'In transit',
  OUT_FOR_DELIVERY: 'Out for delivery', DELIVERED: 'Delivered', FAILED: 'Failed',
};
const stages: DashboardStatus[] = ['CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'];
function StatusBadge({ delivery }: { delivery: DashboardDelivery }) {
  return <span className={`ct-status ct-status-${delivery.status?.toLowerCase() ?? 'unknown'}`}>
    {delivery.status ? statusLabels[delivery.status] : 'Status unavailable'}
  </span>;
}
function ProofState({ delivery }: { delivery: DashboardDelivery }) {
  const messages = { PENDING: 'Proof recorded; completion pending confirmation.', COMPLETED: 'Proof completion confirmed.',
    REJECTED: 'Completion was rejected. Contact dispatch.', BLOCKED: 'Completion needs attention. Contact dispatch.' };
  return delivery.completionState ? <p className="ct-note">{messages[delivery.completionState]}</p> : null;
}
function CurrentDelivery({ delivery }: { delivery: DashboardDelivery }) {
  const stage = delivery.status ? stages.indexOf(delivery.status) : -1;
  return <section className="ct-card ct-current" aria-labelledby="current-delivery-heading">
    <div className="ct-card-heading"><div><p className="ct-eyebrow">Your next stop</p><h2 id="current-delivery-heading">Current delivery</h2></div>
      <span className="ct-icon"><Truck aria-hidden="true" size={24} /></span></div>
    <h3 className="ct-reference">{delivery.trackingRef || `Parcel ${delivery.parcelId}`}</h3>
    <StatusBadge delivery={delivery} />
    {!delivery.projectionAvailable ? <p className="ct-note">Tracking details are not available yet. Open the delivery to check its details.</p>
      : delivery.recipient && <div className="ct-destination"><MapPin aria-hidden="true" size={20} />
        <div><p className="ct-recipient">{delivery.recipient.name}</p><p>{delivery.recipient.address}</p>
          {delivery.recipient.city && <p>{delivery.recipient.city}</p>}</div></div>}
    {(delivery.size || delivery.weightKg !== null) && <p className="ct-note">
      {[delivery.size, delivery.weightKg !== null ? `${delivery.weightKg} kg` : null].filter(Boolean).join(' · ')}
    </p>}
    {stage >= 0 && <div className="ct-stage" role="progressbar" aria-label="Delivery stage" aria-valuemin={1}
      aria-valuemax={stages.length} aria-valuenow={stage + 1} aria-valuetext={statusLabels[delivery.status!]}>
      <div className="ct-stage-bars" aria-hidden="true">{stages.map((value, index) => <span key={value} className={index <= stage ? 'is-reached' : ''} />)}</div>
      <p>Current stage: {statusLabels[delivery.status!]}</p></div>}
    {delivery.failureReason && <p className="ct-note">Delivery issue: {delivery.failureReason}</p>}
    <ProofState delivery={delivery} />
    <Link className="sd-btn-yellow ct-primary" to={`/courier/deliveries/${delivery.parcelId}`}>Open delivery <ArrowRight aria-hidden="true" size={18} /></Link>
    <p className="ct-note">View delivery details and submit proof through the delivery workflow.</p>
  </section>;
}

export function TodayDeliveriesPage() {
  const { user } = useAuth();
  if (!user?.accessToken) return <main className="sd-page-container courier-main courier-today">
    <p role="status">Sign in to view your deliveries.</p>
  </main>;
  return <CourierToday key={principalCacheScope(user?.id)} />;
}
function CourierToday() {
  const { user, login } = useAuth();
  const [page, setPage] = useState(1);
  const query = useCourierTodayDashboard(page);
  const result = user?.accessToken && !query.isError ? query.data : undefined;
  const dashboard = result?.dashboard;
  const displayName = user?.name?.trim();
  const greeting = displayName ? `Welcome back, ${displayName}` : 'Welcome back';
  const date = dashboard ? new Date(`${dashboard.businessDate}T12:00:00+05:30`) : new Date();
  const dateLabel = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Asia/Colombo' }).format(date);
  const canRetry = !(query.error instanceof CourierDashboardError && ['unauthorized', 'forbidden', 'profile'].includes(query.error.kind));
  return <main className="sd-page-container courier-main courier-today">
    <header className="ct-header"><div><p className="ct-eyebrow">Courier / Today</p><h1>{greeting}</h1>
      <p className="ct-subtitle"><CalendarDays aria-hidden="true" size={16} /><time dateTime={dashboard?.businessDate}>{dateLabel}</time></p></div>
      <Button variant="secondary" className="ct-refresh" disabled={query.isFetching || !user?.accessToken || !canRetry}
        onClick={() => void query.refetch()}><RefreshCw aria-hidden="true" size={16} />Refresh</Button></header>
    {query.isPending && user?.accessToken && <section className="ct-card ct-state" role="status"><Package aria-hidden="true" size={30} /><p>Loading deliveries…</p></section>}
    {!user?.accessToken && <p role="status">Sign in to view your deliveries.</p>}
    {query.isError && <div className="ct-alert" role="alert"><p>{dashboardErrorMessage(query.error)}</p>
      {query.error instanceof CourierDashboardError && query.error.kind === 'unauthorized'
        ? <Button onClick={() => void login('/courier', undefined, 'staff')}>Sign in again</Button>
        : canRetry && <Button variant="secondary" onClick={() => void query.refetch()}>Retry</Button>}</div>}
    {dashboard && <>
      <section className="ct-stats" aria-label="Delivery statistics">
        {([{ label: 'Assigned', value: dashboard.counts.assigned, Icon: Package },
          { label: 'Delivered', value: dashboard.counts.delivered, Icon: CheckCircle2 },
          { label: 'Remaining', value: dashboard.counts.remaining, Icon: Truck }]).map(({ label, value, Icon }) =>
          <div key={label} className="ct-card ct-stat"><div><p>{label}</p><strong>{value}</strong></div><Icon aria-hidden="true" size={22} /></div>)}
      </section>
      <p className="ct-note ct-population">Includes unfinished carryover. Failed: {dashboard.counts.failed}.</p>
      {result.partial && <div className="ct-warning" role="status"><p>Some delivery details could not be loaded. Available information is shown.</p>
        <Button variant="secondary" onClick={() => void query.refetch()} disabled={query.isFetching}>Retry details</Button></div>}
      {dashboard.counts.assigned === 0 ? <section className="ct-card ct-state"><span className="ct-icon"><CheckCircle2 aria-hidden="true" size={30} /></span>
        <h2>You’re all caught up</h2><p>No deliveries are assigned to you yet.</p></section>
        : <div className="ct-content">
          {dashboard.currentDelivery ? <CurrentDelivery delivery={dashboard.currentDelivery} />
            : <section className="ct-card ct-state"><Truck aria-hidden="true" size={30} /><h2>No current delivery</h2>
              <p>Your assigned deliveries are listed here. Tracking details may still be updating.</p></section>}
          <section className="ct-card ct-queue" aria-labelledby="today-deliveries-heading" aria-busy={query.isFetching}>
            <div className="ct-card-heading"><div><p className="ct-eyebrow">Your assignments</p><h2 id="today-deliveries-heading">Today’s deliveries</h2></div>
              <span className="ct-count">{dashboard.pageInfo.totalElements}</span></div>
            {dashboard.deliveries.length ? <ul className="ct-list">{dashboard.deliveries.map(delivery => <li key={delivery.parcelId}>
              <div className="ct-list-main"><h3>{delivery.trackingRef || delivery.parcelId}</h3>
                {delivery.projectionAvailable && delivery.recipient && <p>{[delivery.recipient.name, delivery.recipient.city].filter(Boolean).join(' · ')}</p>}
                {!delivery.projectionAvailable && <p>Tracking details pending</p>}
                <StatusBadge delivery={delivery} /><ProofState delivery={delivery} /></div>
              <Link className="ct-link" to={`/courier/deliveries/${delivery.parcelId}`}>View delivery <ArrowRight aria-hidden="true" size={16} /></Link>
            </li>)}</ul> : <p className="ct-note">No deliveries on this page. Return to an earlier page.</p>}
            <nav className="ct-pagination" aria-label="Delivery pages"><Button variant="secondary" disabled={!dashboard.pageInfo.hasPrevious || query.isFetching}
              onClick={() => setPage(dashboard.pageInfo.page - 1)}>Previous</Button>
              <span aria-live="polite">Page {dashboard.pageInfo.page} of {Math.max(1, dashboard.pageInfo.totalPages)}</span>
              <Button variant="secondary" disabled={!dashboard.pageInfo.hasNext || query.isFetching}
                onClick={() => setPage(dashboard.pageInfo.page + 1)}>Next</Button></nav>
          </section>
        </div>}
    </>}
  </main>;
}
