import { graphqlQuery, GraphqlRequestError } from '@/shared/api/graphqlClient';
import { normalizeParcelStatus, formatDate } from './formatters';
import type { DashboardData, Parcel, FeeEstimateRequest, FeeEstimateResponse } from './types';

interface Party { name: string; phone: string; address: string; city: string | null }
interface Event { id: string; status: string; title: string | null; description: string | null; occurredAt: string }
interface AssignedCourier {
  name: string; phone?: string | null; vehicle?: string | null; zone?: string | null;
  assignmentStatus: string; assignedAt?: string | null; respondedAt?: string | null;
}
interface Delivery {
  id: string; trackingRef: string; status: string; rawStatus: string; category: string;
  size: string | null; description: string | null; weightKg: number | null; destinationType?: string | null; isExpress: boolean;
  sender: Party; recipient: Party; fee: number; currency: string; createdAt: string;
  deliveredAt: string | null; estimatedDeliveryDate: string | null; failureReason: string | null;
  courier?: AssignedCourier | null; events: Event[];
}
interface Money { currency: string; amount: number }
interface Summary {
  activeCount: number; deliveredCount: number; awaitingPickupCount: number; failedCount: number;
  inTransitCount: number; totalParcels: number; monthlyFees: Money[]; totalFees: Money[];
}
export interface DashboardTrackingResult {
  trackingRef: string; status: Parcel['status']; sender: { city: string }; recipient: { city: string };
}
const fields = `id trackingRef status rawStatus category size description weightKg destinationType isExpress fee currency createdAt
  deliveredAt estimatedDeliveryDate failureReason sender { name phone address city }
  recipient { name phone address city } events { id status title description occurredAt }`;
const fieldsWithCourier = `${fields} courier { name phone vehicle zone assignmentStatus assignedAt respondedAt }`;
export const DASHBOARD_SUMMARY_QUERY = `query CustomerDashboardSummary {
  customerDashboardSummary { activeCount deliveredCount awaitingPickupCount failedCount inTransitCount totalParcels
    monthlyFees { currency amount } totalFees { currency amount } }
}`;
export const DASHBOARD_HISTORY_QUERY = `query CustomerDashboardHistory($page: Int!, $size: Int!) {
  myDeliveries(page: $page, size: $size, customerOnly: true) { items { ${fields} } pageInfo { page size totalElements totalPages hasNext } }
}`;
export const DASHBOARD_LIVE_QUERY = `query CustomerDashboardLive { customerLiveDelivery { ${fieldsWithCourier} } }`;
export const DASHBOARD_TRACK_QUERY = `query CustomerDashboardTrack($trackingRef: String!) {
  publicTracking(trackingRef: $trackingRef) { trackingRef status originCity destinationCity }
}`;
export const DASHBOARD_FEE_QUERY = `query CustomerDashboardFee($pickupCity: String, $deliveryCity: String,
  $weightKg: Float!, $destinationType: String!, $parcelCategory: String) {
  dashboardFeeEstimate(pickupCity: $pickupCity, deliveryCity: $deliveryCity, weightKg: $weightKg,
    destinationType: $destinationType, parcelCategory: $parcelCategory) {
    fee formattedFee currency estimatedDays breakdown { baseFee weightFee distanceSurcharge expressFee }
  }
}`;

const amount = (value: number, currency: string) => `${currency} ${new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(value)}`;
const money = (values: Money[]) => values.length ? values.map(value => amount(value.amount, value.currency)).join(' / ') : 'No spending recorded';

function parcel(value: Delivery): Parcel {
  return {
    id: value.id, trackingRef: value.trackingRef, status: normalizeParcelStatus(value.status), rawStatus: value.rawStatus, destinationType: value.destinationType || undefined, isExpress: value.isExpress,
    sender: { ...value.sender, city: value.sender.city || '' }, recipient: { ...value.recipient, city: value.recipient.city || '' },
    parcel: { description: value.description || '', category: value.category, weightKg: value.weightKg ?? undefined, size: value.size ?? undefined },
    fee: value.fee, formattedFee: amount(value.fee, value.currency), createdAt: value.createdAt,
    formattedCreatedAt: formatDate(value.createdAt), deliveredAt: value.deliveredAt || undefined,
    formattedDeliveredAt: value.deliveredAt ? formatDate(value.deliveredAt) : undefined,
    estimatedDeliveryDate: value.estimatedDeliveryDate || undefined,
    formattedEstimatedDeliveryDate: value.estimatedDeliveryDate
      ? new Intl.DateTimeFormat('en-LK', { dateStyle: 'medium', timeZone: 'Asia/Colombo' }).format(new Date(value.estimatedDeliveryDate)) : undefined,
    failureReason: value.failureReason || undefined,
    courier: value.courier && value.courier.assignmentStatus === 'ACCEPTED'
      ? {
          name: value.courier.name,
          phone: value.courier.phone || undefined,
          vehicle: value.courier.vehicle || 'Assigned Courier',
          zone: value.courier.zone || undefined,
        }
      : undefined,
    timeline: value.events.map((event, index) => ({ id: event.id, title: event.title || event.status,
      description: event.description || undefined, timestamp: event.occurredAt,
      status: index === value.events.length - 1 ? 'current' : 'completed' })),
  };
}

export async function getCustomerDelivery(trackingRef: string, signal?: AbortSignal): Promise<Parcel | null> {
  const ref=trackingRef.trim().toUpperCase();
  if (!/^[A-Za-z0-9_-]{6,32}$/.test(ref)) throw new Error('Enter a valid tracking reference (6–32 letters, numbers, hyphens or underscores).');
  try {
    const data=await graphqlQuery<{customerDelivery:Delivery|null}>(
      `query CustomerDelivery($trackingRef: String!) { customerDelivery(trackingRef:$trackingRef) { ${fieldsWithCourier} } }`,
      {trackingRef:ref},signal);
    return data.customerDelivery?parcel(data.customerDelivery):null;
  } catch(error) {
    if(error instanceof GraphqlRequestError && error.classification==='NOT_FOUND') return null;
    throw error;
  }
}

export async function getDashboardLiveParcel(signal?: AbortSignal): Promise<Parcel | null> {
  const data = await graphqlQuery<{ customerLiveDelivery: Delivery | null }>(DASHBOARD_LIVE_QUERY, {}, signal);
  return data.customerLiveDelivery ? parcel(data.customerLiveDelivery) : null;
}

export async function getDashboardData(signal?: AbortSignal): Promise<DashboardData> {
  const [summary, history, liveParcel] = await Promise.all([
    graphqlQuery<{ customerDashboardSummary: Summary }>(DASHBOARD_SUMMARY_QUERY, {}, signal),
    graphqlQuery<{ myDeliveries: { items: Delivery[] } }>(DASHBOARD_HISTORY_QUERY, { page: 1, size: 4 }, signal),
    getDashboardLiveParcel(signal),
  ]);
  const s = summary.customerDashboardSummary;
  const liveCourier = liveParcel?.courier
    ? {
        name: liveParcel.courier.name,
        initials: liveParcel.courier.name.split(/\s+/).filter(Boolean).slice(0, 2).map(p => p[0]?.toUpperCase() || '').join('') || 'C',
        role: liveParcel.courier.vehicle || 'Assigned Courier',
        zone: liveParcel.courier.zone || 'Active route',
        phone: liveParcel.courier.phone || '',
      }
    : undefined;
  return { ...s, greeting: 'Welcome back', recentParcels: history.myDeliveries.items.map(parcel), liveParcel,
    courier: liveCourier,
    // Currencies remain separated in display; numeric legacy fields are used only for single-currency callers.
    totalSpent: s.totalFees.length === 1 ? s.totalFees[0].amount : null,
    monthlySpent: s.monthlyFees.length === 1 ? s.monthlyFees[0].amount : null,
    formattedTotalSpent: money(s.totalFees), formattedMonthlySpent: money(s.monthlyFees), notifications: [],
    unavailable: { courier: 'Courier information is unavailable.', notifications: 'Customer notifications are not available yet.' } };
}

export async function getDashboardTracking(trackingRef: string, signal?: AbortSignal): Promise<DashboardTrackingResult | null> {
  const ref = trackingRef.trim().toUpperCase();
  if (!/^[A-Za-z0-9_-]{6,32}$/.test(ref)) throw new Error('Enter a valid tracking reference (6–32 letters, numbers, hyphens or underscores).');
  try {
    const data = await graphqlQuery<{ publicTracking: { trackingRef: string; status: string; originCity: string | null; destinationCity: string | null } | null }>(DASHBOARD_TRACK_QUERY, { trackingRef: ref }, signal);
    const p = data.publicTracking;
    return p ? { trackingRef: p.trackingRef, status: normalizeParcelStatus(p.status), sender: { city: p.originCity || 'Unavailable' }, recipient: { city: p.destinationCity || 'Unavailable' } } : null;
  } catch (error) {
    if (error instanceof GraphqlRequestError && error.classification === 'NOT_FOUND') return null;
    throw error;
  }
}

export async function estimateDashboardFee(payload: FeeEstimateRequest, signal?: AbortSignal): Promise<FeeEstimateResponse> {
  const data = await graphqlQuery<{ dashboardFeeEstimate: FeeEstimateResponse }>(DASHBOARD_FEE_QUERY, {
    pickupCity: payload.pickupCity, deliveryCity: payload.deliveryCity, weightKg: payload.weightKg,
    destinationType: payload.destinationType, parcelCategory: payload.parcelCategory,
  }, signal);
  return data.dashboardFeeEstimate;
}
