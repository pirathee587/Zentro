import { apiClient, withBearerToken } from '@/shared/api/apiClient';

export interface DailyDeliveryRow {
  day: string;
  created: number;
  delivered: number;
  failed: number;
  inTransit: number;
  successRate: number | null;
}

export interface DailyDeliveryReport {
  from: string;
  to: string;
  timezone: string;
  days: DailyDeliveryRow[];
}

export interface DeliverySummaryReport {
  from: string;
  to: string;
  timezone: string;
  created: number;
  delivered: number;
  failed: number;
  rate: number | null;
}

export async function getDeliverySummary(
  from: string,
  to: string,
  token?: string,
  signal?: AbortSignal,
): Promise<DeliverySummaryReport> {
  const params = { from, to };
  const res = await apiClient.get<DeliverySummaryReport>('/api/v1/reports/deliveries/summary', {
    params,
    signal,
    ...withBearerToken(token),
  });
  return res.data;
}

export async function getDailyDeliveries(
  from: string,
  to: string,
  token?: string,
  signal?: AbortSignal,
): Promise<DailyDeliveryReport> {
  const params = { from, to };
  const res = await apiClient.get<DailyDeliveryReport>('/api/v1/reports/deliveries/daily', {
    params,
    signal,
    ...withBearerToken(token),
  });
  return res.data;
}
