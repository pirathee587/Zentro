import { Link } from 'react-router-dom';
import { useDispatchQueue } from '../api/dispatchApi';

export function DispatchQueueCard() {
  const queue = useDispatchQueue();
  return <Link className="staff-stat-card staff-text-link" to="/dispatcher/queue">
    <span>Dispatch queue</span><strong>{queue.isPending ? 'Loading…' : queue.isError ? 'Unavailable' : queue.data.total ?? 'Total unavailable'}</strong>
    <small>{queue.isError ? 'Could not load queue · Open to retry' : queue.isFetching ? 'Refreshing queue…' : 'Open dispatch queue'}</small>
  </Link>;
}
