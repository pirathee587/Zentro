import axios from 'axios';
import { apiClient } from '@/shared/api/apiClient';
import { customerApiConfig } from './config';
import { mockCustomerAdapter } from '../mocks/mockAdapter';
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

export async function listParcels(
  params?: ListParcelsParams
): Promise<ListParcelsResponse> {
  if (customerApiConfig.useMocks) {
    return mockCustomerAdapter.listParcels(params);
  }
  const { data } = await apiClient.get<ListParcelsResponse>(
    `${customerApiConfig.baseUrl}/parcels`,
    { params }
  );
  return data;
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
