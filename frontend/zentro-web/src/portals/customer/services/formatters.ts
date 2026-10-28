/**
 * Format currency strictly as "LKR 1,072"
 */
export function formatMoney(amount: number): string {
  const rounded = Math.round(amount);
  const formattedNumber = new Intl.NumberFormat('en-US', {
    useGrouping: true,
    maximumFractionDigits: 0,
  }).format(rounded);
  return `LKR ${formattedNumber}`;
}

const MONTH_NAMES = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

/**
 * Format date strictly as "27 Oct 2026"
 */
export function formatDate(input: string | number | Date): string {
  if (!input) return '';
  const date = typeof input === 'string' || typeof input === 'number' ? new Date(input) : input;
  if (isNaN(date.getTime())) return '';

  const day = date.getDate();
  const month = MONTH_NAMES[date.getMonth()];
  const year = date.getFullYear();

  return `${day} ${month} ${year}`;
}

export type StandardParcelStatus = 'In progress' | 'Delivered' | 'Failed' | 'Created';

/**
 * Normalizes any backend or raw status string (e.g. "IN_PROGRESS", "DELIVERED", "FAILED")
 * into the customer portal standard status.
 */
export function normalizeParcelStatus(status?: string | null): StandardParcelStatus {
  if (!status) return 'Created';
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
