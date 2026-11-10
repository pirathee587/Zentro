/**
 * Typed pricing API helpers for the Dispatcher portal (S2 Parcel & Pricing, through the gateway).
 *
 * Backend contracts:
 *   GET  /api/v1/admin/pricing-rules   – current pricing rule (DISPATCHER only)
 *   PUT  /api/v1/admin/pricing-rules   – update the pricing rule (DISPATCHER only)
 *   GET  /api/v1/zones                 – active delivery zones (any signed-in role, read-only)
 *   POST /api/v1/parcels/estimate      – fee quotation (CUSTOMER or DISPATCHER), see docs/api/price-quotation.md
 *
 * The backend is the security boundary and the only place prices are calculated. These helpers only map
 * responses to typed values; amounts are read as numbers and never recalculated here.
 */

import axios from 'axios';
import { apiClient, friendlyApiError } from '@/shared/api/apiClient';

// ─── Contracts ────────────────────────────────────────────────────────────────

/** The editable amounts of a pricing rule, in display order. */
export const PRICING_RULE_FIELDS = [
  'baseFee',
  'includedWeightKg',
  'additionalWeightRatePerKg',
  'distanceSurchargeOtherDistrict',
  'expressSurcharge',
  'perKmRate',
] as const;

export type PricingRuleField = (typeof PRICING_RULE_FIELDS)[number];

export type PricingRule = Record<PricingRuleField, number> & {
  id: string;
  ruleName: string;
  currency: string;
  active: boolean;
  updatedAt: string | null;
};

/** Body of PUT /api/v1/admin/pricing-rules: every amount required and >= 0.00. */
export type PricingRuleUpdateRequest = Record<PricingRuleField, number> & {
  currency: string;
};

export type DeliveryZone = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
};

export type DestinationType = 'SAME_CITY' | 'OTHER_DISTRICT';
export type ParcelSize = 'SMALL' | 'MEDIUM' | 'LARGE';

/** The subset of the quotation request the Dispatcher preview sends. */
export type FeeEstimateRequest = {
  destinationType: DestinationType;
  parcelSize: ParcelSize;
  weightKg: number;
  isExpress: boolean;
  originZone?: string;
  destinationZone?: string;
  currency: string;
};

/** Breakdown lines; distanceSurcharge and expressFee are omitted by the API when they are zero. */
export type FeeBreakdown = {
  baseFee: number;
  weightFee: number;
  distanceSurcharge?: number;
  expressFee?: number;
};

export type FeeEstimateResponse = {
  fee: number;
  formattedFee: string;
  currency: string;
  estimatedDays: number;
  breakdown: FeeBreakdown;
};

// ─── Errors ───────────────────────────────────────────────────────────────────

export type PricingErrorKind =
  | 'validation'
  | 'unauthenticated'
  | 'forbidden'
  | 'unavailable'
  | 'network'
  | 'unknown';

/** One error shape for the UI: a message to show and, for validation errors, messages per field. */
export type PricingError = {
  kind: PricingErrorKind;
  message: string;
  fieldErrors: Record<string, string>;
  status?: number;
};

type ProblemBody = {
  detail?: unknown;
  invalidFields?: unknown;
  fieldErrors?: unknown;
};

function stringRecord(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).filter(
      (entry): entry is [string, string] => typeof entry[1] === 'string' && entry[1].trim() !== '',
    ),
  );
}

/**
 * Turns any error from these helpers into a {@link PricingError}:
 * 400 with invalidFields/fieldErrors (or only detail) -> validation; 401 -> unauthenticated;
 * 403 -> forbidden; 5xx -> unavailable; no response -> network.
 */
export function toPricingError(error: unknown, fallback: string): PricingError {
  if (!axios.isAxiosError(error)) {
    return { kind: 'unknown', message: fallback, fieldErrors: {} };
  }
  const status = error.response?.status;
  if (!error.response) {
    return { kind: 'network', message: friendlyApiError(error, fallback), fieldErrors: {} };
  }
  if (status === 401) {
    return { kind: 'unauthenticated', message: friendlyApiError(error, fallback), fieldErrors: {}, status };
  }
  if (status === 403) {
    return { kind: 'forbidden', message: friendlyApiError(error, fallback), fieldErrors: {}, status };
  }
  const body = (error.response.data ?? {}) as ProblemBody;
  const detail = typeof body.detail === 'string' && body.detail.trim() !== '' ? body.detail : undefined;
  if (status === 400) {
    const fieldErrors = {
      ...stringRecord(body.fieldErrors),
      ...stringRecord(body.invalidFields),
    };
    return {
      kind: 'validation',
      message: detail ?? Object.values(fieldErrors)[0] ?? 'Some values were not accepted. Check the highlighted fields.',
      fieldErrors,
      status,
    };
  }
  if (status !== undefined && status >= 500) {
    return {
      kind: 'unavailable',
      message: detail ?? 'Pricing is temporarily unavailable. Please try again.',
      fieldErrors: {},
      status,
    };
  }
  return { kind: 'unknown', message: fallback, fieldErrors: {}, status };
}

// ─── Response mapping ─────────────────────────────────────────────────────────

function amount(value: unknown, field: string): number {
  const parsed = typeof value === 'string' ? Number(value) : value;
  if (typeof parsed !== 'number' || !Number.isFinite(parsed)) {
    throw new Error(`Pricing response has an invalid ${field}.`);
  }
  return parsed;
}

function optionalAmount(value: unknown, field: string): number | undefined {
  return value === undefined || value === null ? undefined : amount(value, field);
}

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

export function toPricingRule(raw: Record<string, unknown>): PricingRule {
  const amounts = Object.fromEntries(
    PRICING_RULE_FIELDS.map((field) => [field, amount(raw[field], field)]),
  ) as Record<PricingRuleField, number>;
  return {
    ...amounts,
    id: text(raw.id),
    ruleName: text(raw.ruleName),
    currency: text(raw.currency, 'LKR') || 'LKR',
    active: raw.active !== false,
    updatedAt: typeof raw.updatedAt === 'string' ? raw.updatedAt : null,
  };
}

function toZone(raw: Record<string, unknown>): DeliveryZone {
  return {
    id: text(raw.id),
    code: text(raw.code),
    name: text(raw.name),
    description: typeof raw.description === 'string' && raw.description.trim() !== '' ? raw.description : null,
    active: raw.active !== false,
  };
}

function toEstimate(raw: Record<string, unknown>): FeeEstimateResponse {
  const breakdown = (raw.breakdown ?? {}) as Record<string, unknown>;
  const distanceSurcharge = optionalAmount(breakdown.distanceSurcharge, 'distanceSurcharge');
  const expressFee = optionalAmount(breakdown.expressFee, 'expressFee');
  return {
    fee: amount(raw.fee, 'fee'),
    formattedFee: text(raw.formattedFee),
    currency: text(raw.currency, 'LKR') || 'LKR',
    estimatedDays: amount(raw.estimatedDays, 'estimatedDays'),
    breakdown: {
      baseFee: amount(breakdown.baseFee, 'baseFee'),
      weightFee: amount(breakdown.weightFee, 'weightFee'),
      ...(distanceSurcharge !== undefined ? { distanceSurcharge } : {}),
      ...(expressFee !== undefined ? { expressFee } : {}),
    },
  };
}

// ─── Endpoints ────────────────────────────────────────────────────────────────

export const PRICING_RULES_PATH = '/api/v1/admin/pricing-rules';
export const ZONES_PATH = '/api/v1/zones';
export const ESTIMATE_PATH = '/api/v1/parcels/estimate';

export async function getPricingRule(signal?: AbortSignal): Promise<PricingRule> {
  return toPricingRule((await apiClient.get<Record<string, unknown>>(PRICING_RULES_PATH, { signal })).data);
}

export async function updatePricingRule(request: PricingRuleUpdateRequest): Promise<PricingRule> {
  return toPricingRule((await apiClient.put<Record<string, unknown>>(PRICING_RULES_PATH, request)).data);
}

export async function getZones(signal?: AbortSignal): Promise<DeliveryZone[]> {
  const data = (await apiClient.get<unknown>(ZONES_PATH, { signal })).data;
  return Array.isArray(data) ? data.map((zone) => toZone(zone as Record<string, unknown>)) : [];
}

export async function estimateFee(request: FeeEstimateRequest): Promise<FeeEstimateResponse> {
  return toEstimate((await apiClient.post<Record<string, unknown>>(ESTIMATE_PATH, request)).data);
}
