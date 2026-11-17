import type { CourierDashboard, DashboardDelivery } from '../../api/courierDashboardApi';

export function dashboardDelivery(overrides: Partial<DashboardDelivery> = {}): DashboardDelivery {
  return { parcelId: '11111111-1111-4111-8111-111111111111', assignedAt: '2026-11-04T10:00:00Z',
    assignmentState: 'ASSIGNED', projectionAvailable: true, trackingRef: null, status: 'IN_TRANSIT',
    recipient: null, weightKg: null, size: null, failureReason: null, proofId: null, completionState: null, ...overrides };
}
export function dashboardFixture(deliveries: DashboardDelivery[] = [dashboardDelivery()], overrides: Partial<CourierDashboard> = {}): CourierDashboard {
  const delivered = deliveries.filter(row => row.status === 'DELIVERED').length;
  const failed = deliveries.filter(row => row.status === 'FAILED').length;
  return { businessDate: '2026-11-16', timeZone: 'Asia/Colombo',
    counts: { assigned: deliveries.length, delivered, failed, remaining: deliveries.length - delivered - failed },
    currentDelivery: null, deliveries,
    pageInfo: { page: 1, size: 10, totalElements: deliveries.length, totalPages: deliveries.length ? 1 : 0, hasNext: false, hasPrevious: false }, ...overrides };
}
export const dashboardEnvelope = (deliveries?: DashboardDelivery[]) => ({ data: { courierTodayDashboard: dashboardFixture(deliveries) } });
