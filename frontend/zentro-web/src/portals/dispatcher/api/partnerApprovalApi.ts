/**
 * Partner Approval API service and unified types (ZD-083 / Issue #158).
 *
 * Unifies merchant application reviews and courier eligibility approvals
 * under the Dispatcher administration workflow.
 */

import type { MerchantApplication } from './adminApi';
import type { CourierProfile } from './courierAdminApi';

export type PartnerType = 'ALL' | 'MERCHANT' | 'COURIER';
export type PartnerStatus = 'ALL' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';

export interface UnifiedPartnerItem {
  id: string;
  partnerType: 'MERCHANT' | 'COURIER';
  name: string;
  identifier: string; // Business Reg No for merchants, Email for couriers
  phone: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';
  rejectionReason: string | null;
  createdAt: string;
  reviewedAt: string | null;
  assignedZone?: string | null;
  vehicleType?: string | null;
  merchantRaw?: MerchantApplication;
  courierRaw?: CourierProfile;
}

/**
 * Maps a MerchantApplication to a UnifiedPartnerItem.
 */
export function mapMerchantToPartner(m: MerchantApplication): UnifiedPartnerItem {
  return {
    id: m.id,
    partnerType: 'MERCHANT',
    name: m.businessName,
    identifier: m.businessRegNo || 'Not provided',
    phone: m.contactPhone,
    status: m.status,
    rejectionReason: m.rejectionReason,
    createdAt: m.createdAt,
    reviewedAt: m.reviewedAt,
    merchantRaw: m,
  };
}

/**
 * Maps a CourierProfile to a UnifiedPartnerItem.
 * Couriers without active eligibility (active === false) are treated as PENDING review.
 * Inactive or disabled accounts are marked REJECTED/SUSPENDED.
 */
export function mapCourierToPartner(c: CourierProfile): UnifiedPartnerItem {
  let status: UnifiedPartnerItem['status'] = 'APPROVED';
  if (!c.active) {
    status = 'PENDING';
  }
  if (c.accountStatus === 'SUSPENDED') {
    status = 'SUSPENDED';
  } else if (c.accountStatus === 'DISABLED') {
    status = 'REJECTED';
  }

  return {
    id: c.id,
    partnerType: 'COURIER',
    name: c.name || 'Unnamed Courier',
    identifier: c.email || 'No email provided',
    phone: c.phone || 'No phone provided',
    status,
    rejectionReason: null,
    createdAt: c.createdAt,
    reviewedAt: c.updatedAt,
    assignedZone: c.assignedZone,
    vehicleType: c.vehicleType,
    courierRaw: c,
  };
}

export const partnerApprovalKeys = {
  all: ['dispatcher', 'partner-approvals'] as const,
  merchants: (status?: string) => ['dispatcher', 'merchant-applications', { status }] as const,
  couriers: (params?: { active?: boolean; status?: string }) => ['dispatcher', 'couriers', params] as const,
};
