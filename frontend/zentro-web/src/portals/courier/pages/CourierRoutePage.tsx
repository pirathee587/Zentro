import { Link } from 'react-router-dom';
import { MapPin, RefreshCw } from 'lucide-react';
import { Button } from '@/shared/ui';
import { useAuth } from '@/shared/auth/useAuth';
import { useCourierTodayDashboard } from '../hooks/useCourierTodayDashboard';
import { CourierPage, ReadState, ProofBadge } from './CourierPageParts';
import { assignedDate } from './courierPageUtils';

export function CourierRoutePage() {
  const { user } = useAuth();
  const query = useCourierTodayDashboard(1, 50);
  const stops = [...(query.data?.dashboard.deliveries ?? [])].sort(
    (a, b) => a.assignedAt.localeCompare(b.assignedAt) || a.parcelId.localeCompare(b.parcelId),
  );
  const confirmed = stops.filter((s) => s.completionState === 'COMPLETED').length;
  return (
    <CourierPage
      title="Route"
      description="Your current assignments, ordered by assignment time. This is not an optimized route."
      action={
        user?.accessToken && (
          <Button onClick={() => void query.refetch()} disabled={query.isFetching}>
            <RefreshCw size={16} aria-hidden="true" /> Refresh
          </Button>
        )
      }
    >
      {!user?.accessToken ? (
        <p role="status">Sign in to view your route.</p>
      ) : query.isPending ? (
        <ReadState loading retry={() => void query.refetch()} />
      ) : query.isError ? (
        <ReadState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <>
          <section className="cs-card" aria-labelledby="route-progress">
            <h2 id="route-progress">Assignment progress</h2>
            <p>
              {stops.length} assignments / {confirmed} completions confirmed
            </p>
            <progress
              className="cs-progress"
              aria-label="Confirmed completions"
              value={confirmed}
              max={Math.max(1, stops.length)}
            />
            <p className="cs-muted">
              Proof confirmation is shown separately from live parcel status.
            </p>
          </section>
          <section className="cs-card" aria-labelledby="route-stops">
            <h2 id="route-stops">Assigned stops</h2>
            {!stops.length ? (
              <div className="cs-state">
                <MapPin aria-hidden="true" />
                <p>No assigned deliveries</p>
                <p className="cs-muted">
                  New assignments will appear here when dispatch assigns them to you.
                </p>
              </div>
            ) : (
              <ol className="cs-list">
                {stops.map((stop, index) => (
                  <li key={stop.parcelId}>
                    <div>
                      <h3>{index + 1}. {stop.trackingRef ? <Link to={`/track/${encodeURIComponent(stop.trackingRef)}`}>{stop.trackingRef}</Link> : 'Tracking reference unavailable'}</h3>
                      <p className="cs-muted">Assigned {assignedDate(stop.assignedAt)}</p>
                      <ProofBadge state={stop.completionState} />
                      <p className="cs-muted">{stop.status ? `Shipment status: ${stop.status.replaceAll('_', ' ')}` : 'Shipment status is unavailable.'}</p>
                      {stop.recipient && <p className="cs-muted">{[stop.recipient.address, stop.recipient.city].filter(Boolean).join(' · ')}</p>}
                    </div>
                    <div className="cs-actions"><Link to={`/courier/deliveries/${stop.parcelId}`}>View delivery</Link></div>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </>
      )}
    </CourierPage>
  );
}
