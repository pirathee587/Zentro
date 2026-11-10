import { type Page, type Locator, expect } from '@playwright/test';

export class HistoryPage {
  readonly page: Page;
  readonly pageTitle: Locator;
  readonly searchInput: Locator;
  readonly filterChips: Locator;
  readonly table: Locator;
  readonly rows: Locator;

  constructor(page: Page) {
    this.page = page;
    this.pageTitle = page.locator('.sd-page-title');
    this.searchInput = page.locator('#history-search-input');
    this.filterChips = page.locator('.sd-filter-chip');
    this.table = page.locator('.sd-table');
    this.rows = page.locator('.sd-table-row:not(.sd-table-skeleton-row)');
  }

  async goto() {
    await this.page.goto('/customer/history');
    await expect(this.pageTitle).toContainText('Delivery history');
  }

  async filterBy(statusLabel: 'All' | 'In progress' | 'Delivered' | 'Failed') {
    const chip = this.filterChips.filter({ hasText: statusLabel });
    await chip.click();
    await this.page.waitForTimeout(350); // debounce
  }

  async search(query: string) {
    await this.searchInput.fill(query);
    await this.page.waitForTimeout(400); // debounce 300ms
  }

  async expectTrackingRefInHistory(trackingRef: string) {
    const cell = this.page.locator('.sd-history-ref', { hasText: trackingRef });
    await expect(cell).toBeVisible({ timeout: 6000 });
  }
}

