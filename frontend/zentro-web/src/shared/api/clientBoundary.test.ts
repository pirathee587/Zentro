import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const sourceRoot = join(process.cwd(), 'src');
const allowedTransportFiles = new Set([
  join(sourceRoot, 'shared/api/apiClient.ts'),
  join(sourceRoot, 'shared/domain/tracking/useTrackingStream.ts'),
]);

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.(ts|tsx)$/.test(entry.name) && !/\.test\.(ts|tsx)$/.test(entry.name) ? [path] : [];
  });
}

describe('HTTP client boundary', () => {
  it('keeps Axios instances and direct HTTP calls in the shared client (SSE is the explicit exception)', () => {
    const violations = sourceFiles(sourceRoot).filter((file) => {
      if (allowedTransportFiles.has(file)) return false;
      const source = readFileSync(file, 'utf8');
      return /\baxios\.create\s*\(|\baxios\.(get|post|put|patch|delete|request)\s*\(|\bfetch\s*\(|new\s+XMLHttpRequest\s*\(|new\s+(EventSource|WebSocket)\s*\(/.test(source);
    });
    expect(violations).toEqual([]);
  });
});
