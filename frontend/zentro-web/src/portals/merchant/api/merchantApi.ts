import { apiClient, withBearerToken } from '@/shared/api/apiClient';
import axios from 'axios';

export type MerchantApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type MerchantApplication = {
  businessName: string;
  status: MerchantApplicationStatus;
  createdAt?: string;
  reviewedAt?: string | null;
  rejectionReason?: string;
};

export type CreateMerchantApplicationRequest = {
  businessName: string;
  businessRegNo: string;
  contactPhone: string;
};

export type MerchantApiKeyMetadata = {
  id: string;
  prefix: string;
  createdAt: string;
  rotatedAt: string;
  revokedAt?: string | null;
  active: boolean;
};

export type MerchantOrderStatus =
  | 'CREATED'
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'FAILED';

export type MerchantOrderContact = {
  name: string;
  phone: string;
  address: string;
  city: string | null;
};

export type MerchantOrderParcel = {
  weightKg: number;
  category: string;
  size: 'SMALL' | 'MEDIUM' | 'LARGE' | null;
  description: string | null;
};

export type MerchantOrder = {
  id: string;
  trackingRef: string;
  externalOrderRef: string;
  merchantId: string;
  status: MerchantOrderStatus;
  fee: number;
  currency: string;
  estimatedDays: number;
  createdAt: string;
  sender: MerchantOrderContact;
  recipient: MerchantOrderContact;
  parcel: MerchantOrderParcel;
};
export type MerchantOrderSummary = { totalOrders: number; deliveredOrders: number; failedOrders: number; statusCounts: Record<string, number> };
export type MerchantBillingSummary = { month: string; orderCount: number; quotedFeeTotal: number; currency: string | null; orders: MerchantOrder[] };

export type IssuedMerchantApiKey = {
  secret: string;
  credential: MerchantApiKeyMetadata;
};

export type MerchantApiAuth = {
  accessToken?: string;
  refreshAccessToken?: (force?: boolean) => Promise<string | undefined>;
  signal?: AbortSignal;
};

const MERCHANT_APPLICATION_PATH = '/api/v1/merchants/me';
const MERCHANTS_PATH = '/api/v1/merchants';
const MERCHANT_API_KEY_PATH = '/api/v1/merchants/api-key';
const MERCHANT_ORDERS_PATH = '/api/v1/merchant/orders';
const MERCHANT_WEBHOOK_PATH = '/api/v1/merchant/webhooks';

export type MerchantWebhook = { id: string; url: string; active: boolean; secretPrefix: string; createdAt: string; updatedAt: string };
export type MerchantWebhookDelivery = { id: string; eventId: string; eventType: string; parcelId: string; status: string; attempts: number; responseStatus: number | null; lastError: string | null; nextAttemptAt: string | null; createdAt: string; updatedAt: string };
export type MerchantWebhookDeliveryPage = { items: MerchantWebhookDelivery[]; page: number; size: number; total: number };
export type MerchantWebhookSecret = { id: string; url: string; secret: string; secretPrefix: string; active: boolean };

async function withMerchantAuth<T>(
  auth: MerchantApiAuth,
  request: (accessToken: string) => Promise<T>,
): Promise<T> {
  const getToken = async (forceRefresh = false) => {
    const token = auth.refreshAccessToken
      ? await auth.refreshAccessToken(forceRefresh)
      : auth.accessToken;

    if (!token) throw new Error('Your sign-in session has ended. Please sign in again.');
    return token;
  };

  return request(await getToken());
}

function authorization(accessToken: string, signal?: AbortSignal) {
  return withBearerToken(accessToken, signal ? { signal } : {});
}

export function getMerchantApplication(auth: MerchantApiAuth) {
  return withMerchantAuth(auth, async (token) =>
    (await apiClient.get<MerchantApplication>(MERCHANT_APPLICATION_PATH, authorization(token, auth.signal))).data,
  );
}

export function getMerchantOrders(auth: MerchantApiAuth, limit = 50) {
  return withMerchantAuth(auth, async (token) =>
    (await apiClient.get<MerchantOrder[]>(MERCHANT_ORDERS_PATH, {
      ...authorization(token, auth.signal),
      params: { limit },
    })).data,
  );
}
export function getMerchantOrderSummary(auth: MerchantApiAuth) {
  return withMerchantAuth(auth, async token => (await apiClient.get<MerchantOrderSummary>(`${MERCHANT_ORDERS_PATH}/summary`, authorization(token, auth.signal))).data);
}
export function getMerchantBillingOrders(auth: MerchantApiAuth, month: string) {
  return withMerchantAuth(auth, async token => (await apiClient.get<MerchantBillingSummary>(`${MERCHANT_ORDERS_PATH}/billing`, { ...authorization(token, auth.signal), params: { month } })).data);
}

export function getMerchantWebhook(auth: MerchantApiAuth): Promise<MerchantWebhook | null> {
  return withMerchantAuth(auth, async token => {
    try {
      return (await apiClient.get<MerchantWebhook>(MERCHANT_WEBHOOK_PATH, authorization(token, auth.signal))).data;
    } catch (error) {
      // The service uses 404 to mean this merchant has not registered a webhook yet.
      if (axios.isAxiosError(error) && error.response?.status === 404) return null;
      throw error;
    }
  });
}
export function getMerchantWebhookDeliveries(auth: MerchantApiAuth, page = 0, size = 20) {
  return withMerchantAuth(auth, async token => (await apiClient.get<MerchantWebhookDeliveryPage>(`${MERCHANT_WEBHOOK_PATH}/deliveries`, { ...authorization(token, auth.signal), params: { page, size } })).data);
}
export function registerMerchantWebhook(auth: MerchantApiAuth, url: string) {
  return withMerchantAuth(auth, async token => (await apiClient.post<MerchantWebhookSecret>(MERCHANT_WEBHOOK_PATH, { url }, authorization(token))).data);
}
export function disableMerchantWebhook(auth: MerchantApiAuth) {
  return withMerchantAuth(auth, async token => apiClient.delete(MERCHANT_WEBHOOK_PATH, authorization(token)));
}
export function rotateMerchantWebhookSecret(auth: MerchantApiAuth) {
  return withMerchantAuth(auth, async token => (await apiClient.post<MerchantWebhookSecret>(`${MERCHANT_WEBHOOK_PATH}/secret`, undefined, authorization(token))).data);
}

export function isMerchantOrderId(orderId: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(orderId);
}

/** Read an owned order by its UUID, not its tracking or external reference. */
export async function getMerchantOrder(auth: MerchantApiAuth, orderId: string): Promise<MerchantOrder> {
  if (!isMerchantOrderId(orderId)) {
    throw new Error('A valid order UUID is required.');
  }

  return withMerchantAuth(auth, async (token) =>
    (await apiClient.get<MerchantOrder>(`${MERCHANT_ORDERS_PATH}/${orderId}`, authorization(token, auth.signal))).data,
  );
}

export function createMerchantApplication(
  auth: MerchantApiAuth,
  request: CreateMerchantApplicationRequest,
) {
  return withMerchantAuth(auth, async (token) =>
    (await apiClient.post<MerchantApplication>(MERCHANTS_PATH, request, authorization(token))).data,
  );
}

export function getMerchantApiKey(auth: MerchantApiAuth) {
  return withMerchantAuth(auth, async (token) => {
    try {
      return (await apiClient.get<MerchantApiKeyMetadata>(MERCHANT_API_KEY_PATH, authorization(token, auth.signal))).data;
    } catch (error) {
      // The identity service uses 404 when an approved merchant has not issued a key yet.
      if (axios.isAxiosError(error) && error.response?.status === 404) return null;
      throw error;
    }
  });
}

export function issueMerchantApiKey(auth: MerchantApiAuth) {
  return withMerchantAuth(auth, async (token) =>
    (await apiClient.post<IssuedMerchantApiKey>(MERCHANT_API_KEY_PATH, undefined, authorization(token))).data,
  );
}

export function revokeMerchantApiKey(auth: MerchantApiAuth) {
  return withMerchantAuth(auth, async (token) =>
    (await apiClient.delete<MerchantApiKeyMetadata | null>(MERCHANT_API_KEY_PATH, authorization(token))).data,
  );
}
