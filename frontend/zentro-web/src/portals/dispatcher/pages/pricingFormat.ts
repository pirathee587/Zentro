/**
 * Display formatting for pricing amounts in the Dispatcher portal: always two decimals with grouping and the
 * currency the API returned, for example "LKR 1,072.00". Only formats values from the API; never calculates.
 *
 * Kept in the Dispatcher portal on purpose (no cross-portal imports). The customer portal formatter rounds to
 * whole rupees; a shared two-decimal formatter in shared/ is a follow-up for the frontend owner.
 */

const AMOUNT = new Intl.NumberFormat('en-US', {
  useGrouping: true,
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** "LKR 1,072.00". Shows "—" when the amount is missing or not a finite number. */
export function formatMoney(amount: number | null | undefined, currency?: string | null): string {
  if (typeof amount !== 'number' || !Number.isFinite(amount)) return '—';
  const code = currency?.trim() || 'LKR';
  return `${code} ${AMOUNT.format(amount)}`;
}

/** "2.50 kg". Shows "—" when the weight is missing or not a finite number. */
export function formatWeightKg(weight: number | null | undefined): string {
  if (typeof weight !== 'number' || !Number.isFinite(weight)) return '—';
  return `${AMOUNT.format(weight)} kg`;
}
