/**
 * Input checks for the Dispatcher pricing screens. These only decide whether a typed value can be sent; the
 * backend still validates every request and is the only place prices are calculated.
 *
 * Backend rules mirrored here: every pricing rule amount is required and >= 0.00 (PricingRuleUpdateRequest).
 * UI-only guards: at most two decimals and an upper limit per field, to catch typing mistakes before saving.
 * The quotation weight follows the customer booking form range (0.1-20 kg), decision D5 on #34.
 */

import { PRICING_RULE_FIELDS, type PricingRule, type PricingRuleField } from '../api/pricingApi';

export type FieldKind = 'money' | 'weight';

export type RuleFieldMeta = {
  label: string;
  hint: string;
  kind: FieldKind;
  max: number;
};

export const RULE_FIELD_META: Record<PricingRuleField, RuleFieldMeta> = {
  baseFee: { label: 'Base fee', hint: 'Charged for every parcel.', kind: 'money', max: 1_000_000 },
  includedWeightKg: { label: 'Included weight (kg)', hint: 'Weight covered by the base fee.', kind: 'weight', max: 100 },
  additionalWeightRatePerKg: {
    label: 'Rate per additional kg', hint: 'Charged for each kg above the included weight.', kind: 'money', max: 1_000_000,
  },
  distanceSurchargeOtherDistrict: {
    label: 'Other-district surcharge', hint: 'Added when the parcel leaves the city or zone.', kind: 'money', max: 1_000_000,
  },
  expressSurcharge: { label: 'Express surcharge', hint: 'Added for express delivery.', kind: 'money', max: 1_000_000 },
  perKmRate: { label: 'Rate per km (same city)', hint: 'Used for same-city distance pricing; 0 turns it off.', kind: 'money', max: 100_000 },
};

export const QUOTE_WEIGHT_MIN = 0.1;
export const QUOTE_WEIGHT_MAX = 20;

const DECIMAL = /^(\d+(\.\d{1,2})?|\.\d{1,2})$/;
const NUMBER = /^-?\d*\.?\d+$/;

/** Checks one typed amount. Returns an error message, or undefined when the value is valid. */
export function amountError(raw: string, max: number): string | undefined {
  const value = raw.trim();
  if (value === '') return 'Enter a value.';
  if (!NUMBER.test(value)) return 'Enter a number.';
  if (value.startsWith('-')) return 'Must be 0 or more.';
  if (!DECIMAL.test(value)) return 'Use at most 2 decimal places.';
  if (Number(value) > max) return `Must be at most ${max.toLocaleString('en-US')}.`;
  return undefined;
}

export type RuleFormValues = Record<PricingRuleField, string>;
export type RuleFormErrors = Partial<Record<PricingRuleField, string>>;

/** The rule's amounts as input text with two decimals, e.g. 550 -> "550.00". */
export function ruleToFormValues(rule: PricingRule): RuleFormValues {
  return Object.fromEntries(
    PRICING_RULE_FIELDS.map((field) => [field, rule[field].toFixed(2)]),
  ) as RuleFormValues;
}

export function validateRuleForm(values: RuleFormValues): RuleFormErrors {
  const errors: RuleFormErrors = {};
  for (const field of PRICING_RULE_FIELDS) {
    const error = amountError(values[field], RULE_FIELD_META[field].max);
    if (error) errors[field] = error;
  }
  return errors;
}

/** Fields whose typed value is a valid number different from the saved rule. */
export function changedFields(values: RuleFormValues, rule: PricingRule): PricingRuleField[] {
  return PRICING_RULE_FIELDS.filter((field) => {
    const typed = values[field].trim();
    return DECIMAL.test(typed) && Number(typed) !== rule[field];
  });
}

/** Checks the quotation weight: 0.1-20 kg with at most two decimals. */
export function quoteWeightError(raw: string): string | undefined {
  const value = raw.trim();
  if (value === '') return 'Enter the parcel weight.';
  if (!NUMBER.test(value)) return 'Enter a number.';
  if (!value.startsWith('-') && !DECIMAL.test(value)) return 'Use at most 2 decimal places.';
  const weight = Number(value);
  if (weight < QUOTE_WEIGHT_MIN || weight > QUOTE_WEIGHT_MAX) {
    return `Weight must be between ${QUOTE_WEIGHT_MIN} and ${QUOTE_WEIGHT_MAX} kg.`;
  }
  return undefined;
}
