import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DashboardPage } from '../DashboardPage';

const mockUseAuth = vi.fn();
const mockUseMerchantApplicationQuery = vi.fn();
const mockUseMerchantApiKeyQuery = vi.fn();
const mockUseMerchantOrdersQuery = vi.fn();

vi.mock('@/shared/auth/useAuth', () => ({ useAuth: () => mockUseAuth() }));
vi.mock('../../hooks/useMerchantQueries', () => ({
  useMerchantApplicationQuery: () => mockUseMerchantApplicationQuery(),
  useMerchantApiKeyQuery: () => mockUseMerchantApiKeyQuery(),
  useMerchantOrdersQuery: () => mockUseMerchantOrdersQuery(),
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
  });

  it('shows loading state while orders are loading', () => {
    mockUseMerchantOrdersQuery.mockReturnValue({ isLoading: true, isError: false });
    renderDashboard();
    expect(screen.getByRole('status', { name: 'Loading your orders' })).toBeInTheDocument();
  });

  it('shows an empty state when the merchant has no orders', () => {
    mockUseMerchantOrdersQuery.mockReturnValue({ isLoading: false, isError: false, data: [] });
    renderDashboard();
    expect(screen.getByText('No orders yet.')).toBeInTheDocument();
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
      data: [{ id: 'order-1', merchantId: 'secret-merchant-id', externalOrderRef: 'ORD-1', trackingRef: 'ZT-1', createdAt: '2026-11-03T10:15:30Z', status: 'IN_TRANSIT', currency: 'LKR', fee: 670 }],
    });
    renderDashboard();
    expect(screen.getByText('In transit')).toBeInTheDocument();
    expect(screen.getByText('ORD-1')).toBeInTheDocument();
    expect(screen.queryByText('secret-merchant-id')).not.toBeInTheDocument();
  });
});
