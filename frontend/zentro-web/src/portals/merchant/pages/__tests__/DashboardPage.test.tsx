import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DashboardPage } from '../DashboardPage';

const mockUseAuth = vi.fn();
const mockUseMerchantApplicationQuery = vi.fn();
const mockUseMerchantApiKeyQuery = vi.fn();
const mockUseMerchantOrdersQuery = vi.fn();
const mockUseMerchantOrderSummaryQuery = vi.fn();
const mockUseMerchantBillingQuery = vi.fn();
const mockUseMerchantWebhookQuery = vi.fn();
const mockUseMerchantWebhookDeliveriesQuery = vi.fn();

vi.mock('@/shared/auth/useAuth', () => ({ useAuth: () => mockUseAuth() }));
vi.mock('../../hooks/useMerchantQueries', () => ({
  useMerchantApplicationQuery: () => mockUseMerchantApplicationQuery(),
  useMerchantApiKeyQuery: () => mockUseMerchantApiKeyQuery(),
  useMerchantOrdersQuery: () => mockUseMerchantOrdersQuery(),
  useMerchantOrderSummaryQuery: () => mockUseMerchantOrderSummaryQuery(),
  useMerchantBillingQuery: () => mockUseMerchantBillingQuery(),
  useMerchantWebhookQuery: () => mockUseMerchantWebhookQuery(),
  useMerchantWebhookDeliveriesQuery: () => mockUseMerchantWebhookDeliveriesQuery(),
}));

function renderDashboard() {
  return render(<MemoryRouter><DashboardPage /></MemoryRouter>);
}

describe('DashboardPage orders', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseAuth.mockReturnValue({ user: { id: 'merchant-1', name: 'Asha Merchant', roles: ['MERCHANT'], merchantStatus: 'APPROVED' } });
    mockUseMerchantApplicationQuery.mockReturnValue({ isLoading: false, isError: false, data: { businessName: 'Asha Store', status: 'APPROVED' } });
    mockUseMerchantApiKeyQuery.mockReturnValue({ isLoading: false, isError: false, data: { active: true, prefix: 'zn_live' } });
    mockUseMerchantOrderSummaryQuery.mockReturnValue({ isLoading: false, isError: false, isSuccess: true, data: { totalOrders: 0, deliveredOrders: 0, failedOrders: 0, statusCounts: {} } });
    mockUseMerchantBillingQuery.mockReturnValue({ isLoading: false, isError: false, isSuccess: true, data: { month: '2026-11', orderCount: 0, quotedFeeTotal: 0, currency: 'LKR', orders: [] } });
    mockUseMerchantWebhookQuery.mockReturnValue({ isLoading: false, isError: false, data: null });
    mockUseMerchantWebhookDeliveriesQuery.mockReturnValue({ isLoading: false, isError: false, data: { items: [] } });
  });

  it('shows loading state while orders are loading', () => {
    mockUseMerchantOrdersQuery.mockReturnValue({ isLoading: true, isError: false });
    renderDashboard();
    expect(screen.getByRole('status', { name: 'Loading your orders' })).toBeInTheDocument();
  });

  it('shows an empty state when the merchant has no orders', () => {
    mockUseMerchantOrdersQuery.mockReturnValue({ isLoading: false, isError: false, isSuccess: true, data: [] });
    renderDashboard();
    expect(screen.getByText('No orders yet.')).toBeInTheDocument();
    expect(screen.getByText(/LKR\s?0\.00/i)).toBeInTheDocument();
    expect(screen.getByText('Orders this month')).toBeInTheDocument();
    expect(screen.getByText('Delivered this month')).toBeInTheDocument();
    expect(screen.queryByText(/billing summary API is required/i)).not.toBeInTheDocument();
  });

  it('shows unavailable billing state when the billing API fails', () => {
    mockUseMerchantOrdersQuery.mockReturnValue({ isLoading: false, isError: false, isSuccess: true, data: [] });
    mockUseMerchantBillingQuery.mockReturnValue({ isLoading: false, isError: true, isSuccess: false });
    renderDashboard();
    expect(screen.getByText('Could not load the monthly billing summary.')).toBeInTheDocument();
    expect(screen.getAllByText('Unavailable').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Retry billing summary' })).toBeInTheDocument();
  });

  it('offers a retry when the monthly order summary fails', () => {
    mockUseMerchantOrderSummaryQuery.mockReturnValue({ isLoading: false, isError: true, isSuccess: false, refetch: vi.fn() });
    renderDashboard();
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load order statuses.');
    expect(screen.getByRole('button', { name: 'Retry order summary' })).toBeInTheDocument();
  });

  it('shows an error state and retry action when orders cannot load', () => {
    mockUseMerchantOrdersQuery.mockReturnValue({ isLoading: false, isError: true, error: new Error('failed'), refetch: vi.fn() });
    renderDashboard();
    expect(screen.getByText('We could not load your orders. Please try again.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('shows order status and does not expose the merchant identifier', () => {
    mockUseMerchantOrdersQuery.mockReturnValue({
      isLoading: false,
      isError: false,
      isSuccess: true,
      data: [{ id: 'order-1', merchantId: 'secret-merchant-id', externalOrderRef: 'ORD-1', trackingRef: 'ZT-1', createdAt: '2026-11-03T10:15:30Z', status: 'IN_TRANSIT', currency: 'LKR', fee: 670 }],
    });
    renderDashboard();
    expect(screen.getByText('In transit')).toBeInTheDocument();
    expect(screen.getByText('ORD-1')).toBeInTheDocument();
    expect(screen.queryByText('secret-merchant-id')).not.toBeInTheDocument();
  });
});
