import { describe, expect, it, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { queryKeys } from './queryKeys';
import { invalidateAfterBooking, invalidateAfterCourierProof, invalidateAfterDispatchAssignment } from './queryInvalidation';

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe('mutation invalidation matrix', () => {
  it('booking invalidates customer parcels, dashboard, and tracking-mine only', async () => {
    const client = makeClient();
    client.setQueryData(queryKeys.customer.parcels({ page: 1 }), []);
    client.setQueryData(queryKeys.customer.dashboard(), {});
    client.setQueryData(queryKeys.customer.trackingMine(), []);
    client.setQueryData(queryKeys.merchant.orders(), []);
    await invalidateAfterBooking(client);
    expect(client.getQueryState(queryKeys.customer.parcels({ page: 1 }))?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.customer.dashboard())?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.customer.trackingMine())?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.merchant.orders())?.isInvalidated).toBe(false);
    client.clear();
  });

  it('dispatcher assignment invalidates queue and workload only', async () => {
    const client = makeClient();
    client.setQueryData(queryKeys.dispatcher.queue({ sort: 'newest' }), []);
    client.setQueryData(queryKeys.dispatcher.workload({ search: 'courier' }), []);
    client.setQueryData(queryKeys.customer.dashboard(), {});
    await invalidateAfterDispatchAssignment(client);
    expect(client.getQueryState(queryKeys.dispatcher.queue({ sort: 'newest' }))?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.dispatcher.workload({ search: 'courier' }))?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.customer.dashboard())?.isInvalidated).toBe(false);
    client.clear();
  });

  it('courier proof invalidates its delivery and delivery list', async () => {
    const client = makeClient();
    client.setQueryData(queryKeys.courier.deliveries(), []);
    client.setQueryData(queryKeys.courier.delivery('delivery-1'), {});
    client.setQueryData(queryKeys.courier.delivery('delivery-2'), {});
    await invalidateAfterCourierProof(client, 'delivery-1');
    expect(client.getQueryState(queryKeys.courier.deliveries())?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.courier.delivery('delivery-1'))?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.courier.delivery('delivery-2'))?.isInvalidated).toBe(false);
    client.clear();
  });

  it('failed writes do not invoke success invalidations', async () => {
    const client = makeClient();
    client.setQueryData(queryKeys.customer.parcels(), []);
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    await expect(Promise.reject(new Error('booking failed')).then(() => invalidateAfterBooking(client))).rejects.toThrow('booking failed');
    expect(invalidate).not.toHaveBeenCalled();
    client.clear();
  });
});
