import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import { z } from 'zod';
import { apiClient, withBearerToken } from '@/shared/api/apiClient';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import { useAuth } from '@/shared/auth/useAuth';
import { dispatchQueueKey, useDispatchQueue, type QueueParcel } from './dispatchApi';

export type ParcelStatus =
  | 'Unassigned'
  | 'Assigned'
  | 'Picked up'
  | 'In transit'
  | 'Out for delivery'
  | 'Delivered'
  | 'Failed';

const assignmentSchema = z.object({
  parcelId: z.string().uuid(),
  courierId: z.string().uuid(),
  courierName: z.string().nullable(),
  assignmentStatus: z.string().nullable(),
  assignedAt: z.string().nullable(),
});
export type DispatchAssignment = z.infer<typeof assignmentSchema>;

const placeSchema = z.object({ address: z.string().nullable(), city: z.string().nullable() }).nullable();
const parcelDetailSchema = z.object({
  id: z.string().uuid(),
  trackingRef: z.string().nullable(),
  status: z.enum(['CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED']),
  createdAt: z.string().nullable(),
  sender: placeSchema,
  recipient: placeSchema,
  parcel: z.object({
    weightKg: z.number().nullable(),
    category: z.string().nullable(),
    size: z.string().nullable(),
  }).nullable(),
});
export type ParcelDetail = z.infer<typeof parcelDetailSchema>;

export interface DispatcherParcel {
  parcelId: string;
  reference: string;
  route: string;
  status: ParcelStatus;
  courier: string | null;
  assignmentStatus: string | null;
  weightCategory: string | null;
  weightKg: string | null;
  createdAt: string | null;
  /** False when S2 parcel details could not be loaded; the row uses queue/assignment data only. */
  detailAvailable: boolean;
}

const S2_STATUS: Record<ParcelDetail['status'], ParcelStatus | null> = {
  CREATED: null,
  PICKED_UP: 'Picked up',
  IN_TRANSIT: 'In transit',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  FAILED: 'Failed',
};

/**
 * S2 parcel status is authoritative once a parcel moves past CREATED. A CREATED parcel is
 * Unassigned while S2 still offers it to the dispatch queue, and Assigned while S3 holds a
 * non-rejected assignment for it.
 */
export function deriveParcelStatus(
  s2Status: ParcelDetail['status'] | undefined,
  inQueue: boolean,
  assignment: DispatchAssignment | undefined,
): ParcelStatus {
  const progressed = s2Status ? S2_STATUS[s2Status] : null;
  if (progressed) return progressed;
  if (inQueue) return 'Unassigned';
  if (assignment && assignment.assignmentStatus !== 'REJECTED') return 'Assigned';
  return 'Unassigned';
}

function place(contact: ParcelDetail['sender'] | undefined, fallback: string | null | undefined) {
  return contact?.city?.trim() || contact?.address?.trim() || fallback?.trim() || null;
}

export function toDispatcherParcel(
  parcelId: string,
  queued: QueueParcel | undefined,
  assignment: DispatchAssignment | undefined,
  detail: ParcelDetail | undefined,
): DispatcherParcel {
  const status = deriveParcelStatus(detail?.status, Boolean(queued), assignment);
  const activeAssignment = assignment && assignment.assignmentStatus !== 'REJECTED' && status !== 'Unassigned';
  const weight = detail?.parcel?.weightKg;
  return {
    parcelId,
    reference: detail?.trackingRef || queued?.trackingReference || 'Reference unavailable',
    route: `${place(detail?.sender, queued?.pickupSummary) ?? 'Pickup unavailable'} to ${place(detail?.recipient, queued?.dropoffSummary) ?? 'Drop-off unavailable'}`,
    status,
    courier: activeAssignment ? assignment.courierName || 'Assigned courier' : null,
    assignmentStatus: activeAssignment ? assignment.assignmentStatus : null,
    weightCategory: detail?.parcel?.category || detail?.parcel?.size || null,
    weightKg: weight === null || weight === undefined ? null : `${weight} kg`,
    createdAt: detail?.createdAt || queued?.createdAt || assignment?.assignedAt || null,
    detailAvailable: Boolean(detail),
  };
}

/** All dispatcher-visible parcels: unassigned queue items plus every S3 assignment, enriched from S2. */
export function useDispatcherParcels() {
  const { user } = useAuth();
  const client = useQueryClient();
  const token = user?.accessToken;
  const scope = principalCacheScope(user?.id);
  const queue = useDispatchQueue();
  const assignments = useQuery({
    queryKey: queryKeys.dispatcher.assignments(scope),
    enabled: Boolean(token),
    queryFn: async ({ signal }) => z.array(assignmentSchema).parse((await apiClient.get(
      '/api/v1/dispatch/assignments', withBearerToken(token!, { signal }))).data),
  });

  const ids = useMemo(() => {
    if (!queue.data || !assignments.data) return [];
    return [...new Set([...queue.data.items.map(item => item.parcelId), ...assignments.data.map(item => item.parcelId)])];
  }, [queue.data, assignments.data]);

  const details = useQueries({
    queries: ids.map(parcelId => ({
      queryKey: queryKeys.dispatcher.parcel(parcelId, scope),
      enabled: Boolean(token),
      staleTime: 30_000,
      queryFn: async ({ signal }: { signal: AbortSignal }) => parcelDetailSchema.parse((await apiClient.get(
        `/api/v1/parcels/${encodeURIComponent(parcelId)}`, withBearerToken(token!, { signal }))).data),
    })),
  });

  const parcels = useMemo(() => {
    if (!queue.data || !assignments.data) return [];
    const queued = new Map(queue.data.items.map(item => [item.parcelId, item]));
    const assigned = new Map(assignments.data.map(item => [item.parcelId, item]));
    return ids
      .map((parcelId, index) => toDispatcherParcel(parcelId, queued.get(parcelId), assigned.get(parcelId), details[index]?.data))
      .sort((left, right) => (right.createdAt ?? '').localeCompare(left.createdAt ?? ''));
  }, [ids, queue.data, assignments.data, details]);

  const refresh = useCallback(() => Promise.all([
    client.invalidateQueries({ queryKey: dispatchQueueKey }),
    client.invalidateQueries({ queryKey: queryKeys.dispatcher.assignments() }),
    client.invalidateQueries({ queryKey: queryKeys.dispatcher.parcel() }),
  ]), [client]);

  return {
    parcels,
    isPending: queue.isPending || assignments.isPending,
    error: queue.error ?? assignments.error ?? null,
    detailsPending: details.some(detail => detail.isPending),
    failedDetails: details.filter(detail => detail.isError).length,
    queueComplete: queue.data?.complete ?? false,
    isFetching: queue.isFetching || assignments.isFetching || details.some(detail => detail.isFetching),
    refresh,
  };
}
