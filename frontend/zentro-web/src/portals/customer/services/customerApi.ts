import axios from 'axios';
import { apiClient } from '@/shared/api/apiClient';
import { customerApiConfig } from './config';
import { mockCustomerAdapter } from '../mocks/mockAdapter';
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
 * Relies on the mock adapter until issue #4 API contracts are finalized.
 */

export async function getDashboard(): Promise<DashboardData> {
  // Dashboard contract is not approved; keep on mock adapter.
  return mockCustomerAdapter.getDashboard();
}

export async function estimateFee(payload: FeeEstimateRequest): Promise<FeeEstimateResponse> {
  if (customerApiConfig.useMocks || customerApiConfig.mode === 'mock') {
    return mockCustomerAdapter.estimateFee(payload);
  }
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
    typeof raw.fee !== 'number'
  ) {
    throw new BookingError('unknown', 'Something went wrong while booking. Please try again.');
  }
  return {
    trackingRef: raw.trackingRef,
    status: raw.status || 'CREATED',
    fee: raw.fee,
    currency: raw.currency || 'LKR',
    estimatedDays: typeof raw.estimatedDays === 'number' ? raw.estimatedDays : 2,
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
      weightKg: raw.parcel?.weightKg ?? 0,
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
    if (customerApiConfig.useMocks || customerApiConfig.mode === 'mock') {
      const mockResult = await mockCustomerAdapter.createDelivery(sanitizedPayload, {
        idempotencyKey,
      });
      return toParcelBookingResponse(mockResult);
    }
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
    return {
      id: '',
      trackingRef: '',
      status: 'Created',
      sender: { name: '', phone: '', address: '', city: 'Origin' },
      recipient: { name: '', phone: '', address: '', city: 'Destination' },
      parcel: { description: 'Parcel', weightKg: 1, category: 'Standard' },
      fee: 0,
      formattedFee: '',
      createdAt: new Date().toISOString(),
      formattedCreatedAt: '',
      timeline: [],
    };
  }

  const raw = p as RawTrackingPayload;
  const normStatus = normalizeParcelStatus(raw.status || raw.rawStatus);
  const fee = typeof raw.fee === 'number' ? raw.fee : 0;
  const createdAt = raw.createdAt || new Date().toISOString();

  // Support both PublicTrackingResponse (originCity/destinationCity) and customerApi (sender/recipient)
  const originCity = raw.originCity || raw.sender?.city || raw.sender?.address || 'Origin';
  const destinationCity = raw.destinationCity || raw.recipient?.city || raw.recipient?.address || 'Destination';

  return {
    id: raw.id || raw.trackingRef || `pcl-${Math.random().toString(36).slice(2, 9)}`,
    trackingRef: raw.trackingRef || raw.id || 'ZTR-UNKNOWN',
    status: normStatus,
    rawStatus: raw.rawStatus || raw.status,
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
      description: raw.description || raw.parcel?.description || 'Parcel',
      weightKg:
        typeof raw.parcel?.weightKg === 'number'
          ? raw.parcel.weightKg
          : typeof raw.weightKg === 'number'
          ? raw.weightKg
          : 1.0,
      category: raw.category || raw.parcel?.category || 'Standard',
      size: raw.size || raw.parcel?.size,
    },
    fee,
    formattedFee: raw.formattedFee || (fee > 0 ? formatMoney(fee) : ''),
    createdAt,
    formattedCreatedAt: raw.formattedCreatedAt || formatDate(createdAt),
    estimatedDeliveryDate: raw.estimatedDeliveryDate,
    formattedEstimatedDeliveryDate:
      raw.formattedEstimatedDeliveryDate ||
      (raw.estimatedDeliveryDate ? formatDate(raw.estimatedDeliveryDate) : undefined),
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
    timeline: (Array.isArray(raw.timeline) ? raw.timeline : []) as Parcel['timeline'],
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
  if (
    customerApiConfig.useMocks ||
    customerApiConfig.mode === 'mock' ||
    !customerApiConfig.allowLegacyListParcelsRealCall
  ) {
    return mockCustomerAdapter.listParcels(params);
  }
  const endpoint = customerApiConfig.deliveriesEndpoint || `${customerApiConfig.baseUrl}/parcels`;
  const { data } = await apiClient.get<unknown>(endpoint, { params });
  return normalizeListParcelsResponse(data);
}

export async function getParcel(idOrRef: string): Promise<Parcel | null> {
  // Tracking contract for customer portal remains on mock adapter in this slice.
  return mockCustomerAdapter.getParcel(idOrRef);
}

export function subscribeToParcel(
  idOrRef: string,
  callback: (parcel: Parcel) => void
): () => void {
  return mockCustomerAdapter.subscribeToParcel(idOrRef, callback);
}

export async function listReceipts(): Promise<Receipt[]> {
  return mockCustomerAdapter.listReceipts();
}

export async function askAssistant(question: string): Promise<AssistantResponse> {
  return mockCustomerAdapter.askAssistant(question);
}

export async function sendSupportMessage(
  payload: SupportMessagePayload
): Promise<SupportTicket> {
  return mockCustomerAdapter.sendSupportMessage(payload);
}
