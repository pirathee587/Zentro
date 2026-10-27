import { describe, expect, it } from 'vitest';
import { formatMoney, formatDate } from './formatters';

describe('SwiftDrop customer formatters', () => {
  it('formats money strictly as "LKR 1,072"', () => {
    expect(formatMoney(1072)).toBe('LKR 1,072');
    expect(formatMoney(550)).toBe('LKR 550');
    expect(formatMoney(1850.4)).toBe('LKR 1,850');
    expect(formatMoney(0)).toBe('LKR 0');
  });

  it('formats dates strictly as "27 Oct 2026"', () => {
    const d = new Date('2026-10-27T12:00:00Z');
    expect(formatDate(d)).toBe('27 Oct 2026');
    expect(formatDate('2026-10-27')).toBe('27 Oct 2026');
    expect(formatDate('2026-01-05')).toBe('5 Jan 2026');
  });
});
