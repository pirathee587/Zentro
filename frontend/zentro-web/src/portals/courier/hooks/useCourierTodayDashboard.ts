import { useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import { QUERY_RETRY_LIMIT } from '@/shared/api/queryClient';
import { useAuth } from '@/shared/auth/useAuth';
import { CourierDashboardError, dashboardAccessFailure, getCourierTodayDashboard } from '../api/courierDashboardApi';

export function useCourierTodayDashboard(page: number, size = 10) {
  const { user } = useAuth();
  const client = useQueryClient();
  const scope = principalCacheScope(user?.id);
  const prefix = queryKeys.courier.dashboard(scope);
  useEffect(() => () => {
    void client.cancelQueries({ queryKey: queryKeys.courier.dashboard(scope) });
    client.removeQueries({ queryKey: queryKeys.courier.dashboard(scope) });
  }, [client, scope]);
  return useQuery({
    queryKey: queryKeys.courier.dashboard(scope, { page, size }),
    queryFn: async ({ signal }) => {
      try { return await getCourierTodayDashboard(page, size, user!.accessToken, signal); }
      catch (error) {
        if (dashboardAccessFailure(error)) client.setQueriesData({ queryKey: prefix }, null);
        throw error;
      }
    },
    enabled: Boolean(user?.id && user.accessToken), gcTime: 0, staleTime: 0,
    retry: (failures, error) => client.getDefaultOptions().queries?.retry !== false
      && failures < QUERY_RETRY_LIMIT && error instanceof CourierDashboardError && error.retryable,
    retryDelay: attempt => Math.min(300 * 2 ** attempt, 1000),
    refetchInterval: query => query.state.error instanceof CourierDashboardError && !query.state.error.retryable ? false : 15_000,
  });
}
