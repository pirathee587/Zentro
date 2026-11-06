import { describe, expect, it } from 'vitest';
import { formatMoney, formatWeightKg } from './pricingFormat';

describe('formatMoney', () => {
  it.each([
    [1072, 'LKR', 'LKR 1,072.00'],
    [550, 'LKR', 'LKR 550.00'],
    [551.2, 'LKR', 'LKR 551.20'],
    [0, 'LKR', 'LKR 0.00'],
    [1234567.891, 'LKR', 'LKR 1,234,567.89'],
    [125.5, 'USD', 'USD 125.50'],
  ])('%d %s -> %s', (amount, currency, expected) => {
    expect(formatMoney(amount, currency)).toBe(expected);
  });

  it('always shows exactly two decimals', () => {
    expect(formatMoney(402, 'LKR')).toMatch(/\.\d{2}$/);
    expect(formatMoney(0.005, 'LKR')).toBe('LKR 0.01');
  });

  it('falls back to LKR when the currency is missing or blank', () => {
    expect(formatMoney(300, undefined)).toBe('LKR 300.00');
    expect(formatMoney(300, null)).toBe('LKR 300.00');
    expect(formatMoney(300, '  ')).toBe('LKR 300.00');
  });

  it('shows a dash for missing or non-finite amounts instead of a wrong number', () => {
    expect(formatMoney(undefined, 'LKR')).toBe('—');
    expect(formatMoney(null, 'LKR')).toBe('—');
    expect(formatMoney(Number.NaN, 'LKR')).toBe('—');
    expect(formatMoney(Number.POSITIVE_INFINITY, 'LKR')).toBe('—');
  });
});

describe('formatWeightKg', () => {
  it('shows two decimals and the unit', () => {
    expect(formatWeightKg(1)).toBe('1.00 kg');
    expect(formatWeightKg(2.5)).toBe('2.50 kg');
    expect(formatWeightKg(undefined)).toBe('—');
  });
});
