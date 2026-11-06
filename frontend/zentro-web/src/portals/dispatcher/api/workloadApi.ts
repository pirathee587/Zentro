import { z } from 'zod';
import { apiClient } from '@/shared/api/apiClient';

export const courierWorkloadKey = ['dispatcher', 'workload'] as const;

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
) {
  const headers = token ? { Authorization: `Bearer ${token}` } : undefined;
  const response = await apiClient.get('/api/v1/dispatch/workload', { params, headers });
  return responseSchema.parse(response.data);
}
