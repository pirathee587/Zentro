import { z } from 'zod';
import { apiClient, withBearerToken } from '@/shared/api/apiClient';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';

export const courierWorkloadKey = ['dispatcher', 'workload'] as const;
export const workloadQueryKey = (params?: { search?: string; availability?: 'available' | 'unavailable' }, userId?: string) =>
  queryKeys.dispatcher.workload(params, userId === undefined ? undefined : principalCacheScope(userId));

const workloadSchema = z.object({
  courierId: z.string().uuid(),
  userId: z.string().uuid(),
  name: z.string().nullable(),
  active: z.boolean(),
  accountStatus: z.string(),
  assignmentCount: z.number().int().nonnegative(),
  availability: z.enum(['AVAILABLE', 'UNAVAILABLE']),
});

const responseSchema = z.object({
  items: z.array(workloadSchema),
  total: z.number().int().nonnegative(),
  overloadConfigured: z.literal(false),
});

export type CourierWorkload = z.infer<typeof workloadSchema>;

export async function getCourierWorkload(
  params: { search?: string; availability?: 'available' | 'unavailable' },
  token?: string,
  signal?: AbortSignal,
) {
  const response = await apiClient.get('/api/v1/dispatch/workload', { params, ...withBearerToken(token), signal });
  return responseSchema.parse(response.data);
}
