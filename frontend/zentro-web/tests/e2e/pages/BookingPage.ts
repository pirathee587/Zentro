import { type Page, type Locator, expect } from '@playwright/test';

export interface BookingFormData {
  pickupAddress: string;
  pickupPhone: string;
  destinationType?: 'SAME_CITY' | 'OTHER_DISTRICT';
  dropoffAddress: string;
  recipientName: string;
  recipientPhone: string;
  parcelSize?: 'SMALL' | 'MEDIUM' | 'LARGE';
  weightKg: string;
}

export class BookingPage {
  readonly page: Page;
  readonly pickupAddressInput: Locator;
  readonly pickupPhoneInput: Locator;
  readonly destinationSelect: Locator;
  readonly dropoffAddressInput: Locator;
  readonly recipientNameInput: Locator;
  readonly recipientPhoneInput: Locator;
  readonly parcelSizeSelect: Locator;
  readonly weightKgInput: Locator;
  readonly reviewButton: Locator;
  readonly confirmButton: Locator;
  readonly backButton: Locator;
  readonly trackingReferenceBox: Locator;
  readonly trackParcelButton: Locator;
  readonly sendAnotherButton: Locator;
  readonly feeCard: Locator;
  readonly feeBreakdown: Locator;
  readonly reviewList: Locator;
  readonly bookedCard: Locator;
  readonly serverAlert: Locator;

  constructor(page: Page) {
    this.page = page;
    this.pickupAddressInput = page.locator('#pickupAddress');
    this.pickupPhoneInput = page.locator('#pickupPhone');
    this.destinationSelect = page.locator('#destinationType');
    this.dropoffAddressInput = page.locator('#dropoffAddress');
    this.recipientNameInput = page.locator('#recipientName');
    this.recipientPhoneInput = page.locator('#recipientPhone');
    this.parcelSizeSelect = page.locator('#parcelSize');
    this.weightKgInput = page.locator('#weightKg');
    this.reviewButton = page.locator('button[type="submit"]', { hasText: 'Review & see fee' });
    this.confirmButton = page.locator('button', { hasText: 'Confirm delivery' });
    this.backButton = page.locator('button', { hasText: 'Back' });
    this.trackingReferenceBox = page.locator('.sd-booked-ref-box');
    this.trackParcelButton = page.locator('button', { hasText: 'Track parcel' });
    this.sendAnotherButton = page.locator('button', { hasText: 'Send another' });
    this.feeCard = page.locator('.sd-fee-card');
    this.feeBreakdown = page.locator('.sd-fee-breakdown');
    this.reviewList = page.locator('.sd-review-list');
    this.bookedCard = page.locator('.sd-booked-card');
    this.serverAlert = page.locator('.sd-inline-alert-error');
  }

  async goto() {
    await this.page.goto('/customer/send');
    await expect(this.page.locator('h1.sd-page-title')).toContainText('Send a parcel');
  }

  async fillForm(data: Partial<BookingFormData>) {
    if (data.pickupAddress !== undefined) {
      await this.pickupAddressInput.fill(data.pickupAddress);
    }
    if (data.pickupPhone !== undefined) {
      await this.pickupPhoneInput.fill(data.pickupPhone);
    }
    if (data.destinationType !== undefined) {
      await this.destinationSelect.selectOption(data.destinationType);
    }
    if (data.dropoffAddress !== undefined) {
      await this.dropoffAddressInput.fill(data.dropoffAddress);
    }
    if (data.recipientName !== undefined) {
      await this.recipientNameInput.fill(data.recipientName);
    }
    if (data.recipientPhone !== undefined) {
      await this.recipientPhoneInput.fill(data.recipientPhone);
    }
    if (data.parcelSize !== undefined) {
      await this.parcelSizeSelect.selectOption(data.parcelSize);
    }
    if (data.weightKg !== undefined) {
      await this.weightKgInput.fill(data.weightKg);
    }
  }

  async waitForFeeEstimate() {
    await expect(this.feeBreakdown).toBeVisible({ timeout: 5000 });
  }

  async proceedToReview() {
    await expect(this.reviewButton).toBeEnabled({ timeout: 5000 });
    await this.reviewButton.click();
    await expect(this.reviewList).toBeVisible({ timeout: 5000 });
  }

  async confirmBooking(): Promise<string> {
    await expect(this.confirmButton).toBeEnabled();
    await this.confirmButton.click();
    await expect(this.bookedCard).toBeVisible({ timeout: 5000 });
    const trackingRef = (await this.trackingReferenceBox.textContent())?.trim() || '';
    expect(trackingRef).toMatch(/^ZT-[0-9A-Z]{10}$/);
    return trackingRef;
  }

  getFieldError(fieldId: string): Locator {
    return this.page.locator(`#${fieldId}-error, [id="${fieldId}"] ~ .sd-field-error`);
  }
}
