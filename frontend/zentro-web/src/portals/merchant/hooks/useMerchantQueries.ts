import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import {
  getMerchantApiKey,
  getMerchantApplication,
  getMerchantOrder,
  getMerchantOrders,
  isMerchantOrderId,
  issueMerchantApiKey,
  revokeMerchantApiKey,
} from '../api/merchantApi';

export const merchantQueryKeys = {
  application: (userId?: string) => queryKeys.merchant.application(principalCacheScope(userId)),
  apiKey: (userId?: string) => queryKeys.merchant.apiKey(principalCacheScope(userId)),
  orders: (userId?: string) => queryKeys.merchant.orders(undefined, principalCacheScope(userId)),
  order: (orderId: string, userId?: string) => queryKeys.merchant.order(orderId, principalCacheScope(userId)),
};

export function useMerchantApplicationQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();

  return useQuery({
    queryKey: merchantQueryKeys.application(user?.id),
    enabled: Boolean(user?.accessToken) && enabled,
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
