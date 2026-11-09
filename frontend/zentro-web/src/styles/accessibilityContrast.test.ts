import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';

const css = readFileSync(resolve(process.cwd(), 'src/portals/customer/layout/customer.css'), 'utf8');
const globalCss = readFileSync(resolve(process.cwd(), 'src/styles/accessibility.css'), 'utf8');
const tokens = (block: string) => Object.fromEntries([...block.matchAll(/--([\w-]+):\s*([^;]+);/g)].map(match => [match[1], match[2].trim()]));
const light = tokens(css.match(/:root\s*\{([\s\S]*?)\}/)![1]);
const dark = { ...light, ...tokens(css.match(/html\[data-theme="dark"\]\s*\{([\s\S]*?)\}/)![1]) };
type RGB = [number, number, number];
function color(value: string): { rgb: RGB; alpha: number } {
  if (value.startsWith('#')) return { rgb: [1, 3, 5].map(index => parseInt(value.slice(index, index + 2), 16)) as RGB, alpha: 1 };
  const components = value.match(/[\d.]+/g)!.map(Number);
  return { rgb: components.slice(0, 3) as RGB, alpha: components[3] ?? 1 };
}
function over(value: string, surface: RGB): RGB {
  const parsed = color(value);
  return parsed.rgb.map((channel, index) => channel * parsed.alpha + surface[index] * (1 - parsed.alpha)) as RGB;
}
function luminance(rgb: RGB) {
  const linear = rgb.map(channel => { const value = channel / 255; return value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4; });
  return .2126 * linear[0] + .7152 * linear[1] + .0722 * linear[2];
}
function contrast(fg: RGB, bg: RGB) {
  const a = luminance(fg), b = luminance(bg);
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}
const rows: { theme: string; pair: string; before: number; after: number; threshold: number }[] = [];
for (const [theme, palette] of [['light', light], ['dark', dark]] as const) {
  const rgb = (token: string) => color(palette[token]).rgb;
  const oldMute = theme === 'light' ? color('#6f6f6f').rgb : rgb('mute');
  function add(pair: string, beforeFg: RGB, afterFg: RGB, surface: RGB, threshold = 4.5) {
    rows.push({ theme, pair, before: contrast(beforeFg, surface), after: contrast(afterFg, surface), threshold });
  }
  for (const surface of ['bg1', 'bg2', 'chip']) {
    add(`body/${surface}`, rgb('ink'), rgb('ink'), rgb(surface));
    add(`muted/${surface}`, oldMute, rgb('mute'), rgb(surface));
  }
  for (const endpoint of ['bg1', 'bg2']) {
    const card = over(palette.card, rgb(endpoint));
    add(`body/card-over-${endpoint}`, rgb('ink'), rgb('ink'), card);
    add(`muted/card-over-${endpoint}`, oldMute, rgb('mute'), card);
  }
  add('delivered-chip', rgb('onDark'), rgb('onDark'), rgb('dark'));
  add('accent-chip', color('#222222').rgb, color('#222222').rgb, rgb('y'));
  add('failed-chip', color('#3a1410').rgb, color('#3a1410').rgb, color('#e9a8a0').rgb);
  add('panel-body', rgb('onDark'), rgb('panel-text'), rgb('panel'));
  add('panel-muted', oldMute, rgb('panel-muted'), rgb('panel'));
  add('chat-placeholder', over('rgba(245,244,243,.45)', color('#1c1c1c').rgb), rgb('panel-muted'), color('#1c1c1c').rgb);
  add('input-boundary/chip', over(palette.line, rgb('chip')), rgb('mute'), rgb('chip'), 3);
  // Text-sized meaningful icons and placeholders share these actual foreground tokens.
}
const sharedTokens = tokens(readFileSync(resolve(process.cwd(), 'src/styles/tokens.css'), 'utf8'));
const outerFocus = color(sharedTokens.espresso).rgb;
const innerFocus = color(sharedTokens.white).rgb;
rows.push({ theme: 'shared', pair: 'focus-indicator/light-surface', before: contrast(color('#e5b574').rgb, color('#faf8f5').rgb), after: contrast(outerFocus, color('#faf8f5').rgb), threshold: 3 });
rows.push({ theme: 'shared', pair: 'focus-indicator/dark-surface', before: contrast(color('#e5b574').rgb, color('#171717').rgb), after: contrast(innerFocus, color('#171717').rgb), threshold: 3 });

describe('WCAG token contrast (sRGB alpha compositing, not rendered browser pixels)', () => {
  it.each(rows)('$theme $pair meets $threshold:1', row => expect(row.after).toBeGreaterThanOrEqual(row.threshold));
  it('uses a two-tone focus indicator and forced-colors outline', () => {
    expect(globalCss).toContain('outline: 2px solid var(--espresso)');
    expect(globalCss).toContain('box-shadow: 0 0 0 2px var(--white)');
    expect(globalCss).toContain('outline: 2px solid Highlight');
    expect(contrast(outerFocus, innerFocus)).toBeGreaterThan(3);
  });
});
afterAll(() => {
  if (process.env.CONTRAST_REPORT_PATH) writeFileSync(process.env.CONTRAST_REPORT_PATH, JSON.stringify(rows.map(row => ({ ...row, before: Number(row.before.toFixed(3)), after: Number(row.after.toFixed(3)) })), null, 2) + '\n');
});
