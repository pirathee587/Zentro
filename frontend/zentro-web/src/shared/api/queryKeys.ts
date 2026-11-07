type QueryParams = Record<string, string | number | boolean | undefined>;
const stableParams = (params?: QueryParams) => Object.fromEntries(
  Object.entries(params ?? {}).sort(([left], [right]) => left.localeCompare(right)),
);

const principalNamespaces = new Map<string, string>();
let nextPrincipalNamespace = 0;

/** Return an opaque, process-local cache namespace without placing identity data in query keys. */
export function principalCacheScope(principalId?: string): string {
  if (!principalId) return 'anonymous';
  let namespace = principalNamespaces.get(principalId);
  if (!namespace) {
    namespace = `account-${++nextPrincipalNamespace}`;
    principalNamespaces.set(principalId, namespace);
  }
  return namespace;
}

export const queryKeys = {
  customer: {
    parcels: (params?: QueryParams) => params === undefined ? ['customer', 'parcels'] as const : ['customer', 'parcels', stableParams(params)] as const,
    dashboard: () => ['customer', 'dashboard'] as const,
    trackingMine: () => ['customer', 'tracking', 'mine'] as const,
  },
  tracking: {
    parcel: (trackingRef: string) => ['tracking', 'parcel', { trackingRef }] as const,
  },
  dispatcher: {
    queue: (params?: QueryParams, scope?: string) => params === undefined && scope === undefined ? ['dispatcher', 'queue'] as const : ['dispatcher', 'queue', stableParams({ ...params, ...(scope ? { scope } : {}) })] as const,
    workload: (params?: QueryParams, scope?: string) => params === undefined && scope === undefined ? ['dispatcher', 'workload'] as const : ['dispatcher', 'workload', stableParams({ ...params, ...(scope ? { scope } : {}) })] as const,
  },
  courier: {
    deliveries: (scope?: string) => scope ? ['courier', 'deliveries', { scope }] as const : ['courier', 'deliveries'] as const,
    delivery: (deliveryId: string, scope?: string) => ['courier', 'delivery', stableParams({ deliveryId, scope })] as const,
    proof: (deliveryId: string, scope?: string) => ['courier', 'proof', stableParams({ deliveryId, scope })] as const,
    profile: (scope?: string) => scope ? ['courier', 'profile', { scope }] as const : ['courier', 'profile'] as const,
  },
  merchant: {
    application: (scope?: string) => scope ? ['merchant', 'application', { scope }] as const : ['merchant', 'application'] as const,
    apiKey: (scope?: string) => scope ? ['merchant', 'api-key', { scope }] as const : ['merchant', 'api-key'] as const,
    orders: (params?: QueryParams, scope?: string) => params === undefined && scope === undefined ? ['merchant', 'orders'] as const : ['merchant', 'orders', stableParams({ ...params, ...(scope ? { scope } : {}) })] as const,
  },
} as const;
