import { test, expect } from '@playwright/test';
import { setupCustomerAuth, setupDispatcherAuth } from '../fixtures/auth.fixture';
import { COURIERS, DispatchScenario, PARCELS } from '../fixtures/dispatch.fixture';
import { DispatchQueuePage } from '../pages/DispatchQueuePage';
import { WorkloadPage } from '../pages/WorkloadPage';

for (const eligibility of ['inactive', 'disabled'] as const) {
  test(`courier becomes ${eligibility} after selection: clear selection and preserve assignments`, async ({ page }) => {
    const state = new DispatchScenario();
    await setupDispatcherAuth(page);
    await state.install(page);
    const queue = new DispatchQueuePage(page);
    await queue.goto();
    await queue.expectCount(3);
    await queue.openAssignment(PARCELS[0].trackingReference);
    await queue.selectCourier(COURIERS[0].id);
    if (eligibility === 'inactive') state.couriers[0].active = false;
    else state.couriers[0].accountStatus = 'DISABLED';
    const response = page.waitForResponse(value => value.request().method() === 'POST' && value.url().endsWith('/api/v1/dispatch/assignments'));
    await queue.confirm.click();
    expect((await response).status()).toBe(409);
    await expect(queue.dialog.getByRole('alert')).toContainText('That courier is no longer eligible. Choose another courier.');
    await expect(queue.courier).toHaveValue('');
    await expect(queue.confirm).toBeDisabled();
    await queue.expectCount(3);
    expect(state.assignments.size).toBe(0);
    expect(state.assignmentResults).toEqual([409]);
    await queue.dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    const workload = new WorkloadPage(page);
    await workload.goto();
    await workload.expectAssignments('Courier One', 0);
    await expect(workload.card('Courier One').getByRole('status')).toHaveText('Unavailable');
  });
}

test('two dispatchers race: one succeeds and one sees a refreshed conflict', async ({ page, browser }) => {
  const state = new DispatchScenario();
  state.coordinateAssignments(2);
  const otherContext = await browser.newContext();
  try {
    const other = await otherContext.newPage();
    await setupDispatcherAuth(page);
    await setupDispatcherAuth(other, { id: 'dispatcher-two', name: 'Second Dispatcher', email: 'dispatcher2@zentro.test', roles: ['DISPATCHER'] });
    await state.install(page);
    await state.install(other);
    const queues = [new DispatchQueuePage(page), new DispatchQueuePage(other)];
    for (let index = 0; index < queues.length; index++) {
      await queues[index].goto();
      await queues[index].openAssignment(PARCELS[0].trackingReference);
      await queues[index].selectCourier(COURIERS[index].id);
    }
    await Promise.all(queues.map(queue => queue.confirm.click()));
    await expect.poll(() => [...state.assignmentResults].sort()).toEqual([201, 409]);
    expect(state.assignments.size).toBe(1);
    for (const queue of queues) {
      await expect(queue.dialog).toHaveCount(0);
      await queue.expectCount(2);
      await expect(queue.page.getByRole('row').filter({ hasText: PARCELS[0].trackingReference })).toHaveCount(0);
    }
    const notices = await Promise.all([page, other].map(view => view.getByRole('status').allTextContents()));
    expect(notices.flat().filter(value => value.includes('Delivery assigned successfully.'))).toHaveLength(1);
    expect(notices.flat().filter(value => value.includes('already assigned or no longer eligible'))).toHaveLength(1);
    const workload = new WorkloadPage(page);
    await workload.goto();
    const winner = state.assignments.get(PARCELS[0].parcelId)!.courierId;
    for (const courier of COURIERS.slice(0, 2)) {
      await workload.expectAssignments(courier.name, courier.id === winner ? 1 : 0);
    }
  } finally {
    await otherContext.close();
  }
});

for (const role of ['CUSTOMER', 'MERCHANT', 'COURIER']) {
  test(`${role} cannot open the dispatch queue`, async ({ page }) => {
    const state = new DispatchScenario();
    await setupCustomerAuth(page, { id: `${role.toLowerCase()}-e2e`, name: role, email: `${role.toLowerCase()}@zentro.test`, roles: [role] });
    await state.install(page);
    await page.goto('/dispatcher/queue');
    await expect(page).toHaveURL(/\/forbidden$/);
    await expect(page.getByRole('heading', { name: 'Forbidden' })).toBeVisible();
    expect(state.calls).toEqual([]);
  });
}

test('anonymous visitor reaches the sign-in boundary without dispatch data', async ({ page }) => {
  const state = new DispatchScenario();
  await page.addInitScript(() => { (window as unknown as { __E2E__?: boolean }).__E2E__ = true; });
  await page.route('**/.well-known/openid-configuration', route => route.abort());
  await state.install(page);
  await page.goto('/dispatcher/queue');
  await expect(page.getByText('Redirecting to sign in...', { exact: true })).toBeVisible();
  expect(state.calls).toEqual([]);
  await expect(page.getByRole('button', { name: /^Assign ZD092/ })).toHaveCount(0);
});

for (const status of [401, 403] as const) {
  test(`assignment API ${status} is visible and does not change the queue or workload`, async ({ page }) => {
    const state = new DispatchScenario();
    state.assignFailure = status;
    await setupDispatcherAuth(page);
    await state.install(page);
    const queue = new DispatchQueuePage(page);
    await queue.goto();
    await queue.openAssignment(PARCELS[0].trackingReference);
    await queue.selectCourier(COURIERS[0].id);
    await queue.confirm.click();
    await expect(queue.dialog.getByRole('alert')).toContainText(status === 401
      ? 'Your session has expired. Please sign in again.'
      : 'Your account does not have permission to do that.');
    await queue.expectCount(3);
    expect(state.assignmentResults).toEqual([status]);
    expect(state.assignments.size).toBe(0);
    await queue.dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    const workload = new WorkloadPage(page);
    await workload.goto();
    await workload.expectAssignments('Courier One', 0);
  });
}
