import { describe, expect, it } from 'vitest';
import { normalizePublicTrackingNumber } from '../trackingNumber';

describe('normalizePublicTrackingNumber', () => {
  it('trims and normalizes valid tracking numbers', () => {
    expect(normalizePublicTrackingNumber(' ztr-abcdefg234 ')).toBe('ZTR-ABCDEFG234');
  });

  it('rejects values outside the public tracking format', () => {
    expect(normalizePublicTrackingNumber('ZTR-ABCDEFG231')).toBeUndefined();
    expect(normalizePublicTrackingNumber('')).toBeUndefined();
  });
});
