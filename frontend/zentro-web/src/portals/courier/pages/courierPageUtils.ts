import type { CompletionState } from '../api/deliveryApi';

export const completionLabels: Record<CompletionState, string> = {
  PENDING: 'Completion pending',
  COMPLETED: 'Completion confirmed',
  REJECTED: 'Completion rejected',
  BLOCKED: 'Completion needs attention',
};
export function assignedDate(value: string) {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Colombo',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(new Date(value));
}
export function assignedDay(value: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Colombo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(value));
  return ['year', 'month', 'day']
    .map((type) => parts.find((p) => p.type === type)?.value)
    .join('-');
}
