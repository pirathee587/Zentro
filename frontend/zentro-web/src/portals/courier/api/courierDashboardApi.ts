import axios from 'axios';
import { z } from 'zod';
import { apiClient, withBearerToken } from '@/shared/api/apiClient';

const statusSchema = z.enum(['CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED']);
const count = z.number().int().nonnegative();
const deliverySchema = z.object({
  parcelId: z.string().uuid(), assignedAt: z.string().datetime(), assignmentState: z.literal('ASSIGNED'),
  projectionAvailable: z.boolean(), trackingRef: z.string().nullable(), status: statusSchema.nullable(),
  recipient: z.object({ name: z.string(), phone: z.string(), address: z.string(), city: z.string().nullable() }).nullable(),
  weightKg: z.number().nonnegative().nullable(), size: z.string().nullable(), failureReason: z.string().nullable(),
  proofId: z.string().uuid().nullable(), completionState: z.enum(['PENDING', 'COMPLETED', 'REJECTED', 'BLOCKED']).nullable(),
}).transform(row => row.projectionAvailable ? row : {
  ...row, trackingRef: null, status: null, recipient: null, weightKg: null, size: null, failureReason: null,
});
const dashboardSchema = z.object({
  businessDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), timeZone: z.literal('Asia/Colombo'),
  counts: z.object({ assigned: count, delivered: count, failed: count, remaining: count })
    .refine(c => c.assigned === c.delivered + c.failed + c.remaining),
  currentDelivery: deliverySchema.nullable(), deliveries: z.array(deliverySchema),
  pageInfo: z.object({ page: z.number().int().positive(), size: z.number().int().positive().max(50),
    totalElements: count, totalPages: count, hasNext: z.boolean(), hasPrevious: z.boolean() }),
});
export type CourierDashboard = z.infer<typeof dashboardSchema>;
export type DashboardDelivery = z.infer<typeof deliverySchema>;
export type DashboardStatus = z.infer<typeof statusSchema>;
export type DashboardResult = { dashboard: CourierDashboard; partial: boolean };
type ErrorKind = 'unauthorized' | 'forbidden' | 'profile' | 'ownership' | 'dependency' | 'network' | 'invalid';
export class CourierDashboardError extends Error {
  constructor(readonly kind: ErrorKind) { super(dashboardMessage(kind)); this.name = 'CourierDashboardError'; }
  get retryable() { return ['dependency', 'network', 'ownership'].includes(this.kind); }
}
function dashboardMessage(kind: ErrorKind): string {
  switch (kind) {
    case 'unauthorized': return 'Your session has expired. Please sign in again.';
    case 'forbidden': return "You don't have access to this courier dashboard.";
    case 'profile': return 'Your courier profile could not be verified. Please contact dispatch.';
    case 'ownership': return 'Your assignments have changed. Refresh to see your current deliveries.';
    case 'dependency': return 'Delivery dashboard data is temporarily unavailable. Please try again later.';
    case 'network': return 'We could not reach Zentro. Check your connection and try again.';
    default: return 'The delivery dashboard could not be loaded. Please try again later.';
  }
}
export function dashboardErrorMessage(error: unknown): string {
  return error instanceof CourierDashboardError ? error.message : dashboardMessage('invalid');
}
export function dashboardAccessFailure(error: unknown): boolean {
  return error instanceof CourierDashboardError && ['unauthorized', 'forbidden', 'profile', 'ownership'].includes(error.kind);
}
export const courierTodayQuery = `query CourierTodayDashboard($page: Int!, $size: Int!) {
  courierTodayDashboard(page: $page, size: $size) {
    businessDate timeZone counts { assigned delivered failed remaining }
    currentDelivery { ...CourierTodayDelivery }
    deliveries { ...CourierTodayDelivery }
    pageInfo { page size totalElements totalPages hasNext hasPrevious }
  }
}
fragment CourierTodayDelivery on CourierTodayDelivery {
  parcelId assignedAt assignmentState projectionAvailable trackingRef status
  recipient { name phone address city } weightKg size failureReason proofId completionState
}`;
const errorSchema = z.object({ extensions: z.object({ classification: z.string().optional(), code: z.string().optional() }).optional(),
  path: z.array(z.union([z.string(), z.number()])).optional() });
const envelopeSchema = z.object({ data: z.object({ courierTodayDashboard: z.unknown() }).nullish(), errors: z.array(errorSchema).optional() });
export async function getCourierTodayDashboard(page: number, size: number, token: string, signal?: AbortSignal): Promise<DashboardResult> {
  let body: unknown;
  try {
    body = (await apiClient.post('/graphql', { operationName: 'CourierTodayDashboard', query: courierTodayQuery,
      variables: { page, size } }, withBearerToken(token, { signal, timeout: 20_000 }))).data;
  } catch (error) {
    if (axios.isCancel(error) || (axios.isAxiosError(error) && error.code === 'ERR_CANCELED')) throw error;
    if (axios.isAxiosError(error)) {
      if (error.response?.status === 401) throw new CourierDashboardError('unauthorized');
      if (error.response?.status === 403) throw new CourierDashboardError('forbidden');
      throw new CourierDashboardError(error.response ? 'dependency' : 'network');
    }
    throw new CourierDashboardError('invalid');
  }
  const envelope = envelopeSchema.safeParse(body);
  if (!envelope.success) throw new CourierDashboardError('invalid');
  const errors = envelope.data.errors ?? [];
  // Authorization and ownership errors always discard even a partially successful response.
  for (const error of errors) {
    const { code, classification } = error.extensions ?? {};
    if ([code, classification].includes('UNAUTHORIZED')) throw new CourierDashboardError('unauthorized');
    if ([code, classification].includes('FORBIDDEN')) throw new CourierDashboardError('forbidden');
    if (code === 'COURIER_PROFILE_NOT_FOUND' || classification === 'NOT_FOUND') throw new CourierDashboardError('profile');
    if (code === 'ASSIGNMENTS_CHANGED') throw new CourierDashboardError('ownership');
  }
  if (errors.some(error => !error.path || error.path.length <= 1)) throw new CourierDashboardError('dependency');
  const parsed = dashboardSchema.safeParse(envelope.data.data?.courierTodayDashboard);
  if (!parsed.success) throw new CourierDashboardError(errors.length ? 'dependency' : 'invalid');
  return { dashboard: parsed.data, partial: errors.length > 0 };
}
