import axios from 'axios';
import { apiClient } from '@/shared/api/apiClient';
import { customerApiConfig } from './config';
import { mockCustomerAdapter } from '../mocks/mockAdapter';
import { normalizeParcelStatus, formatMoney, formatDate } from './formatters';
import type {
  DashboardData,
  FeeEstimateRequest,
  FeeEstimateResponse,
  CreateDeliveryRequest,
  CreateDeliveryOptions,
  Parcel,
  ListParcelsParams,
  ListParcelsResponse,
  Receipt,
  AssistantResponse,
  SupportMessagePayload,
  SupportTicket,
} from './types';

export * from './types';
export * from './formatters';
export * from './config';

/**
 * SwiftDrop Customer Portal API Service Module
 * Handles all dashboard, delivery, tracking, receipt, assistant, and support operations.
 * Relies on the mock adapter until issue #4 API contracts are finalized.
 */

export async function getDashboard(): Promise<DashboardData> {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.getDashboard();
  }
  const { data } = await apiClient.get<DashboardData>(`${customerApiConfig.baseUrl}/dashboard`);
  return data;
}

export async function estimateFee(payload: FeeEstimateRequest): Promise<FeeEstimateResponse> {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.estimateFee(payload);
  }
  const { data } = await apiClient.post<FeeEstimateResponse>(
    `${customerApiConfig.baseUrl}/estimate-fee`,
    payload
  );
  return data;
}

export type BookingErrorKind = 'validation' | 'unauthorized' | 'unavailable' | 'unknown';

/** Typed failure from createDelivery so the UI can decide between field errors, re-login and retry. */
export class BookingError extends Error {
  constructor(
    public readonly kind: BookingErrorKind,
    message: string,
    /** Server field errors keyed by request path, e.g. "recipient.phone". */
    public readonly fieldErrors: Record<string, string> = {},
    public readonly status?: number
  ) {
    super(message);
    this.name = 'BookingError';
  }
}

/**
 * Accepts the two common 422 body shapes:
 *   { fieldErrors: { "recipient.phone": "..." } }
 *   { errors: [{ field: "recipient.phone", message: "..." }] }
 */
function parseFieldErrors(body: unknown): Record<string, string> {
  if (!body || typeof body !== 'object') return {};
  const b = body as { fieldErrors?: unknown; errors?: unknown };
  const out: Record<string, string> = {};
  if (b.fieldErrors && typeof b.fieldErrors === 'object') {
    for (const [k, v] of Object.entries(b.fieldErrors as Record<string, unknown>)) {
      if (typeof v === 'string') out[k] = v;
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
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    if (!error.response) {
      return new BookingError('unavailable', 'We could not reach SwiftDrop. Check your connection and try again.');
    }
    if (status === 422 || status === 400) {
      return new BookingError('validation', 'Some details need fixing.', parseFieldErrors(error.response.data), status);
    }
    if (status === 401) {
      return new BookingError('unauthorized', 'Your session has expired. Please sign in again.', {}, status);
    }
    if (status === 502 || status === 503 || status === 504) {
      return new BookingError('unavailable', 'Booking service is temporarily unavailable. Please try again.', {}, status);
    }
    return new BookingError('unknown', 'Something went wrong while booking. Please try again.', {}, status);
  }
  return new BookingError('unknown', 'Something went wrong while booking. Please try again.');
}

export async function createDelivery(
  payload: CreateDeliveryRequest,
  options: CreateDeliveryOptions = { idempotencyKey: (typeof crypto !== 'undefined' && crypto.randomUUID) ? crypto.randomUUID() : `idemp-${Date.now()}` }
): Promise<Parcel> {
  try {
    if (customerApiConfig.useMocks) {
      return await mockCustomerAdapter.createDelivery(payload, options);
    }
    const { data } = await apiClient.post<Parcel>(`${customerApiConfig.baseUrl}/deliveries`, payload, {
      headers: { 'Idempotency-Key': options.idempotencyKey },
    });
    return data;
  } catch (error) {
    throw toBookingError(error);
  }
}

export function normalizeParcelRecord(p: Partial<Parcel>): Parcel {
  const normStatus = normalizeParcelStatus(p.status || p.rawStatus);
  const fee = typeof p.fee === 'number' ? p.fee : 0;
  const createdAt = p.createdAt || new Date().toISOString();

  return {
    id: p.id || p.trackingRef || `pcl-${Math.random().toString(36).slice(2, 9)}`,
    trackingRef: p.trackingRef || p.id || 'ZTR-UNKNOWN',
    status: normStatus,
    rawStatus: p.rawStatus || p.status,
    sender: {
      name: p.sender?.name || '',
      phone: p.sender?.phone || '',
      address: p.sender?.address || '',
      city: p.sender?.city || p.sender?.address || 'Origin',
    },
    recipient: {
      name: p.recipient?.name || '',
      phone: p.recipient?.phone || '',
      address: p.recipient?.address || '',
      city: p.recipient?.city || p.recipient?.address || 'Destination',
    },
    parcel: {
      description: p.parcel?.description || 'Parcel',
      weightKg: typeof p.parcel?.weightKg === 'number' ? p.parcel.weightKg : 1.0,
      category: p.parcel?.category || 'Standard',
      size: p.parcel?.size,
    },
    fee,
    formattedFee: p.formattedFee || formatMoney(fee),
    createdAt,
    formattedCreatedAt: p.formattedCreatedAt || formatDate(createdAt),
    estimatedDeliveryDate: p.estimatedDeliveryDate,
    formattedEstimatedDeliveryDate:
      p.formattedEstimatedDeliveryDate ||
      (p.estimatedDeliveryDate ? formatDate(p.estimatedDeliveryDate) : undefined),
    deliveredAt: p.deliveredAt,
    formattedDeliveredAt:
      p.formattedDeliveredAt || (p.deliveredAt ? formatDate(p.deliveredAt) : undefined),
    failureReason: p.failureReason,
    courier: p.courier,
    timeline: Array.isArray(p.timeline) ? p.timeline : [],
  };
}

export function normalizeListParcelsResponse(raw: unknown): ListParcelsResponse {
  if (!raw || typeof raw !== 'object') {
    return { parcels: [], total: 0, totalSpent: 0, formattedTotalSpent: formatMoney(0) };
  }

  if (Array.isArray(raw)) {
    const parcels = raw.map(normalizeParcelRecord);
    const totalSpent = parcels.reduce((sum, p) => sum + (p.fee || 0), 0);
    return {
      parcels,
      total: parcels.length,
      totalSpent,
      formattedTotalSpent: formatMoney(totalSpent),
    };
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

  const totalSpent =
    typeof obj.totalSpent === 'number'
      ? obj.totalSpent
      : parcels.reduce((sum, p) => sum + (p.fee || 0), 0);

  return {
    parcels,
    total,
    totalSpent,
    formattedTotalSpent:
      typeof obj.formattedTotalSpent === 'string'
        ? obj.formattedTotalSpent
        : formatMoney(totalSpent),
  };
}

export async function listParcels(
  params?: ListParcelsParams
): Promise<ListParcelsResponse> {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.listParcels(params);
  }
  const endpoint = customerApiConfig.deliveriesEndpoint || `${customerApiConfig.baseUrl}/parcels`;
  const { data } = await apiClient.get<unknown>(endpoint, { params });
  return normalizeListParcelsResponse(data);
}

export async function getParcel(idOrRef: string): Promise<Parcel | null> {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.getParcel(idOrRef);
  }
  try {
    const { data } = await apiClient.get<Parcel>(
      `${customerApiConfig.baseUrl}/parcels/${encodeURIComponent(idOrRef)}`
    );
    return data;
  } catch (error: unknown) {
    const status = (error as { response?: { status?: number } })?.response?.status;
    if (status === 404) return null;
    throw error;
  }
}

export function subscribeToParcel(
  idOrRef: string,
  callback: (parcel: Parcel) => void
): () => void {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.subscribeToParcel(idOrRef, callback);
  }

  // Real SSE / WebSocket / polling fallback when real baseUrl is enabled
  const eventSource = new EventSource(
    `${customerApiConfig.baseUrl}/parcels/${encodeURIComponent(idOrRef)}/stream`
  );
  eventSource.onmessage = (event) => {
    try {
      const parsed = JSON.parse(event.data) as Parcel;
      callback(parsed);
    } catch {
      // ignore parse errors
    }
  };
  return () => {
    eventSource.close();
  };
}

export async function listReceipts(): Promise<Receipt[]> {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.listReceipts();
  }
  const { data } = await apiClient.get<Receipt[]>(`${customerApiConfig.baseUrl}/receipts`);
  return data;
}

export async function askAssistant(question: string): Promise<AssistantResponse> {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.askAssistant(question);
  }
  const { data } = await apiClient.post<AssistantResponse>(
    `${customerApiConfig.baseUrl}/assistant`,
    { question }
  );
  return data;
}

export async function sendSupportMessage(
  payload: SupportMessagePayload
): Promise<SupportTicket> {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.sendSupportMessage(payload);
  }
  const { data } = await apiClient.post<SupportTicket>(
    `${customerApiConfig.baseUrl}/support/messages`,
    payload
  );
  return data;
}
