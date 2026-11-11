import React from 'react';
import type { UnifiedPartnerItem } from '../api/partnerApprovalApi';

export interface PartnerDetailModalProps {
  partner: UnifiedPartnerItem | null;
  onClose: () => void;
}

function formatDate(isoStr?: string | null): string {
  if (!isoStr) return '—';
  const date = new Date(isoStr);
  return Number.isNaN(date.getTime())
    ? 'Date unavailable'
    : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export function PartnerDetailModal({ partner, onClose }: PartnerDetailModalProps) {
  if (!partner) return null;

  const isMerchant = partner.partnerType === 'MERCHANT';
  const merchant = partner.merchantRaw;
  const courier = partner.courierRaw;

  return (
    <div
      className="staff-modal-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="partner-detail-title"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="staff-modal" style={{ maxWidth: '620px' }}>
        <div className="staff-modal-header">
          <div>
            <span className="staff-eyebrow">
              {isMerchant ? 'MERCHANT APPLICATION' : 'COURIER FLEET PROFILE'}
            </span>
            <h2 id="partner-detail-title">{partner.name}</h2>
            <p>Verification and registration records.</p>
          </div>
          <button
            type="button"
            className="staff-modal-close"
            onClick={onClose}
            aria-label="Close details"
          >
            ×
          </button>
        </div>

        <div className="staff-modal-body" style={{ display: 'grid', gap: '18px' }}>
          <div className="staff-review-meta" style={{ marginTop: 0 }}>
            <span>
              Partner Type
              <strong>{isMerchant ? 'Merchant Partner' : 'Courier Fleet'}</strong>
            </span>
            <span>
              Review Status
              <strong>{partner.status}</strong>
            </span>
            <span>
              Contact Phone
              <strong>{partner.phone}</strong>
            </span>
            <span>
              Identifier ({isMerchant ? 'Registration No' : 'Email'})
              <strong>{partner.identifier}</strong>
            </span>
            <span>
              Application / Profile ID
              <strong style={{ fontSize: '11px', fontFamily: 'monospace' }}>{partner.id}</strong>
            </span>
            <span>
              Submitted / Registered
              <strong>{formatDate(partner.createdAt)}</strong>
            </span>
            {partner.reviewedAt && (
              <span>
                Reviewed / Last Active
                <strong>{formatDate(partner.reviewedAt)}</strong>
              </span>
            )}
          </div>

          {isMerchant && merchant && (
            <div
              style={{
                padding: '14px',
                background: 'var(--cream)',
                borderRadius: '12px',
                display: 'grid',
                gap: '10px',
              }}
            >
              <h3 style={{ margin: 0, fontSize: '14px' }}>Business Verification</h3>
              <div className="staff-review-meta" style={{ marginTop: '6px' }}>
                <span>
                  Business Registration
                  <strong>{merchant.businessRegNo || 'Not provided'}</strong>
                </span>
                <span>
                  User Account UUID
                  <strong style={{ fontSize: '11px', fontFamily: 'monospace' }}>
                    {merchant.appUserId}
                  </strong>
                </span>
                {merchant.rejectionReason && (
                  <span style={{ gridColumn: '1 / -1' }}>
                    Rejection / Suspension Reason
                    <strong style={{ color: 'var(--err)' }}>{merchant.rejectionReason}</strong>
                  </span>
                )}
              </div>
            </div>
          )}

          {!isMerchant && courier && (
            <div
              style={{
                padding: '14px',
                background: 'var(--cream)',
                borderRadius: '12px',
                display: 'grid',
                gap: '10px',
              }}
            >
              <h3 style={{ margin: 0, fontSize: '14px' }}>Fleet &amp; Operational Details</h3>
              <div className="staff-review-meta" style={{ marginTop: '6px' }}>
                <span>
                  Vehicle Type
                  <strong>{courier.vehicleType || 'Unassigned'}</strong>
                </span>
                <span>
                  Assigned Delivery Zone
                  <strong>{courier.assignedZone || 'Unassigned'}</strong>
                </span>
                <span>
                  Operational Eligibility
                  <strong>{courier.active ? 'Active / Eligible' : 'Inactive / Pending'}</strong>
                </span>
                <span>
                  User Account Status
                  <strong>{courier.accountStatus || 'ACTIVE'}</strong>
                </span>
                <span>
                  User Account UUID
                  <strong style={{ fontSize: '11px', fontFamily: 'monospace' }}>
                    {courier.userId}
                  </strong>
                </span>
              </div>
            </div>
          )}
        </div>

        <div className="staff-modal-footer">
          <button type="button" className="staff-review-button" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

