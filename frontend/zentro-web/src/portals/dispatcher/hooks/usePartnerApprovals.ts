import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import {
  listMerchantApplications,
  reviewMerchantApplication,
  type MerchantApplication,
} from '../api/adminApi';
import {
  listCouriersAdmin,
  updateCourierAdmin,
  type CourierProfile,
} from '../api/courierAdminApi';
import {
  partnerApprovalKeys,
  mapMerchantToPartner,
  mapCourierToPartner,
  type UnifiedPartnerItem,
} from '../api/partnerApprovalApi';

export function usePartnerApprovals(options: {
  isMerchantEnabled?: boolean;
  isCourierEnabled?: boolean;
} = {}) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const token = user?.accessToken;

  const isMerchantEnabled = options.isMerchantEnabled !== false && Boolean(token);
  const isCourierEnabled = options.isCourierEnabled !== false && Boolean(token);

  const merchantsQuery = useQuery({
    queryKey: partnerApprovalKeys.merchants(),
    enabled: isMerchantEnabled,
    queryFn: ({ signal }) => listMerchantApplications(signal),
  });

  const couriersQuery = useQuery({
    queryKey: partnerApprovalKeys.couriers(),
    enabled: isCourierEnabled,
    queryFn: ({ signal }) => listCouriersAdmin(undefined, token, signal),
  });

  const reviewMerchantMutation = useMutation({
    mutationFn: async (input: {
      id: string;
      decision: 'APPROVED' | 'REJECTED';
      rejectionReason?: string;
    }) => {
      const { id, ...body } = input;
      return reviewMerchantApplication(id, body);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: partnerApprovalKeys.all });
      await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'merchant-applications'] });
    },
  });

  const reviewCourierMutation = useMutation({
    mutationFn: async (input: { id: string; active: boolean }) => {
      return updateCourierAdmin(input.id, { active: input.active }, token);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: partnerApprovalKeys.all });
      await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'couriers'] });
    },
  });

  const merchantPartners: UnifiedPartnerItem[] = (merchantsQuery.data ?? []).map(mapMerchantToPartner);
  const courierPartners: UnifiedPartnerItem[] = (couriersQuery.data ?? []).map(mapCourierToPartner);

  return {
    merchantsQuery,
    couriersQuery,
    merchantPartners,
    courierPartners,
    reviewMerchantMutation,
    reviewCourierMutation,
  };
}
