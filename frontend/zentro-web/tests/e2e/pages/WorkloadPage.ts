import { expect, type Page } from '@playwright/test';

export class WorkloadPage {
  constructor(readonly page: Page) {}

  async goto() {
    await this.page.goto('/dispatcher/workload');
    await expect(this.page.getByRole('heading', { name: 'Courier workload', exact: true })).toBeVisible();
  }

  card(name: string) {
    return this.page.getByTestId('workload-card').filter({
      has: this.page.getByRole('heading', { name, exact: true }),
    });
  }

  async expectAssignments(name: string, count: number) {
    const cell = this.card(name).locator('div').filter({
      has: this.page.getByText('Assigned deliveries', { exact: true }),
    }).filter({ has: this.page.locator('strong') }).last();
    await expect(cell.locator('strong')).toHaveText(String(count));
  }
}
