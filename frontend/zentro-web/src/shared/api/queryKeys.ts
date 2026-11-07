type QueryParams = Record<string, string | number | boolean | undefined>;
const stableParams = (params?: QueryParams) => Object.fromEntries(
  Object.entries(params ?? {}).sort(([left], [right]) => left.localeCompare(right)),
);

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
    queue: (params?: QueryParams) => params === undefined ? ['dispatcher', 'queue'] as const : ['dispatcher', 'queue', stableParams(params)] as const,
    workload: (params?: QueryParams) => params === undefined ? ['dispatcher', 'workload'] as const : ['dispatcher', 'workload', stableParams(params)] as const,
  },
  courier: {
    deliveries: () => ['courier', 'deliveries'] as const,
    delivery: (deliveryId: string) => ['courier', 'delivery', { deliveryId }] as const,
    proof: (deliveryId: string) => ['courier', 'proof', { deliveryId }] as const,
    profile: () => ['courier', 'profile'] as const,
  },
  merchant: {
    application: () => ['merchant', 'application'] as const,
    apiKey: () => ['merchant', 'api-key'] as const,
    orders: (params?: QueryParams) => params === undefined ? ['merchant', 'orders'] as const : ['merchant', 'orders', stableParams(params)] as const,
  },
} as const;
