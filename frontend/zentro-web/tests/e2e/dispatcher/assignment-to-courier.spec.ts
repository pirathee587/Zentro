import { test, expect } from '@playwright/test';
import { setupCourierAuth, setupDispatcherAuth } from '../fixtures/auth.fixture';
import { COURIERS, DispatchScenario, PARCELS } from '../fixtures/dispatch.fixture';
import { DispatchQueuePage } from '../pages/DispatchQueuePage';
import { WorkloadPage } from '../pages/WorkloadPage';
import { CourierTodayPage } from '../pages/CourierTodayPage';

test('empty queue remains empty after a manual refresh', async ({ page }) => {
  const state = new DispatchScenario();
  state.parcels.length = 0;
  await setupDispatcherAuth(page);
  await state.install(page);
  const queue = new DispatchQueuePage(page);
  await queue.goto();
  await queue.expectCount(0);
  await expect(page.getByText('The dispatch queue is empty.', { exact: true })).toBeVisible();
  const reads = state.calls.length;
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect.poll(() => state.calls.length).toBeGreaterThan(reads);
  await queue.expectCount(0);
  await expect(page.getByRole('button', { name: /^Assign ZD092/ })).toHaveCount(0);
});

test('review availability, assign, refresh workload and verify both courier views', async ({ page, browser }) => {
  const state = new DispatchScenario();
  await setupDispatcherAuth(page);
  await state.install(page);
  const workload = new WorkloadPage(page);
  await workload.goto();
  for (const courier of COURIERS) await workload.expectAssignments(courier.name, 0);
  await expect(workload.card('Courier One').getByRole('status')).toHaveText('Available');
  for (const name of ['Inactive Courier', 'Disabled Courier']) {
    await expect(workload.card(name).getByRole('status')).toHaveText('Unavailable');
  }
  await expect(workload.card('Inactive Courier')).toContainText('Inactive');
  await expect(workload.card('Disabled Courier')).toContainText('DISABLED');

  const queue = new DispatchQueuePage(page);
  await page.getByRole('navigation', { name: 'Dispatcher navigation' }).getByRole('link', { name: 'Dispatch Queue' }).click();
  await queue.expectCount(3);
  for (const parcel of PARCELS) await expect(page.getByRole('row').filter({ hasText: parcel.trackingReference })).toBeVisible();
  await queue.openAssignment(PARCELS[0].trackingReference);
  await expect(queue.confirm).toBeDisabled();
  await expect(queue.courier.locator('option')).toHaveCount(3);
  await expect(queue.courier).not.toContainText('Inactive Courier');
  await expect(queue.courier).not.toContainText('Disabled Courier');
  await queue.selectCourier(COURIERS[0].id);
  await queue.confirm.click();
  await expect(page.getByRole('status').filter({ hasText: 'Delivery assigned successfully.' })).toBeVisible();
  await queue.expectCount(2);
  await expect(page.getByRole('row').filter({ hasText: PARCELS[0].trackingReference })).toHaveCount(0);
  expect(state.assignments.size).toBe(1);
  expect(state.assignmentResults).toEqual([201]);
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await queue.expectCount(2);

  await page.getByRole('navigation', { name: 'Dispatcher navigation' }).getByRole('link', { name: 'Workload', exact: true }).click();
  await workload.expectAssignments('Courier One', 1);
  await workload.expectAssignments('Courier Two', 0);

  const contexts = await Promise.all([browser.newContext(), browser.newContext()]);
  try {
    for (let index = 0; index < contexts.length; index++) {
      const courier = COURIERS[index];
      const courierPage = await contexts[index].newPage();
      await courierPage.addInitScript(() => { (window as unknown as { __E2E__?: boolean }).__E2E__ = true; });
      await setupCourierAuth(courierPage, { id: courier.userId, name: courier.name, email: `courier${index + 1}@zentro.test`, roles: ['COURIER'] });
      await state.install(courierPage, courier.id);
      const today = new CourierTodayPage(courierPage);
      await today.goto();
      if (index === 0) {
        await expect(today.delivery(PARCELS[0].parcelId)).toBeVisible();
        await today.delivery(PARCELS[0].parcelId).getByRole('link', { name: 'View delivery' }).click();
        await expect(courierPage.getByRole('heading', { name: 'Proof of delivery', exact: true })).toBeVisible();
      } else {
        await expect(today.delivery(PARCELS[0].parcelId)).toHaveCount(0);
        await expect(courierPage.getByText('No deliveries are assigned to you yet.')).toBeVisible();
        const response = courierPage.waitForResponse(value => new URL(value.url()).pathname === `/api/v1/courier/deliveries/${PARCELS[0].parcelId}`);
        await today.openDetail(PARCELS[0].parcelId);
        expect((await response).status()).toBe(403);
        await expect(courierPage.getByRole('alert')).toContainText('Delivery not available');
        await expect(courierPage.getByRole('button', { name: /Submit/ })).toHaveCount(0);
      }
    }
  } finally {
    await Promise.all(contexts.map(context => context.close()));
  }
});
