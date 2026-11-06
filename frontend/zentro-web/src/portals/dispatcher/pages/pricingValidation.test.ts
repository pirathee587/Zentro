import { describe, expect, it } from 'vitest';
import type { PricingRule } from '../api/pricingApi';
import {
  amountError,
  changedFields,
  quoteWeightError,
  ruleToFormValues,
  validateRuleForm,
} from './pricingValidation';

const RULE: PricingRule = {
  id: 'r1', ruleName: 'STANDARD', currency: 'LKR', active: true, updatedAt: null,
  baseFee: 550, includedWeightKg: 1, additionalWeightRatePerKg: 120,
  distanceSurchargeOtherDistrict: 402, expressSurcharge: 300, perKmRate: 0,
};

describe('amountError', () => {
  it.each(['0', '0.00', '550', '550.5', '550.50', '.5', '  12.30  ', '1000000'])('accepts "%s"', (value) => {
    expect(amountError(value, 1_000_000)).toBeUndefined();
  });

  it.each([
    ['', 'Enter a value.'],
    ['   ', 'Enter a value.'],
    ['abc', 'Enter a number.'],
    ['12a', 'Enter a number.'],
    ['1.2.3', 'Enter a number.'],
    ['-1', 'Must be 0 or more.'],
    ['-0.01', 'Must be 0 or more.'],
    ['1.234', 'Use at most 2 decimal places.'],
    ['.555', 'Use at most 2 decimal places.'],
    ['1.', 'Enter a number.'],
    ['1000000.01', 'Must be at most 1,000,000.'],
  ])('rejects "%s" with "%s"', (value, message) => {
    expect(amountError(value, 1_000_000)).toBe(message);
  });
});

describe('rule form helpers', () => {
  it('shows saved amounts with two decimals', () => {
    expect(ruleToFormValues(RULE)).toEqual({
      baseFee: '550.00', includedWeightKg: '1.00', additionalWeightRatePerKg: '120.00',
      distanceSurchargeOtherDistrict: '402.00', expressSurcharge: '300.00', perKmRate: '0.00',
    });
  });

  it('reports one message per invalid field and none for a valid form', () => {
    const values = ruleToFormValues(RULE);
    expect(validateRuleForm(values)).toEqual({});
    expect(validateRuleForm({ ...values, baseFee: '-5', includedWeightKg: '101', perKmRate: '' })).toEqual({
      baseFee: 'Must be 0 or more.',
      includedWeightKg: 'Must be at most 100.',
      perKmRate: 'Enter a value.',
    });
  });

  it('lists only fields whose valid value differs from the saved rule', () => {
    const values = ruleToFormValues(RULE);
    expect(changedFields(values, RULE)).toEqual([]);
    expect(changedFields({ ...values, baseFee: '550' }, RULE)).toEqual([]);
    expect(changedFields({ ...values, baseFee: '600', expressSurcharge: 'abc' }, RULE)).toEqual(['baseFee']);
  });
});

describe('quoteWeightError', () => {
  it.each(['0.1', '1', '2.5', '19.99', '20'])('accepts %s kg', (value) => {
    expect(quoteWeightError(value)).toBeUndefined();
  });

  it.each([
    ['', 'Enter the parcel weight.'],
    ['heavy', 'Enter a number.'],
    ['2.555', 'Use at most 2 decimal places.'],
    ['0', 'Weight must be between 0.1 and 20 kg.'],
    ['0.09', 'Weight must be between 0.1 and 20 kg.'],
    ['25', 'Weight must be between 0.1 and 20 kg.'],
    ['-1', 'Weight must be between 0.1 and 20 kg.'],
  ])('rejects "%s" with "%s"', (value, message) => {
    expect(quoteWeightError(value)).toBe(message);
  });
});
