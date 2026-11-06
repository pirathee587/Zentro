import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import {
  getMerchantApiKey,
  getMerchantApplication,
  getMerchantOrders,
  issueMerchantApiKey,
  revokeMerchantApiKey,
} from '../api/merchantApi';

export const merchantQueryKeys = {
  application: (userId?: string) => ['merchant', userId, 'application'] as const,
  apiKey: (userId?: string) => ['merchant', userId, 'api-key'] as const,
  orders: (userId?: string) => ['merchant', userId, 'orders'] as const,
};

export function useMerchantApplicationQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();

  return useQuery({
    queryKey: merchantQueryKeys.application(user?.id),
    enabled: Boolean(user?.accessToken) && enabled,
    retry: false,
    queryFn: () => getMerchantApplication({
      accessToken: user?.accessToken,
      refreshAccessToken,
    }),
  });
}

export function useMerchantApiKeyQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();

  return useQuery({
    queryKey: merchantQueryKeys.apiKey(user?.id),
    enabled: Boolean(user?.accessToken) && enabled,
    retry: false,
    queryFn: () => getMerchantApiKey({
      accessToken: user?.accessToken,
      refreshAccessToken,
    }),
  });
}

export function useMerchantOrdersQuery(enabled = true) {
  const { user, refreshAccessToken } = useAuth();

  return useQuery({
    queryKey: merchantQueryKeys.orders(user?.id),
    enabled: Boolean(user?.accessToken) && enabled,
    retry: false,
    queryFn: () => getMerchantOrders({
      accessToken: user?.accessToken,
      refreshAccessToken,
    }),
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
