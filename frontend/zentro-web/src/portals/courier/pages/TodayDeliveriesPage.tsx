import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { Button } from '@/shared/ui';
import { deliveryError, listDeliveries } from '../api/deliveryApi';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';

export function TodayDeliveriesPage() {
  const { user } = useAuth();
  const deliveries = useQuery({ queryKey: queryKeys.courier.deliveries(principalCacheScope(user?.id)),
    queryFn: ({ signal }) => listDeliveries(user!.accessToken, signal), enabled: Boolean(user?.accessToken), gcTime: 0 });
  const firstName = user?.name?.trim().split(/\s+/)[0] || 'Courier';
  return <main className="courier-main">
    <section className="staff-hero"><div><span className="staff-eyebrow">YOUR COURIER SPACE</span>
      <h1>Welcome, {firstName}.</h1><p>Review your assigned deliveries and record each final handoff.</p></div></section>
    <section className="courier-section" aria-labelledby="deliveries-title">
      <h2 id="deliveries-title">My assigned deliveries</h2>
      {deliveries.isPending && <p role="status">Loading deliveries…</p>}
      {deliveries.isError && <div className="courier-alert is-error" role="alert"><span>{deliveryError(deliveries.error)}</span>
        <Button onClick={() => void deliveries.refetch()}>Retry</Button></div>}
      {deliveries.data?.length === 0 && <div className="courier-assignment"><p>No deliveries are assigned to you yet.</p></div>}
      <ul className="courier-delivery-list">{!deliveries.isError && deliveries.data?.map(delivery => <li className="courier-card" key={delivery.parcelId}>
        <h3>Delivery <span className="courier-delivery-id">{delivery.parcelId}</span></h3>
        <p>Assigned {new Date(delivery.assignedAt).toLocaleString()}</p>
        <p>{delivery.completionState ? `Proof: ${delivery.completionState}` : 'Proof not submitted'}</p>
        <Link className="staff-text-link" to={`/courier/deliveries/${delivery.parcelId}`}>View delivery</Link>
      </li>)}</ul>
    </section>
  </main>;
}
