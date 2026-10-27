import React from 'react';

export type ParcelStatusType =
  | 'In progress'
  | 'Delivered'
  | 'Failed'
  | 'Created'
  | 'IN_PROGRESS'
  | 'IN_TRANSIT'
  | 'OUT_FOR_DELIVERY'
  | 'PICKED_UP'
  | 'DELIVERED'
  | 'FAILED'
  | 'CREATED'
  | 'CANCELLED';

export interface StatusChipProps extends React.HTMLAttributes<HTMLSpanElement> {
  status: ParcelStatusType | string;
}

export function normalizeStatus(status: string): 'In progress' | 'Delivered' | 'Failed' | 'Created' {
  const s = status.trim().toUpperCase().replace(/[\s_-]+/g, '');
  if (['DELIVERED', 'COMPLETED', 'RECEIVED'].includes(s)) {
    return 'Delivered';
  }
  if (['FAILED', 'CANCELLED', 'RETURNED', 'REJECTED'].includes(s)) {
    return 'Failed';
  }
  if (['CREATED', 'DRAFT', 'PENDING', 'BOOKED'].includes(s)) {
    return 'Created';
  }
  return 'In progress';
}

export const StatusChip: React.FC<StatusChipProps> = ({ status, className = '', ...props }) => {
  const normalized = normalizeStatus(status);
  const statusClassKey = normalized.toLowerCase().replace(/\s+/g, '-');

  return (
    <span
      className={`sd-status-chip sd-status-${statusClassKey} ${className}`.trim()}
      {...props}
    >
      <span className="sd-status-dot" aria-hidden="true" />
      <span>{normalized}</span>
    </span>
  );
};
