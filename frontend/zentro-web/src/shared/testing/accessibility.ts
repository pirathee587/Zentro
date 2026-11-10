import axe from 'axe-core';

/** DOM checks only: jsdom has no rendered layout or reliable color/compositing measurements. */
export const disabledAxeRules = ['color-contrast'] as const;
export async function auditAccessibility(container: HTMLElement) {
  return axe.run(container, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'] },
    rules: { 'color-contrast': { enabled: false } },
  });
}
