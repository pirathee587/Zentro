import { useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { useAuth } from '@/shared/auth/useAuth';
import { queryKeys, principalCacheScope } from '@/shared/api/queryKeys';
import { listDeliveries, deliveryAccessDenied, deliverySessionExpired } from '../api/deliveryApi';

const records = z.array(
  z.object({
    parcelId: z.string().uuid(),
    courierId: z.string().uuid(),
    assignedAt: z.string().datetime({ offset: true }),
    proofId: z.string().uuid().nullable(),
    completionState: z.enum(['PENDING', 'COMPLETED', 'REJECTED', 'BLOCKED']).nullable(),
  }),
);
/** REST current-assignment records, not the Today-only GraphQL query or a historical ledger. */
export function useCourierAssignments() {
  const { user } = useAuth();
  const client = useQueryClient();
  return useQuery({
    queryKey: queryKeys.courier.deliveries(principalCacheScope(user?.id)),
    queryFn: async ({ signal }) => records.parse(await listDeliveries(user!.accessToken, signal)),
    enabled: Boolean(user?.id && user.accessToken),
    gcTime: 0,
    staleTime: 10_000,
    retry: (failures, error) =>
      client.getDefaultOptions().queries?.retry !== false &&
      failures < 1 &&
      !deliveryAccessDenied(error) &&
      !deliverySessionExpired(error),
    retryDelay: 300,
  });
}
