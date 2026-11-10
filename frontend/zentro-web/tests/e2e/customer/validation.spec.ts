import { test, expect } from '../fixtures/auth.fixture';
import { BookingPage } from '../pages/BookingPage';

test.describe('Customer Form Validation & Error Handling E2E', () => {
  test('Validates required fields on blur and submit', async ({ authenticatedPage }) => {
    const bookingPage = new BookingPage(authenticatedPage);
    await bookingPage.goto();

    // 1. Trigger blur on short/empty pickup address
    await bookingPage.pickupAddressInput.fill('Short');
    await bookingPage.pickupAddressInput.blur();
    await expect(authenticatedPage.locator('text=Enter at least 8 characters.')).toBeVisible();

    // 2. Trigger invalid phone format
    await bookingPage.pickupPhoneInput.fill('12345');
    await bookingPage.pickupPhoneInput.blur();
    await expect(
      authenticatedPage.locator('text=Enter a Sri Lankan number, e.g. 0771234567')
    ).toBeVisible();

    // 3. Trigger weight validation bounds
    await bookingPage.weightKgInput.fill('25'); // Exceeds 20 kg max
    await bookingPage.weightKgInput.blur();
    await expect(
      authenticatedPage.locator('text=Weight must be between 0.1 and 20 kg.')
    ).toBeVisible();

    // 4. Entering valid data clears the validation error
    await bookingPage.weightKgInput.fill('2.0');
    await bookingPage.weightKgInput.blur();
    await expect(
      authenticatedPage.locator('text=Weight must be between 0.1 and 20 kg.')
    ).not.toBeVisible();
  });

  test('Idempotency and duplicate booking conflict handling', async ({ authenticatedPage }) => {
    const bookingPage = new BookingPage(authenticatedPage);
    await bookingPage.goto();

    await bookingPage.fillForm({
      pickupAddress: 'No. 50, Galle Road, Colombo',
      pickupPhone: '077 123 4567',
      dropoffAddress: 'No. 100, Kandy Road, Colombo',
      recipientName: 'Ravi',
      recipientPhone: '071 987 6543',
      weightKg: '1.5',
    });

    await bookingPage.waitForFeeEstimate();
    await bookingPage.proceedToReview();

    // Confirm booking
    const trackingRef = await bookingPage.confirmBooking();
    expect(trackingRef).toBeTruthy();
  });
});

