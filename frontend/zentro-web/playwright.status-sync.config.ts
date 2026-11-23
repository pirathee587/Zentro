import { defineConfig } from '@playwright/test';

// Opt-in real-service test. Requires CourierTrackingSyncIT's disposable stack and Vite on 28173.
export default defineConfig({
  testDir: './tests/status-sync',
  testMatch: '**/*.spec.ts',
  timeout: 180_000,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: 'status-sync-report', open: 'never' }]],
  outputDir: 'status-sync-results',
  use: { baseURL: 'http://localhost:28173', channel: 'chrome', headless: true, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
});
