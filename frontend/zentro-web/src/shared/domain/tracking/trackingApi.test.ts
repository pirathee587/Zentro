import { describe, expect, it, vi, beforeEach } from 'vitest';
import { apiClient } from '@/shared/api/apiClient';
import { getTracking, fetchPublicTracking, type PublicTrackingResponse } from './trackingApi';

describe('trackingApi service module', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('getTracking issues GET request to approved /api/v1/tracking/{code} endpoint', async () => {
    const mockResponse: PublicTrackingResponse = {
      trackingRef: 'ZT-TEST12345',
      status: 'In progress',
      rawStatus: 'IN_TRANSIT',
      originCity: 'Colombo',
      destinationCity: 'Kandy',
      category: 'Standard',
      size: 'Medium',
      description: 'Test shipment',
      events: [],
      timeline: [],
    };

    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: mockResponse });

    const result = await getTracking('zt-test12345');

    expect(getSpy).toHaveBeenCalledWith('/api/v1/tracking/ZT-TEST12345');
    expect(result.data.trackingRef).toBe('ZT-TEST12345');
    expect(result.data.originCity).toBe('Colombo');
    expect(result.data.destinationCity).toBe('Kandy');
  });

  it('fetchPublicTracking returns tracking data on 200 success', async () => {
    const mockResponse: PublicTrackingResponse = {
      trackingRef: 'ZT-ABCD9999',
      status: 'Delivered',
      rawStatus: 'DELIVERED',
      originCity: 'Galle',
      destinationCity: 'Matara',
      events: [],
      timeline: [],
    };

    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: mockResponse });

    const data = await fetchPublicTracking('ZT-ABCD9999');
    expect(data).not.toBeNull();
    expect(data?.trackingRef).toBe('ZT-ABCD9999');
    expect(data?.status).toBe('Delivered');
  });

  it('fetchPublicTracking safely returns null on 404 not-found', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue({
      response: {
        status: 404,
        data: {
          code: 'TRACKING_NOT_FOUND',
          title: 'Tracking reference not found',
        },
      },
    });

    const data = await fetchPublicTracking('ZT-NONEXISTENT');
    expect(data).toBeNull();
  });

  it('fetchPublicTracking rethrows on server 500 error', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(new Error('Internal Server Error'));

    await expect(fetchPublicTracking('ZT-ERROR')).rejects.toThrow('Internal Server Error');
  });

  it('fetchPublicTracking returns null immediately for empty or whitespace string', async () => {
    const getSpy = vi.spyOn(apiClient, 'get');
    const data = await fetchPublicTracking('   ');
    expect(data).toBeNull();
    expect(getSpy).not.toHaveBeenCalled();
  });
});
