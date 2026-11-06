import axios from 'axios';
import { apiClient } from '@/shared/api/apiClient';

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
};

export type IssuedMerchantApiKey = {
  secret: string;
  credential: MerchantApiKeyMetadata;
};

export type MerchantApiAuth = {
  accessToken?: string;
  refreshAccessToken?: (force?: boolean) => Promise<string | undefined>;
};

const MERCHANT_APPLICATION_PATH = '/api/v1/merchants/me';
const MERCHANTS_PATH = '/api/v1/merchants';
const MERCHANT_API_KEY_PATH = '/api/v1/merchants/api-key';
const MERCHANT_ORDERS_PATH = '/api/v1/merchant/orders';

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

  try {
    return await request(await getToken());
  } catch (error) {
    if (!axios.isAxiosError(error) || error.response?.status !== 401 || !auth.refreshAccessToken) {
      throw error;
    }

    return request(await getToken(true));
  }
}

function authorization(accessToken: string) {
  return { headers: { Authorization: `Bearer ${accessToken}` } };
}

export function getMerchantApplication(auth: MerchantApiAuth) {
  return withMerchantAuth(auth, async (token) =>
    (await apiClient.get<MerchantApplication>(MERCHANT_APPLICATION_PATH, authorization(token))).data,
  );
}

export function getMerchantOrders(auth: MerchantApiAuth, limit = 50) {
  return withMerchantAuth(auth, async (token) =>
    (await apiClient.get<MerchantOrder[]>(MERCHANT_ORDERS_PATH, {
      ...authorization(token),
      params: { limit },
    })).data,
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
  return withMerchantAuth(auth, async (token) =>
    (await apiClient.get<MerchantApiKeyMetadata | null>(MERCHANT_API_KEY_PATH, authorization(token))).data,
  );
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
