import { test, expect, type Page } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const target = resolve(process.cwd(), '../../backend/merchant-order-webhook-e2e/target');
const fixture = JSON.parse(readFileSync(resolve(target, 'status-sync-browser.json'), 'utf8')) as {
  gateway: string; keycloak: string; parcelId: string; trackingRef: string;
};
if (fixture.keycloak !== 'http://localhost:28180') throw new Error('Refusing a non-isolated authentication target');

async function signIn(page: Page, path: string, role: 'customer' | 'courier') {
  await page.goto(path);
  await expect(page).toHaveURL(/localhost:28180/);
  await page.locator('#username').fill(`sync-${role}@example.test`);
  await page.locator('#password').fill(`isolated-${role}-password`);
  await page.locator('#kc-login').click();
  await expect(page).toHaveURL(new RegExp(`localhost:28173${path}`), { timeout: 45_000 });
}

test('real courier actions persist to the same customer tracking URL, timeline and courier', async ({ browser }) => {
  const courierContext = await browser.newContext();
  const customerContext = await browser.newContext();
  const publicContext = await browser.newContext();
  const courier = await courierContext.newPage(), customer = await customerContext.newPage(), publicPage = await publicContext.newPage();
  const deliveryPath = `/courier/deliveries/${fixture.parcelId}`;
  const trackingPath = `/customer/track/${fixture.trackingRef}`;
  await signIn(courier, deliveryPath, 'courier');
  await expect(courier.getByRole('link', { name: fixture.trackingRef, exact: true })).toBeVisible();
  await signIn(customer, trackingPath, 'customer');
  await expect(customer.getByText('Current status: created', { exact: true })).toBeVisible();
  await courier.getByRole('button', { name: 'Accept assignment', exact: true }).click();
  await expect(courier.getByText('Assignment accepted.', { exact: true })).toBeVisible();
  await customer.reload();
  await expect(customer.getByText('Isolated COURIER', { exact: true })).toBeVisible();
  for (const [state, label] of [['PICKED_UP', 'Picked up'], ['IN_TRANSIT', 'In transit'], ['OUT_FOR_DELIVERY', 'Out for delivery']] as const) {
    await courier.getByRole('button', { name: `Mark ${state.replaceAll('_', ' ')}`, exact: true }).click();
    await expect(courier.getByText(`Status updated to ${state}.`, { exact: true })).toBeVisible();
    await courier.reload();
    await expect(courier.getByText(state.replaceAll('_', ' '), { exact: true })).toBeVisible();
    await customer.reload();
    await expect(customer.getByText(`Current status: ${state.toLowerCase().replaceAll('_', ' ')}`, { exact: true })).toBeVisible();
    await expect(customer.getByLabel(`${label}: current`, { exact: true })).toBeVisible();
    await expect(customer.getByText('Isolated COURIER', { exact: true })).toBeVisible();
    await publicPage.goto(`/track/${fixture.trackingRef}`);
    await expect(publicPage.getByText(`Current status: ${state.toLowerCase().replaceAll('_', ' ')}`, { exact: true })).toBeVisible();
  }
  await courier.locator('[name="recipientName"]').fill('Isolated browser recipient');
  await courier.locator('[name="proofReference"]').fill('isolated-browser-proof');
  await courier.getByRole('button', { name: 'Submit proof', exact: true }).click();
  await expect(courier.getByText('Delivery completed. Parcel service has confirmed delivery.', { exact: true })).toBeVisible({ timeout: 45_000 });
  await customer.reload();
  await expect(customer.getByText('Current status: delivered', { exact: true })).toBeVisible();
  await expect(customer.getByLabel('Delivered: done', { exact: true })).toBeVisible();
  await publicPage.goto(`/track/${fixture.trackingRef}`);
  await publicPage.reload();
  await expect(publicPage.getByText('Current status: delivered', { exact: true })).toBeVisible();
  await expect(publicPage.getByLabel('Delivered: done', { exact: true })).toBeVisible();
  await expect(publicPage.getByText('Isolated COURIER', { exact: true })).toHaveCount(0);
  await customer.screenshot({ path: test.info().outputPath('customer-delivered.png'), fullPage: true });
  await publicPage.screenshot({ path: test.info().outputPath('public-delivered.png'), fullPage: true });
  writeFileSync(resolve(target, 'status-sync-browser-complete'), 'verified matching parcel, persisted transitions, courier, proof and refresh');
  await courierContext.close(); await customerContext.close(); await publicContext.close();
});
