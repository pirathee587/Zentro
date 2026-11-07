import type { QueryClient } from '@tanstack/react-query';
import { queryKeys } from './queryKeys';

export function invalidateAfterBooking(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.customer.parcels() }),
    client.invalidateQueries({ queryKey: queryKeys.customer.dashboard() }),
    client.invalidateQueries({ queryKey: queryKeys.customer.trackingMine() }),
  ]);
}

export function invalidateAfterDispatchAssignment(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.dispatcher.queue() }),
    client.invalidateQueries({ queryKey: queryKeys.dispatcher.workload() }),
  ]);
}

export function invalidateAfterCourierProof(client: QueryClient, deliveryId: string) {
  return Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.courier.deliveries() }),
    client.invalidateQueries({ queryKey: queryKeys.courier.delivery(deliveryId) }),
  ]);
}
