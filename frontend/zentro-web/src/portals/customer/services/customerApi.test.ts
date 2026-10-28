import { describe, expect, it, vi, afterEach } from 'vitest';
import { apiClient } from '@/shared/api/apiClient';
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
  normalizeListParcelsResponse,
  setCustomerApiConfig,
} from './customerApi';

describe('SwiftDrop customerApi service and mock adapter', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    setCustomerApiConfig({ useMocks: true, baseUrl: '/api/v1/customer', deliveriesEndpoint: undefined });
  });

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

  it('listParcels returns list and count using mock adapter', async () => {
    const res = await listParcels();
    expect(res.parcels.length).toBeGreaterThan(0);
    expect(res.total).toBe(res.parcels.length);
  });

  it('listParcels queries real apiClient and normalizes Spring Data Page when useMocks is false', async () => {
    setCustomerApiConfig({ useMocks: false, baseUrl: '/api/v1/customer' });

    const springPageResponse = {
      content: [
        {
          id: 'pcl-srv-1',
          trackingRef: 'ZTR-REAL001',
          status: 'IN_PROGRESS',
          fee: 1200,
          createdAt: '2026-10-25T10:00:00Z',
          sender: { name: 'Sita', city: 'Colombo' },
          recipient: { name: 'Ravi', city: 'Jaffna' },
        },
      ],
      totalElements: 1,
      totalPages: 1,
    };

    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: springPageResponse } as never);

    const res = await listParcels({ page: 1, limit: 10, status: 'In progress' });

    expect(getSpy).toHaveBeenCalledWith('/api/v1/customer/parcels', {
      params: { page: 1, limit: 10, status: 'In progress' },
    });
    expect(res.total).toBe(1);
    expect(res.parcels.length).toBe(1);
    expect(res.parcels[0].trackingRef).toBe('ZTR-REAL001');
    expect(res.parcels[0].status).toBe('In progress');
    expect(res.parcels[0].formattedFee).toBe('LKR 1,200');
  });

  it('listParcels respects custom deliveriesEndpoint when configured', async () => {
    setCustomerApiConfig({
      useMocks: false,
      baseUrl: '/api/v1/customer',
      deliveriesEndpoint: '/api/v1/tracking/mine',
    });

    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValueOnce({
      data: { parcels: [], total: 0 },
    } as never);

    await listParcels({ page: 1 });

    expect(getSpy).toHaveBeenCalledWith('/api/v1/tracking/mine', {
      params: { page: 1 },
    });
  });

  it('normalizeListParcelsResponse handles plain array and computes totalSpent', () => {
    const rawArray = [
      { id: '1', trackingRef: 'ZTR-1', status: 'DELIVERED', fee: 500 },
      { id: '2', trackingRef: 'ZTR-2', status: 'FAILED', fee: 800 },
    ];

    const normalized = normalizeListParcelsResponse(rawArray);
    expect(normalized.total).toBe(2);
    expect(normalized.parcels.length).toBe(2);
    expect(normalized.parcels[0].status).toBe('Delivered');
    expect(normalized.parcels[1].status).toBe('Failed');
    expect(normalized.totalSpent).toBe(1300);
    expect(normalized.formattedTotalSpent).toBe('LKR 1,300');
  });

  it('normalizeListParcelsResponse handles deliveries or data wrapped responses', () => {
    const wrapped = {
      deliveries: [
        { id: 'd-1', trackingRef: 'ZTR-D1', status: 'In progress', fee: 400 },
      ],
      total: 1,
    };
    const normalized = normalizeListParcelsResponse(wrapped);
    expect(normalized.total).toBe(1);
    expect(normalized.parcels.length).toBe(1);
    expect(normalized.parcels[0].trackingRef).toBe('ZTR-D1');
    expect(normalized.parcels[0].status).toBe('In progress');
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
