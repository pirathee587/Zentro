export interface CustomerApiConfig {
  parcelsPath: string;
}

export const customerApiConfig: CustomerApiConfig = {
  parcelsPath: import.meta.env.VITE_PARCELS_API_PATH || '/api/v1/parcels',
};

export function setCustomerApiConfig(config: Partial<CustomerApiConfig>): void {
  Object.assign(customerApiConfig, config);
}
