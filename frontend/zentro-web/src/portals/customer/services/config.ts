export type CustomerApiMode = 'mock' | 'real';

export interface CustomerApiConfig {
  mode: CustomerApiMode;
  useMocks: boolean;
  baseUrl: string;
  parcelsPath: string;
  deliveriesEndpoint?: string;
  /** Internal flag set when a test explicitly overrides baseUrl/deliveriesEndpoint for listParcels. */
  allowLegacyListParcelsRealCall?: boolean;
}

const initialUseMocks =
  import.meta.env.VITE_CUSTOMER_API_MODE === 'real'
    ? false
    : import.meta.env.VITE_USE_CUSTOMER_MOCKS !== 'false';

/**
 * Configuration switch for the customer portal data layer.
 * When mode is 'real' (useMocks is false), approved S2 endpoints
 * (estimateFee -> POST /api/v1/parcels/estimate, createDelivery -> POST /api/v1/parcels)
 * call the real API Gateway via apiClient, while unapproved customer endpoints stay on the mock adapter.
 */
export const customerApiConfig: CustomerApiConfig = {
  mode: initialUseMocks ? 'mock' : 'real',
  useMocks: initialUseMocks,
  baseUrl: import.meta.env.VITE_CUSTOMER_API_BASE_URL || '/api/v1/customer',
  parcelsPath: import.meta.env.VITE_PARCELS_API_PATH || '/api/v1/parcels',
  deliveriesEndpoint: import.meta.env.VITE_CUSTOMER_DELIVERIES_ENDPOINT || undefined,
  allowLegacyListParcelsRealCall: false,
};

export function setCustomerApiConfig(config: Partial<CustomerApiConfig>): void {
  Object.assign(customerApiConfig, config);
  if (config.mode !== undefined && config.useMocks === undefined) {
    customerApiConfig.useMocks = config.mode === 'mock';
  } else if (config.useMocks !== undefined && config.mode === undefined) {
    customerApiConfig.mode = config.useMocks ? 'mock' : 'real';
  }
  if (customerApiConfig.useMocks) {
    customerApiConfig.allowLegacyListParcelsRealCall = false;
  } else if (config.baseUrl !== undefined || config.deliveriesEndpoint !== undefined) {
    customerApiConfig.allowLegacyListParcelsRealCall = true;
  } else if (config.allowLegacyListParcelsRealCall === undefined) {
    customerApiConfig.allowLegacyListParcelsRealCall = false;
  }
}
