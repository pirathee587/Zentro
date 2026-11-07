import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { apiClient, withBearerToken } from '@/shared/api/apiClient';
import { queryKeys } from '@/shared/api/queryKeys';
import { useAuth } from '@/shared/auth/useAuth';

export const dispatchQueueKey = ['dispatcher', 'queue'] as const;
export type QueueFilters = { search: string; from: string; to: string; sort: 'newest' | 'oldest' };
export const defaultQueueFilters: QueueFilters = { search: '', from: '', to: '', sort: 'newest' };
const parcelSchema = z.object({
  parcelId: z.string().uuid(), trackingReference: z.string().nullable(),
  pickupSummary: z.string().nullable(), dropoffSummary: z.string().nullable(),
  createdAt: z.string().datetime({ offset: true }),
});
const queueSchema = z.object({
  items: z.array(parcelSchema), total: z.number().int().nonnegative().nullable(),
  matchingTotal: z.number().int().nonnegative().nullable(), complete: z.boolean(),
}).refine(value => value.complete
  ? value.total !== null && value.matchingTotal !== null && value.matchingTotal === value.items.length && value.total >= value.matchingTotal
  : value.total === null && value.matchingTotal === null, 'Invalid queue totals');
export type QueueParcel = z.infer<typeof parcelSchema>;
export type DispatchQueue = z.infer<typeof queueSchema>;
const courierSchema = z.object({ id: z.string().uuid(), userId: z.string().uuid(), name: z.string().nullable(), active: z.literal(true), accountStatus: z.literal('ACTIVE') });
export type EligibleCourier = z.infer<typeof courierSchema>;

export function useDispatchQueue(filters = defaultQueueFilters) {
  const { user } = useAuth();
  return useQuery({
    queryKey: queryKeys.dispatcher.queue(filters), enabled: Boolean(user?.accessToken),
    queryFn: async ({ signal }) => queueSchema.parse((await apiClient.get('/api/v1/dispatch/queue', {
      ...withBearerToken(user!.accessToken!), params: { search: filters.search, from: filters.from || undefined, to: filters.to || undefined, sort: filters.sort },
      signal,
    })).data),
  });
}

export async function eligibleCouriers(parcelId: string, token: string, signal?: AbortSignal) {
  return z.array(courierSchema).parse((await apiClient.get('/api/v1/dispatch/eligible-couriers', {
    ...withBearerToken(token), params: { parcelId }, signal,
  })).data);
}

export async function assignDelivery(parcelId: string, courierId: string, token: string) {
  await apiClient.post('/api/v1/dispatch/assignments', { parcelId, courierId }, withBearerToken(token));
}
