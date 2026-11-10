import { type Page, type Locator, expect } from '@playwright/test';

export class TrackingPage {
  readonly page: Page;
  readonly searchInput: Locator;
  readonly trackButton: Locator;
  readonly trackingRefLarge: Locator;
  readonly liveBadge: Locator;
  readonly statusChip: Locator;
  readonly routeSubtitle: Locator;
  readonly progressBar: Locator;
  readonly stageTimeline: Locator;
  readonly stageItems: Locator;
  readonly notFoundAlert: Locator;
  readonly emptyState: Locator;

  constructor(page: Page) {
    this.page = page;
    this.searchInput = page.locator('#tracking-search-input');
    this.trackButton = page.locator('button[type="submit"]', { hasText: 'Track' });
    this.trackingRefLarge = page.locator('.sd-track-ref-large');
    this.liveBadge = page.locator('.sd-live-badge');
    this.statusChip = page.locator('.sd-status-chip');
    this.routeSubtitle = page.locator('.sd-route-subtitle');
    this.progressBar = page.locator('[role="progressbar"]');
    this.stageTimeline = page.locator('.sd-stage-timeline');
    this.stageItems = page.locator('.sd-stage-item');
    this.notFoundAlert = page.locator('.sd-field-error, [role="alert"]', {
      hasText: 'No parcel found for that number',
    });
    this.emptyState = page.locator('.sd-empty-state');
  }

  async goto(trackingRef?: string) {
    if (trackingRef) {
      await this.page.goto(`/customer/track/${encodeURIComponent(trackingRef)}`);
    } else {
      await this.page.goto('/customer/track');
    }
  }

  async gotoPublic(trackingRef?: string) {
    if (trackingRef) {
      await this.page.goto(`/track/${encodeURIComponent(trackingRef)}`);
    } else {
      await this.page.goto('/track');
    }
  }

  async search(trackingRef: string) {
    await this.searchInput.fill(trackingRef);
    await this.trackButton.click();
  }

  async expectParcelLoaded(trackingRef: string, statusText?: string) {
    await expect(this.trackingRefLarge).toHaveText(trackingRef, { timeout: 15000 });
    await expect(this.liveBadge).toBeVisible();
    await expect(this.stageTimeline).toBeVisible();
    if (statusText) {
      await expect(this.statusChip).toContainText(statusText);
    }
  }

  async expectPublicParcelLoaded(trackingNumber: string) {
    await expect(this.page.locator('h1')).toContainText("Your parcel's", { timeout: 15000 });
    await expect(this.page.locator('text=Tracking number:')).toContainText(trackingNumber);
  }

  async expectNotFound() {
    await expect(
      this.notFoundAlert.or(this.page.locator('text=No parcel found for that number')).first()
    ).toBeVisible({ timeout: 5000 });
  }
}
