import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const tokens = readFileSync(resolve(process.cwd(), 'src/styles/tokens.css'), 'utf8');
const keycloakTokens = readFileSync(resolve(process.cwd(), '../../infra/keycloak/themes/zentro/login/resources/css/zentro-tokens.css'), 'utf8');
const colorLiteral = /#[\da-f]{3,8}\b|rgba?\s*\(|hsla?\s*\(|(^|[^\w-])(?:white|black|red|blue|green|purple|transparent)(?=$|[^\w-])/gi;
const sourceColorLiteral = /#[\da-f]{3,8}\b|rgba?\s*\(|hsla?\s*\(/gi;

function cssFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? cssFiles(path) : entry.name.endsWith('.css') ? [path] : [];
  });
}

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? sourceFiles(path) : /\.(?:ts|tsx)$/.test(entry.name) && !/\.test\./.test(entry.name) ? [path] : [];
  });
}

describe('SwiftDrop palette tokens', () => {
  it('preserves the named reference color values', () => {
    for (const [name, value] of Object.entries({
      espresso: '#1a1209', umber: '#3a2610', gold: '#e5b574', cream: '#faf8f5', sand: '#f4f2f0', ink: '#1d1a17', muted: '#6b6560', err: '#b3261e',
      'warm-pale': '#fbe9cf', 'dark-umber': '#2b1c0c', 'espresso-deep': '#120c05', 'gold-light': '#f2cf9c', ochre: '#c98f4a', bronze: '#a8742f', stone: '#8a8178', line: '#ddd6cd',
    })) expect(tokens).toContain(`--${name}: ${value};`);
  });

  it('keeps color literals in the shared token file only', () => {
    const frontendCss = cssFiles(resolve(process.cwd(), 'src')).filter((path) => !path.endsWith('styles\\tokens.css'));
    const keycloakCss = resolve(process.cwd(), '../../infra/keycloak/themes/zentro/login/resources/css/zentro.css');
    const offenders = [...frontendCss, keycloakCss].flatMap((path) => {
      const matches = [...readFileSync(path, 'utf8').matchAll(colorLiteral)];
      return matches.map((match) => `${path}: ${match[0]}`);
    });

    expect(offenders).toEqual([]);
    const sourceOffenders = sourceFiles(resolve(process.cwd(), 'src')).flatMap((path) => {
      const matches = [...readFileSync(path, 'utf8').matchAll(sourceColorLiteral)];
      return matches.map((match) => `${path}: ${match[0]}`);
    });
    expect(sourceOffenders).toEqual([]);
  });

  it('keeps Keycloak theme colors identical to the frontend tokens', () => {
    const entries = [...tokens.matchAll(/--([\w-]+)\s*:\s*([^;]+);/g)];
    for (const [, name, value] of entries) expect(keycloakTokens).toMatch(new RegExp(`--${name}\\s*:\\s*${value.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*;`));
  });
});
