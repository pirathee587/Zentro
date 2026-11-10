import { describe, expect, it } from 'vitest';
import { principalCacheScope, queryKeys } from './queryKeys';

describe('query key factories', () => {
  it('produce stable serializable domain/resource/params keys', () => {
    const left = queryKeys.dispatcher.queue({ from: '2026-01-01', sort: 'oldest' });
    const right = queryKeys.dispatcher.queue({ sort: 'oldest', from: '2026-01-01' });
    expect(left.slice(0, 2)).toEqual(['dispatcher', 'queue']);
    expect(JSON.stringify(left)).toBe(JSON.stringify(right));
    expect(() => JSON.stringify(queryKeys.customer.parcels({ page: 2 }))).not.toThrow();
  });

  it('separates resources and parameter values', () => {
    expect(queryKeys.dispatcher.queue({ sort: 'oldest' })).not.toEqual(queryKeys.dispatcher.workload({ sort: 'oldest' }));
    expect(queryKeys.dispatcher.queue({ sort: 'oldest' })).not.toEqual(queryKeys.dispatcher.queue({ sort: 'newest' }));
  });

  it('keeps credentials and personal fields out of key factories', () => {
    const serialized = JSON.stringify([
      queryKeys.courier.deliveries(principalCacheScope('private-user-id')),
      queryKeys.merchant.orders({ limit: 10 }, principalCacheScope('private-user-id')),
    ]);
    expect(serialized).not.toMatch(/private-user-id|token|phone|address|email/i);
    expect(principalCacheScope('private-user-id')).toBe(principalCacheScope('private-user-id'));
  });
});
