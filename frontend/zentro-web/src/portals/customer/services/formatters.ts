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
