import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { getCourierWorkload, workloadQueryKey } from '../api/workloadApi';

export function CourierWorkloadCard() {
  const { user } = useAuth();
  const workload = useQuery({
    queryKey: workloadQueryKey({}, user?.id),
    enabled: Boolean(user?.accessToken),
    queryFn: ({ signal }) => getCourierWorkload({}, user?.accessToken, signal),
  });

  return (
    <Link className="staff-stat-card staff-text-link" to="/dispatcher/workload">
      <span>Courier workload</span>
      <strong>
        {workload.isPending
          ? 'Loading…'
          : workload.isError
          ? 'Unavailable'
          : workload.data?.total ?? 'Total unavailable'}
      </strong>
      <small>
        {workload.isError
          ? 'Could not load workload · Open to retry'
          : workload.isFetching
          ? 'Refreshing workload…'
          : 'Open courier workload'}
      </small>
    </Link>
  );
}
