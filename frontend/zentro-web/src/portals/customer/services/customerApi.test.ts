import { describe, expect, it } from 'vitest';
import {
  getDashboard,
  estimateFee,
  createDelivery,
  listParcels,
  getParcel,
  subscribeToParcel,
  listReceipts,
  askAssistant,
  sendSupportMessage,
} from './customerApi';

describe('SwiftDrop customerApi service and mock adapter', () => {
  it('getDashboard returns metrics and recent parcels', async () => {
    const data = await getDashboard();
    expect(data).toBeDefined();
    expect(typeof data.activeCount).toBe('number');
    expect(data.formattedTotalSpent).toContain('LKR');
    expect(Array.isArray(data.recentParcels)).toBe(true);
  });

  it('estimateFee calculates fee without UI computation', async () => {
    const res = await estimateFee({
      pickupCity: 'Colombo 04',
      deliveryCity: 'Kandy',
      weightKg: 2.5,
      isExpress: true,
    });
    expect(res.fee).toBeGreaterThan(550);
    expect(res.formattedFee).toBe(`LKR ${res.fee.toLocaleString('en-US')}`);
    expect(res.estimatedDays).toBe(1);
  });

  it('createDelivery creates parcel with tracking reference and timeline', async () => {
    const parcel = await createDelivery({
      sender: {
        name: 'Amara',
        phone: '+94 77 123 4567',
        address: '42 Galle Rd',
        city: 'Colombo',
      },
      recipient: {
        name: 'Kamal',
        phone: '+94 71 456 7890',
        address: '10 Main St',
        city: 'Galle',
      },
      parcel: {
        description: 'Books',
        weightKg: 1.0,
        category: 'Documents',
      },
    });

    expect(parcel.id).toBeDefined();
    expect(parcel.trackingRef).toMatch(/^ZTR-SD[A-Z0-9]{8}$/);
    expect(parcel.status).toBe('Created');
    expect(parcel.timeline.length).toBeGreaterThanOrEqual(1);

    // Verify it is retrievable via getParcel
    const fetched = await getParcel(parcel.trackingRef);
    expect(fetched?.id).toBe(parcel.id);
  });

  it('listParcels returns list and count', async () => {
    const res = await listParcels();
    expect(res.parcels.length).toBeGreaterThan(0);
    expect(res.total).toBe(res.parcels.length);
  });

  it('subscribeToParcel notifies subscriber and returns unsubscribe function', async () => {
    let notified = false;
    const unsubscribe = subscribeToParcel('ZTR-SD8941K2M7', (p) => {
      if (p.trackingRef === 'ZTR-SD8941K2M7') {
        notified = true;
      }
    });

    await new Promise((r) => setTimeout(r, 50));
    expect(notified).toBe(true);
    expect(typeof unsubscribe).toBe('function');
    unsubscribe();
  });

  it('listReceipts returns official receipts formatted in LKR', async () => {
    const receipts = await listReceipts();
    expect(receipts.length).toBeGreaterThan(0);
    expect(receipts[0].formattedAmount).toContain('LKR');
  });

  it('askAssistant replies with suggested actions', async () => {
    const reply = await askAssistant('How do I track my parcel?');
    expect(reply.reply).toBeDefined();
    expect(reply.suggestedActions?.length).toBeGreaterThan(0);
  });

  it('sendSupportMessage submits support ticket with reference', async () => {
    const ticket = await sendSupportMessage({
      subject: 'Delay question',
      category: 'delivery',
      trackingRef: 'ZTR-SD8941K2M7',
      message: 'When is the parcel arriving in Kandy?',
    });

    expect(ticket.id).toBeDefined();
    expect(ticket.ticketNumber).toContain('TICK-');
    expect(ticket.status).toBe('Open');
  });
});
