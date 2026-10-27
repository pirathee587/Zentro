import type { DestinationType, ParcelSize } from '../services/customerApi';

export interface SendParcelForm {
  pickupAddress: string;
  pickupPhone: string;
  destinationType: DestinationType;
  dropoffAddress: string;
  recipientName: string;
  recipientPhone: string;
  parcelSize: ParcelSize;
  weightKg: string;
}

export type FieldName = keyof SendParcelForm;
export type FieldErrors = Partial<Record<FieldName, string>>;

export const WEIGHT_MIN = 0.1;
export const WEIGHT_MAX = 20;
const SL_PHONE = /^(\+94|0)\d{9}$/;

export const SIZE_OPTIONS: { value: ParcelSize; label: string }[] = [
  { value: 'SMALL', label: 'Small (up to 1 kg)' },
  { value: 'MEDIUM', label: 'Medium (1-5 kg)' },
  { value: 'LARGE', label: 'Large (5-20 kg)' },
];

export const DESTINATION_OPTIONS: { value: DestinationType; label: string }[] = [
  { value: 'SAME_CITY', label: 'Same city' },
  { value: 'OTHER_DISTRICT', label: 'Another district' },
];

export const EMPTY_FORM: SendParcelForm = {
  pickupAddress: '',
  pickupPhone: '',
  destinationType: 'SAME_CITY',
  dropoffAddress: '',
  recipientName: '',
  recipientPhone: '',
  parcelSize: 'SMALL',
  weightKg: '',
};

export const normalizePhone = (value: string) => value.replace(/[\s-]/g, '');

/** Returns the weight as a number only when it is inside the allowed range. */
export function parseWeight(value: string): number | null {
  if (value.trim() === '') return null;
  const n = Number(value);
  return Number.isFinite(n) && n >= WEIGHT_MIN && n <= WEIGHT_MAX ? n : null;
}

export function validateField(name: FieldName, form: SendParcelForm): string | undefined {
  const value = form[name];
  switch (name) {
    case 'pickupAddress':
    case 'dropoffAddress':
      return value.trim().length >= 8 ? undefined : 'Enter at least 8 characters.';
    case 'pickupPhone':
    case 'recipientPhone':
      return SL_PHONE.test(normalizePhone(value))
        ? undefined
        : 'Enter a Sri Lankan number, e.g. 0771234567 or +94771234567.';
    case 'recipientName':
      return value.trim() ? undefined : 'Recipient name is required.';
    case 'weightKg':
      return parseWeight(value) !== null ? undefined : `Weight must be between ${WEIGHT_MIN} and ${WEIGHT_MAX} kg.`;
    default:
      return undefined;
  }
}

export function validateForm(form: SendParcelForm): FieldErrors {
  const errors: FieldErrors = {};
  (Object.keys(form) as FieldName[]).forEach((name) => {
    const err = validateField(name, form);
    if (err) errors[name] = err;
  });
  return errors;
}

/** Maps server field paths (and plain form names) from a 422 response onto form fields. */
const SERVER_FIELD_MAP: Record<string, FieldName> = {
  'sender.address': 'pickupAddress',
  'sender.phone': 'pickupPhone',
  'recipient.address': 'dropoffAddress',
  'recipient.name': 'recipientName',
  'recipient.phone': 'recipientPhone',
  'parcel.weightKg': 'weightKg',
  'parcel.size': 'parcelSize',
  destinationType: 'destinationType',
};

export function mapServerFieldErrors(serverErrors: Record<string, string>): {
  fieldErrors: FieldErrors;
  unmapped: string[];
} {
  const fieldErrors: FieldErrors = {};
  const unmapped: string[] = [];
  for (const [path, message] of Object.entries(serverErrors)) {
    const field = SERVER_FIELD_MAP[path] ?? (path in EMPTY_FORM ? (path as FieldName) : undefined);
    if (field) fieldErrors[field] = message;
    else unmapped.push(message);
  }
  return { fieldErrors, unmapped };
}
