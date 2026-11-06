import { Button } from '@/shared/ui';
import { toPricingError } from '../api/pricingApi';
import { useZones } from './usePricing';

/** Read-only list of delivery zones from the live API. Zone editing has no backend API yet. */
export function ZoneList() {
  const zones = useZones();

  return (
    <div className="staff-panel staff-pricing-zones">
      {zones.isPending ? (
        <div className="staff-empty" role="status">Loading delivery zones…</div>
      ) : zones.isError ? (
        <div className="staff-error" role="alert">
          <p>{toPricingError(zones.error, 'Could not load delivery zones. Please try again.').message}</p>
          <Button variant="secondary" type="button" onClick={() => void zones.refetch()}>Try again</Button>
        </div>
      ) : zones.data.length === 0 ? (
        <div className="staff-empty">No delivery zones are configured.</div>
      ) : (
        <div className="staff-queue-scroll">
          <table className="staff-pricing-table">
            <caption className="sr-only">Delivery zones</caption>
            <thead>
              <tr>
                <th scope="col">Code</th>
                <th scope="col">Name</th>
                <th scope="col">Description</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {zones.data.map((zone) => (
                <tr key={zone.id || zone.code}>
                  <td><code>{zone.code}</code></td>
                  <td>{zone.name}</td>
                  <td>{zone.description ?? <span className="staff-muted">—</span>}</td>
                  <td>
                    <span className={`staff-badge ${zone.active ? 'staff-badge-active' : 'staff-badge-disabled'}`}>
                      {zone.active ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="staff-field-hint">
        Zones are read-only here. Adding or editing zones needs a backend API that does not exist yet.
      </p>
    </div>
  );
}
