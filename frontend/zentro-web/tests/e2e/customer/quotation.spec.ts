import { test, expect } from '../fixtures/auth.fixture';
import { BookingPage } from '../pages/BookingPage';

test.describe('Customer Quotation & Pricing E2E', () => {
  test('Calculates base rate and updates fee dynamically as weight changes', async ({
    authenticatedPage,
  }) => {
    const bookingPage = new BookingPage(authenticatedPage);
    await bookingPage.goto();

    // Fill minimum required fields
    await bookingPage.fillForm({
      pickupAddress: '123 Galle Road, Colombo',
      pickupPhone: '077 111 2222',
      destinationType: 'SAME_CITY',
      dropoffAddress: '456 Kandy Road, Colombo',
      recipientName: 'Nimal',
      recipientPhone: '071 222 3333',
      weightKg: '1.0',
    });

    // Wait for initial estimate
    await bookingPage.waitForFeeEstimate();
    const initialText = await bookingPage.feeCard.textContent();
    expect(initialText).toContain('550'); // Base fee

    // Increase weight to 4.0 kg
    await bookingPage.weightKgInput.fill('4.0');
    // Debounce wait
    await authenticatedPage.waitForTimeout(500);
    await bookingPage.waitForFeeEstimate();

    const updatedText = await bookingPage.feeCard.textContent();
    expect(updatedText).toContain('Weight charge');
    expect(updatedText).not.toEqual(initialText);
  });

  test('Applies destination surcharge when other district is selected', async ({
    authenticatedPage,
  }) => {
    const bookingPage = new BookingPage(authenticatedPage);
    await bookingPage.goto();

    await bookingPage.fillForm({
      pickupAddress: '123 Galle Road, Colombo',
      pickupPhone: '077 111 2222',
      destinationType: 'SAME_CITY',
      dropoffAddress: 'Peradeniya Road, Kandy',
      recipientName: 'Anura',
      recipientPhone: '071 333 4444',
      weightKg: '2.0',
    });

    await bookingPage.waitForFeeEstimate();
    const initialFeeText = await bookingPage.feeCard.textContent();
    expect(initialFeeText).toContain('Distance surcharge');

    // Switch to OTHER_DISTRICT
    await bookingPage.destinationSelect.selectOption('OTHER_DISTRICT');
    await authenticatedPage.waitForTimeout(500);
    await bookingPage.waitForFeeEstimate();

    // Surcharge should now reflect 402 LKR
    await expect(bookingPage.feeCard).toContainText('402');
  });

  test('Review button is disabled until valid quotation is computed', async ({
    authenticatedPage,
  }) => {
    const bookingPage = new BookingPage(authenticatedPage);
    await bookingPage.goto();

    // Review button should be disabled initially
    await expect(bookingPage.reviewButton).toBeDisabled();

    // Fill valid weight
    await bookingPage.fillForm({
      pickupAddress: 'Colombo',
      pickupPhone: '077 111 2222',
      dropoffAddress: 'Galle',
      recipientName: 'Sunil',
      recipientPhone: '071 222 3333',
      weightKg: '1.5',
    });

    await bookingPage.waitForFeeEstimate();
    await expect(bookingPage.reviewButton).toBeEnabled();

    // Clear weight
    await bookingPage.weightKgInput.fill('');
    await authenticatedPage.waitForTimeout(400);
    await expect(bookingPage.reviewButton).toBeDisabled();
  });
});
