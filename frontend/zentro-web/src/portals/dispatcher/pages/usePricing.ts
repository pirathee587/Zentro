import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { estimateFee, getPricingRule, getZones, updatePricingRule } from '../api/pricingApi';

/** Query keys for the Zones & pricing page; one place so reads and invalidation always match. */
export const pricingKeys = {
  rule: ['dispatcher', 'pricing-rule'] as const,
  zones: ['dispatcher', 'zones'] as const,
};

export function usePricingRule() {
  return useQuery({ queryKey: pricingKeys.rule, queryFn: getPricingRule });
}

export function useZones() {
  return useQuery({ queryKey: pricingKeys.zones, queryFn: getZones });
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
