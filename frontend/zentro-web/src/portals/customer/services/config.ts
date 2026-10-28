export interface CustomerApiConfig {
  useMocks: boolean;
  baseUrl: string;
  deliveriesEndpoint?: string;
}

/**
 * Single configuration switch for customer portal data layer.
 * When useMocks is true, calls route to the mock adapter.
 * When false, calls use the real API baseUrl.
 */
export const customerApiConfig: CustomerApiConfig = {
  // Default to mock adapter until backend API contracts for issue #4 are finalized.
  // Can be overridden via VITE_USE_CUSTOMER_MOCKS or VITE_API_BASE_URL.
  useMocks: import.meta.env.VITE_USE_CUSTOMER_MOCKS !== 'false',
  baseUrl: import.meta.env.VITE_CUSTOMER_API_BASE_URL || '/api/v1/customer',
  deliveriesEndpoint: import.meta.env.VITE_CUSTOMER_DELIVERIES_ENDPOINT || undefined,
};

export function setCustomerApiConfig(config: Partial<CustomerApiConfig>): void {
  Object.assign(customerApiConfig, config);
}
