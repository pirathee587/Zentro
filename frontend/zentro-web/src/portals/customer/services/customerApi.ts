import axios from 'axios';
import { getDashboardData, getCustomerDelivery } from './dashboardGraphql';
import { graphqlQuery } from '@/shared/api/graphqlClient';
import { apiClient, getAccessToken } from '@/shared/api/apiClient';
import { customerApiConfig } from './config';
import { normalizeParcelStatus, formatMoney, formatDate } from './formatters';
import {
  BookingError,
  type BookingErrorKind,
  type DashboardData,
  type FeeEstimateRequest,
  type FeeEstimateResponse,
  type CreateDeliveryRequest,
  type CreateDeliveryOptions,
  type ParcelBookingResponse,
  type Parcel,
  type ProofOfDeliverySummary,
  type ListParcelsParams,
  type ListParcelsResponse,
  type Receipt,
  type AssistantHistoryMessage,
  type AssistantResponse,
  type SupportMessagePayload,
  type SupportTicket,
} from './types';

export * from './types';
export * from './formatters';
export * from './config';

/**
 * SwiftDrop Customer Portal API Service Module
 * Handles all dashboard, delivery, tracking, receipt, assistant, and support operations.
 * Reads and writes use the existing authenticated gateway; no production mock fallback.
 */

export async function getDashboard(signal?: AbortSignal): Promise<DashboardData> {
  return getDashboardData(signal);
}

export async function estimateFee(payload: FeeEstimateRequest): Promise<FeeEstimateResponse> {
  const { data } = await apiClient.post<FeeEstimateResponse>(
    `${customerApiConfig.parcelsPath}/estimate`,
    payload
  );
  return data;
}

export { BookingError, type BookingErrorKind };

/**
 * Accepts the common 422 body shapes:
 *   { fieldErrors: { "recipient.phone": "..." } }
 *   { invalidFields: { "recipient.phone": "..." } }
 *   { errors: [{ field: "recipient.phone", message: "..." }] }
 */
function parseFieldErrors(body: unknown): Record<string, string> {
  if (!body || typeof body !== 'object') return {};
  const b = body as { fieldErrors?: unknown; invalidFields?: unknown; errors?: unknown };
  const out: Record<string, string> = {};
  if (b.fieldErrors && typeof b.fieldErrors === 'object') {
    for (const [k, v] of Object.entries(b.fieldErrors as Record<string, unknown>)) {
      if (typeof v === 'string') out[k] = v;
    }
  }
  if (b.invalidFields && typeof b.invalidFields === 'object') {
    for (const [k, v] of Object.entries(b.invalidFields as Record<string, unknown>)) {
      if (typeof v === 'string' && !(k in out)) out[k] = v;
    }
  }
  if (Array.isArray(b.errors)) {
    for (const e of b.errors as { field?: unknown; message?: unknown }[]) {
      if (typeof e?.field === 'string' && typeof e?.message === 'string') out[e.field] = e.message;
    }
  }
  return out;
}

export function toBookingError(error: unknown): BookingError {
  if (error instanceof BookingError) return error;
  const isErrorLike =
    axios.isAxiosError(error) ||
    (typeof error === 'object' &&
      error !== null &&
      ('response' in error ||
        'code' in error ||
        'isAxiosError' in error ||
        ('message' in error &&
          typeof (error as { message?: unknown }).message === 'string' &&
          /network|timeout|exceeded|failed to fetch/i.test(
            (error as { message: string }).message
          ))));

  if (isErrorLike) {
    const errObj = error as {
      response?: { status?: number; data?: unknown };
      code?: string;
    };
    const status = errObj.response?.status;
    if (
      !errObj.response ||
      errObj.code === 'ECONNABORTED' ||
      errObj.code === 'ETIMEDOUT' ||
      errObj.code === 'ERR_NETWORK'
    ) {
      return new BookingError(
        'unavailable',
        'We could not reach SwiftDrop. Check your connection and try again.'
      );
    }
    if (status === 422) {
      return new BookingError(
        'validation',
        'Some booking details are missing or invalid.',
        parseFieldErrors(errObj.response.data),
        422
      );
    }
    if (status === 400) {
      return new BookingError(
        'bad_request',
        "We couldn't process this request, please check your details",
        {},
        400
      );
    }
    if (status === 401) {
      return new BookingError(
        'unauthorized',
        'Your session has expired. Please sign in again.',
        {},
        401
      );
    }
    if (status === 403) {
      return new BookingError('forbidden', "Your account can't create deliveries", {}, 403);
    }
    if (status === 409) {
      return new BookingError(
        'conflict',
        'This booking conflicts with an earlier attempt. Review your details and try again',
        {},
        409
      );
    }
    if (status === 502 || status === 503 || status === 504) {
      return new BookingError(
        'unavailable',
        'Booking service is temporarily unavailable. Please try again.',
        {},
        status
      );
    }
    return new BookingError(
      'unknown',
      'Something went wrong while booking. Please try again.',
      {},
      status
    );
  }
  return new BookingError('unknown', 'Something went wrong while booking. Please try again.');
}

function sanitizeCreateDeliveryRequest(payload: CreateDeliveryRequest): CreateDeliveryRequest {
  return {
    sender: {
      name: payload.sender.name,
      phone: payload.sender.phone,
      address: payload.sender.address,
      ...(payload.sender.city !== undefined ? { city: payload.sender.city } : {}),
    },
    recipient: {
      name: payload.recipient.name,
      phone: payload.recipient.phone,
      address: payload.recipient.address,
      ...(payload.recipient.city !== undefined ? { city: payload.recipient.city } : {}),
    },
    parcel: {
      weightKg: payload.parcel.weightKg,
      category: payload.parcel.category,
      ...(payload.parcel.size !== undefined ? { size: payload.parcel.size } : {}),
      ...(payload.parcel.description !== undefined ? { description: payload.parcel.description } : {}),
    },
    ...(payload.destinationType !== undefined ? { destinationType: payload.destinationType } : {}),
    ...(payload.isExpress !== undefined ? { isExpress: payload.isExpress } : {}),
    ...(payload.notes !== undefined ? { notes: payload.notes } : {}),
  };
}

function toParcelBookingResponse(raw: ParcelBookingResponse): ParcelBookingResponse {
  if (
    !raw ||
    typeof raw !== 'object' ||
    typeof raw.trackingRef !== 'string' ||
    !raw.trackingRef.trim() ||
    typeof raw.fee !== 'number' ||
    typeof raw.status !== 'string' || !raw.status ||
    typeof raw.currency !== 'string' || !raw.currency ||
    typeof raw.estimatedDays !== 'number' ||
    typeof raw.createdAt !== 'string' || !raw.createdAt ||
    typeof raw.parcel?.weightKg !== 'number'
  ) {
    throw new BookingError('unknown', 'Something went wrong while booking. Please try again.');
  }
  return {
    trackingRef: raw.trackingRef,
    status: raw.status,
    fee: raw.fee,
    currency: raw.currency,
    estimatedDays: raw.estimatedDays,
    createdAt: raw.createdAt,
    sender: {
      name: raw.sender?.name || '',
      phone: raw.sender?.phone || '',
      address: raw.sender?.address || '',
      ...(raw.sender?.city !== undefined ? { city: raw.sender.city } : {}),
    },
    recipient: {
      name: raw.recipient?.name || '',
      phone: raw.recipient?.phone || '',
      address: raw.recipient?.address || '',
      ...(raw.recipient?.city !== undefined ? { city: raw.recipient.city } : {}),
    },
    parcel: {
      weightKg: raw.parcel?.weightKg,
      category: raw.parcel?.category || '',
      ...(raw.parcel?.size !== undefined ? { size: raw.parcel.size } : {}),
      ...(raw.parcel?.description !== undefined ? { description: raw.parcel.description } : {}),
    },
  };
}

export async function createDelivery(
  payload: CreateDeliveryRequest,
  options: CreateDeliveryOptions = {
    idempotencyKey:
      typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `idem-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`,
  }
): Promise<ParcelBookingResponse> {
  const sanitizedPayload = sanitizeCreateDeliveryRequest(payload);
  const idempotencyKey =
    options?.idempotencyKey?.trim() ||
    (typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `idem-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`);

  try {
    const { data } = await apiClient.post<ParcelBookingResponse>(
      customerApiConfig.parcelsPath,
      sanitizedPayload,
      {
        headers: { 'Idempotency-Key': idempotencyKey },
      }
    );
    return toParcelBookingResponse(data);
  } catch (error) {
    throw toBookingError(error);
  }
}

export interface RawTrackingPayload {
  id?: string;
  trackingRef?: string;
  status?: string;
  rawStatus?: string;
  destinationType?: string;
  isExpress?: boolean;
  category?: string;
  size?: string;
  description?: string;
  originCity?: string;
  destinationCity?: string;
  estimatedDays?: number;
  estimatedDeliveryDate?: string;
  formattedEstimatedDeliveryDate?: string;
  createdAt?: string;
  formattedCreatedAt?: string;
  deliveredAt?: string;
  formattedDeliveredAt?: string;
  failureReason?: string;
  proofOfDelivery?: ProofOfDeliverySummary;
  fee?: number;
  formattedFee?: string;
  sender?: { name?: string; phone?: string; address?: string; city?: string };
  recipient?: { name?: string; phone?: string; address?: string; city?: string };
  parcel?: { description?: string; weightKg?: number; category?: string; size?: string };
  weightKg?: number;
  courier?: { name?: string; initials?: string; role?: string; zone?: string; phone?: string; vehicle?: string };
  events?: Array<{ id: string; title?: string; description?: string; occurredAt: string; status: string }>;
  timeline?: Array<{
    id?: string;
    title: string;
    description?: string;
    timestamp?: string;
    formattedDate?: string;
    location?: string;
    status: 'completed' | 'current' | 'upcoming' | 'failed' | string;
    failureReason?: string;
  }>;
}

export function normalizeParcelRecord(p: RawTrackingPayload | Partial<Parcel> | Record<string, unknown>): Parcel {
  if (!p || typeof p !== 'object') {
    throw new Error('The parcel response is invalid.');
  }

  const raw = p as RawTrackingPayload;
  if (!raw.trackingRef || !(raw.status || raw.rawStatus)) throw new Error('The parcel response is missing its reference or status.');
  const normStatus = normalizeParcelStatus(raw.status || raw.rawStatus);
  const fee = typeof raw.fee === 'number' ? raw.fee : undefined;
  const createdAt = raw.createdAt || '';
  const estimatedDeliveryDate = raw.estimatedDeliveryDate || (createdAt && raw.estimatedDays
    ? new Date(new Date(createdAt).getTime() + raw.estimatedDays * 86400000).toISOString() : undefined);

  // Support both PublicTrackingResponse (originCity/destinationCity) and customerApi (sender/recipient)
  const originCity = raw.originCity || raw.sender?.city || '';
  const destinationCity = raw.destinationCity || raw.recipient?.city || '';

  return {
    id: raw.id || raw.trackingRef || '',
    trackingRef: raw.trackingRef || raw.id || '',
    status: normStatus,
    rawStatus: raw.rawStatus || raw.status,
    destinationType: raw.destinationType,
    isExpress: raw.isExpress,
    sender: {
      name: raw.sender?.name || '',
      phone: raw.sender?.phone || '',
      address: raw.sender?.address || '',
      city: originCity,
    },
    recipient: {
      name: raw.recipient?.name || '',
      phone: raw.recipient?.phone || '',
      address: raw.recipient?.address || '',
      city: destinationCity,
    },
    parcel: {
      description: raw.description || raw.parcel?.description || '',
      weightKg:
        typeof raw.parcel?.weightKg === 'number'
          ? raw.parcel.weightKg
          : typeof raw.weightKg === 'number'
          ? raw.weightKg
          : undefined,
      category: raw.category || raw.parcel?.category || '',
      size: raw.size || raw.parcel?.size,
    },
    ...(fee !== undefined ? { fee } : {}),
    formattedFee: raw.formattedFee || (fee !== undefined ? formatMoney(fee) : ''),
    createdAt,
    formattedCreatedAt: raw.formattedCreatedAt || (createdAt ? formatDate(createdAt) : ''),
    estimatedDeliveryDate,
    formattedEstimatedDeliveryDate:
      raw.formattedEstimatedDeliveryDate ||
      (estimatedDeliveryDate ? formatDate(estimatedDeliveryDate) : undefined),
    deliveredAt: raw.deliveredAt,
    formattedDeliveredAt:
      raw.formattedDeliveredAt || (raw.deliveredAt ? formatDate(raw.deliveredAt) : undefined),
    failureReason: raw.failureReason,
    proofOfDelivery: raw.proofOfDelivery,
    courier: raw.courier?.name
      ? {
          name: raw.courier.name,
          initials: raw.courier.initials,
          role: raw.courier.role,
          zone: raw.courier.zone,
          phone: raw.courier.phone,
          vehicle: raw.courier.vehicle,
        }
      : undefined,
    timeline: (Array.isArray(raw.timeline) ? raw.timeline : (raw.events || []).map((event, index, events) => ({
      id: event.id, title: event.title || event.status, description: event.description, timestamp: event.occurredAt,
      status: index === events.length - 1 ? 'current' : 'completed',
    }))) as Parcel['timeline'],
  };
}

export function normalizeListParcelsResponse(raw: unknown): ListParcelsResponse {
  if (!raw || typeof raw !== 'object') {
    return { parcels: [], total: 0, formattedTotalSpent: 'Not recorded' };
  }

  if (Array.isArray(raw)) {
    const parcels = raw.map(normalizeParcelRecord);
    const totalSpent = parcels.reduce((sum, p) => sum + (p.fee ?? 0), 0);
    return { parcels, total: parcels.length, totalSpent, formattedTotalSpent: formatMoney(totalSpent) };
  }

  const obj = raw as Record<string, unknown>;
  const rawList = (Array.isArray(obj.parcels)
    ? obj.parcels
    : Array.isArray(obj.content)
    ? obj.content
    : Array.isArray(obj.items)
    ? obj.items
    : Array.isArray(obj.deliveries)
    ? obj.deliveries
    : Array.isArray(obj.data)
    ? obj.data
    : []) as Partial<Parcel>[];

  const parcels = rawList.map(normalizeParcelRecord);
  const total =
    typeof obj.total === 'number'
      ? obj.total
      : typeof obj.totalElements === 'number'
      ? obj.totalElements
      : parcels.length;

  const feeTotals = Array.isArray(obj.totalFees) ? obj.totalFees as { currency: string; amount: number }[] : undefined;
  const singleCurrencyTotal = feeTotals?.length === 1 ? feeTotals[0].amount : undefined;
  const totalSpent = typeof obj.totalSpent === 'number' ? obj.totalSpent : singleCurrencyTotal;
  const formattedTotalSpent = typeof obj.formattedTotalSpent === 'string' ? obj.formattedTotalSpent
    : feeTotals?.length ? feeTotals.map(value => formatMoney(value.amount, value.currency)).join(' / ')
    : totalSpent === undefined ? 'Not recorded' : formatMoney(totalSpent);
  return { parcels, total, ...(totalSpent !== undefined ? { totalSpent } : {}), formattedTotalSpent,
    ...(feeTotals ? { totalFees: feeTotals } : {}) };
}

export async function listParcels(
  params?: ListParcelsParams
): Promise<ListParcelsResponse> {
  const status = params?.status?.toUpperCase().replaceAll(' ', '_');
  const { data } = await apiClient.get(`${customerApiConfig.parcelsPath}/mine`, {
    params: { page: params?.page ?? 1, size: params?.limit ?? 10, status, q: params?.q ?? params?.search },
  });
  return normalizeListParcelsResponse(data);
}

/**
 * Public tracking must never attempt the customer-owned lookup merely because
 * a courier or merchant happens to have an access token in the browser.
 */
export async function getParcel(idOrRef: string, publicOnly = false): Promise<Parcel | null> {
  const reference = idOrRef.trim();
  if (!publicOnly && getAccessToken()) {
    const owned = await getCustomerDelivery(reference);
    if (owned) return owned;
  }
  const { publicTracking } = await graphqlQuery<{ publicTracking: RawTrackingPayload | null }>(
    `query CustomerTracking($trackingRef: String!) {
      publicTracking(trackingRef: $trackingRef) {
        trackingRef status category size description originCity destinationCity estimatedDays
        estimatedDeliveryDate createdAt deliveredAt failureReason
        events { id title description occurredAt status }
      }
    }`, { trackingRef: reference.toUpperCase() });
  return publicTracking ? normalizeParcelRecord(publicTracking) : null;
}

export function subscribeToParcel(idOrRef: string, callback: (parcel: Parcel) => void, publicOnly = false): () => void {
  let active = true;
  const timer = setInterval(() => {
    void getParcel(idOrRef, publicOnly).then(parcel => { if (active && parcel) callback(parcel); }).catch(() => {
      // Preserve the last verified record; a later successful poll refreshes it.
    });
  }, 30000);
  return () => { active = false; clearInterval(timer); };
}

export async function listReceipts(): Promise<Receipt[]> {
  const { data } = await apiClient.get<Receipt[]>(`${customerApiConfig.parcelsPath}/mine/receipts`);
  return data;
}

export async function askAssistant(
  question: string,
  history?: AssistantHistoryMessage[],
  conversationId?: string
): Promise<AssistantResponse> {
  const { data } = await apiClient.post<AssistantResponse>(
    '/api/v1/assistant/chat',
    {
      message: question,
      ...(conversationId ? { conversationId } : {}),
      ...(history && history.length > 0 ? { history: history.slice(-10) } : {}),
    },
    { timeout: 90000 }
  );
  return data;
}

export async function sendSupportMessage(
  payload: SupportMessagePayload
): Promise<SupportTicket> {
  const { data } = await apiClient.post<SupportTicket>('/api/v1/support/tickets', payload);
  return data;
}

export { getDashboardTracking, getDashboardLiveParcel, estimateDashboardFee } from './dashboardGraphql';
