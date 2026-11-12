import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { estimateFee, getPricingRule, getZoneByCode, getZones, updatePricingRule } from '../api/pricingApi';

/** Query keys for the Zones & pricing page; one place so reads and invalidation always match. */
export const pricingKeys = {
  rule: ['dispatcher', 'pricing-rule'] as const,
  zones: ['dispatcher', 'zones'] as const,
  zone: (code: string) => ['dispatcher', 'zones', code] as const,
};

export function usePricingRule() {
  return useQuery({ queryKey: pricingKeys.rule, queryFn: ({ signal }) => getPricingRule(signal) });
}

export function useZones() {
  return useQuery({ queryKey: pricingKeys.zones, queryFn: ({ signal }) => getZones(signal) });
}

export function useZoneByCode(code: string) {
  return useQuery({
    queryKey: pricingKeys.zone(code),
    queryFn: ({ signal }) => getZoneByCode(code, signal),
    enabled: Boolean(code && code.trim()),
  });
}

/** Saves the rule, shows the saved values at once and then reloads them from the API. */
export function useUpdatePricingRule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: updatePricingRule,
    onSuccess: async (saved) => {
      queryClient.setQueryData(pricingKeys.rule, saved);
      await queryClient.invalidateQueries({ queryKey: pricingKeys.rule });
    },
  });
}

/** A quotation is requested on demand (Calculate), never on every keystroke. */
export function useFeeEstimate() {
  return useMutation({ mutationFn: estimateFee });
}
