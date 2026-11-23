import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import {
  getMerchantApiKey,
  getMerchantApplication,
  getMerchantOrder,
  getMerchantOrders,
  getMerchantOrderSummary,
  getMerchantBillingOrders,
  isMerchantOrderId,
  issueMerchantApiKey,
  revokeMerchantApiKey,
  getMerchantWebhook,
  getMerchantWebhookDeliveries,
  registerMerchantWebhook,
  disableMerchantWebhook,
  rotateMerchantWebhookSecret,
} from '../api/merchantApi';
import type { MerchantApplication } from '../api/merchantApi';

export const merchantQueryKeys = {
  application: (userId?: string) => queryKeys.merchant.application(principalCacheScope(userId)),
  apiKey: (userId?: string) => queryKeys.merchant.apiKey(principalCacheScope(userId)),
  orders: (userId?: string) => queryKeys.merchant.orders(undefined, principalCacheScope(userId)),
  order: (orderId: string, userId?: string) => queryKeys.merchant.order(orderId, principalCacheScope(userId)),
  webhook: (userId?: string) => ['merchant', principalCacheScope(userId), 'webhook'],
  webhookDeliveries: (userId?: string) => ['merchant', principalCacheScope(userId), 'webhook-deliveries'],
  summary: (userId?: string) => ['merchant', principalCacheScope(userId), 'summary'],
  billing: (month: string, userId?: string) => ['merchant', principalCacheScope(userId), 'billing', month],
};

export function useMerchantWebhookQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();
  return useQuery({ queryKey: merchantQueryKeys.webhook(user?.id), enabled: Boolean(user?.accessToken) && enabled, retry: false, staleTime: 30_000,
    queryFn: ({ signal }) => getMerchantWebhook({ accessToken: user?.accessToken, refreshAccessToken, signal }) });
}

export function useMerchantOrderSummaryQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();
  return useQuery({ queryKey: merchantQueryKeys.summary(user?.id), enabled: Boolean(user?.accessToken) && enabled,
    queryFn: ({ signal }) => getMerchantOrderSummary({ accessToken: user?.accessToken, refreshAccessToken, signal }) });
}

export function useMerchantBillingQuery(month: string, enabled = true) {
  const { user, refreshAccessToken } = useAuth();
  return useQuery({ queryKey: merchantQueryKeys.billing(month, user?.id), enabled: Boolean(user?.accessToken) && enabled,
    queryFn: ({ signal }) => getMerchantBillingOrders({ accessToken: user?.accessToken, refreshAccessToken, signal }, month) });
}
export function useMerchantWebhookDeliveriesQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();
  return useQuery({ queryKey: merchantQueryKeys.webhookDeliveries(user?.id), enabled: Boolean(user?.accessToken) && enabled,
    queryFn: ({ signal }) => getMerchantWebhookDeliveries({ accessToken: user?.accessToken, refreshAccessToken, signal }) });
}
export function useRegisterMerchantWebhookMutation() {
  const { user, refreshAccessToken } = useAuth(); const client = useQueryClient();
  return useMutation({ mutationFn: (url: string) => registerMerchantWebhook({ accessToken: user?.accessToken, refreshAccessToken }, url), onSuccess: () => client.invalidateQueries({ queryKey: merchantQueryKeys.webhook(user?.id) }) });
}
export function useDisableMerchantWebhookMutation() {
  const { user, refreshAccessToken } = useAuth(); const client = useQueryClient();
  return useMutation({ mutationFn: () => disableMerchantWebhook({ accessToken: user?.accessToken, refreshAccessToken }), onSuccess: () => client.invalidateQueries({ queryKey: merchantQueryKeys.webhook(user?.id) }) });
}
export function useRotateMerchantWebhookSecretMutation() {
  const { user, refreshAccessToken } = useAuth(); const client = useQueryClient();
  return useMutation({
    mutationFn: () => rotateMerchantWebhookSecret({ accessToken: user?.accessToken, refreshAccessToken }),
    onSuccess: () => client.invalidateQueries({ queryKey: merchantQueryKeys.webhook(user?.id) }),
  });
}

export function useMerchantApplicationQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();

  return useQuery<MerchantApplication>({
    queryKey: merchantQueryKeys.application(user?.id),
    enabled: Boolean(user?.accessToken) && enabled,
    refetchInterval: (query) => {
      const data = query.state.data as MerchantApplication | undefined;
      return data?.status === 'PENDING' ? 3000 : false;
    },
    queryFn: ({ signal }) => getMerchantApplication({
      accessToken: user?.accessToken,
      refreshAccessToken,
      signal,
    }),
  });
}

export function useMerchantApiKeyQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();

  return useQuery({
    queryKey: merchantQueryKeys.apiKey(user?.id),
    enabled: Boolean(user?.accessToken) && enabled,
    queryFn: ({ signal }) => getMerchantApiKey({
      accessToken: user?.accessToken,
      refreshAccessToken,
      signal,
    }),
  });
}

export function useMerchantOrdersQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();

  return useQuery({
    queryKey: merchantQueryKeys.orders(user?.id),
    enabled: Boolean(user?.accessToken) && enabled,
    queryFn: ({ signal }) => getMerchantOrders({
      accessToken: user?.accessToken,
      refreshAccessToken,
      signal,
    }),
  });
}

export function useMerchantOrderQuery(orderId: string, enabled = true) {
  const { user, loading, refreshAccessToken } = useAuth();
  const authenticated = !loading && Boolean(user?.id && user.accessToken);

  return useQuery({
    queryKey: merchantQueryKeys.order(orderId, user?.id),
    enabled: authenticated && isMerchantOrderId(orderId) && enabled,
    queryFn: ({ signal }) => {
      // Also guard explicit refetches, which bypass the enabled option.
      if (!authenticated) throw new Error('Your sign-in session has ended. Please sign in again.');
      return getMerchantOrder({ accessToken: user?.accessToken, refreshAccessToken, signal }, orderId);
    },
  });
}

export function useIssueMerchantApiKeyMutation() {
  const { user, refreshAccessToken } = useAuth();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => issueMerchantApiKey({
      accessToken: user?.accessToken,
      refreshAccessToken,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: merchantQueryKeys.apiKey(user?.id) }),
  });
}

export function useRevokeMerchantApiKeyMutation() {
  const { user, refreshAccessToken } = useAuth();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => revokeMerchantApiKey({
      accessToken: user?.accessToken,
      refreshAccessToken,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: merchantQueryKeys.apiKey(user?.id) }),
  });
}
