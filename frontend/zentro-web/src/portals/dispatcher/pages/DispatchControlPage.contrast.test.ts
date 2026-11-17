import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(resolve(process.cwd(), 'src/portals/dispatcher/pages/DispatchControlPage.css'), 'utf8');
const block = css.match(/html\[data-theme='dark'\] \.dispatcher-portal\.sd-dispatcher-shell:has\(\.dispatch-dashboard\)\s*\{([^}]+)\}/)![1];
const tokens = Object.fromEntries([...block.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(match => [match[1], match[2].trim()]));
function resolveToken(name: string): string {
  const value = tokens[name];
  const alias = value.match(/^var\((--[\w-]+)\)$/);
  return alias ? resolveToken(alias[1]) : value;
}
function luminance(name: string) {
  const hex = resolveToken(name);
  const [r, g, b] = [1, 3, 5].map(offset => {
    const channel = parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4;
  });
  return .2126 * r + .7152 * g + .0722 * b;
}
function ratio(fg: string, bg: string) {
  const a = luminance(fg), b = luminance(bg);
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}
const textPairs = [
  ['--ink', '--bg1'], ['--mute', '--bg1'], ['--ink', '--card'], ['--mute', '--card'],
  ['--ink', '--chip'], ['--mute', '--chip'], ['--ink', '--dispatch-hover'],
  ['--y', '--card'], ['--dispatch-accent-hover', '--card'],
  ['--dispatch-highlight-ink', '--y'], ['--dispatch-highlight-muted', '--y'],
  ['--dispatch-highlight-ink', '--dispatch-accent-hover'],
  ['--dispatch-error', '--card'], ['--ink', '--dispatch-notice'],
];
const uiPairs = [
  ['--dispatch-control-border', '--card'], ['--dispatch-control-border', '--chip'],
  ['--dispatch-control-border', '--dispatch-hover'], ['--dispatch-control-border', '--bg1'],
  ['--dispatch-positive', '--card'], ['--dispatch-highlight-status', '--y'],
  ['--dispatch-highlight-muted', '--y'], ['--y', '--bg1'], ['--ink', '--card'],
];
describe('Dispatcher dark palette contrast (sRGB tokens, not rendered pixels)', () => {
  it.each(textPairs)('%s text on %s meets WCAG AA', (fg, bg) => expect(ratio(fg, bg)).toBeGreaterThanOrEqual(4.5));
  it.each(uiPairs)('%s essential UI on %s meets 3:1', (fg, bg) => expect(ratio(fg, bg)).toBeGreaterThanOrEqual(3));
});
