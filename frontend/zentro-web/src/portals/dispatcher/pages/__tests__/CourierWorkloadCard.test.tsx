import { describe, expect, it, vi, beforeEach } from 'vitest';
import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext } from '@/shared/auth/authContext';
import { CourierWorkloadCard } from '../CourierWorkloadCard';
import { UnassignedParcelsPage } from '../index';
import * as workloadApi from '../../api/workloadApi';
import { apiClient } from '@/shared/api/apiClient';

const workloadResponse = {
  items: [
    {
      courierId: '11111111-1111-4111-8111-111111111111',
      userId: '22222222-2222-4222-8222-222222222222',
      name: 'Kamal Silva',
      active: true,
      accountStatus: 'ACTIVE',
      assignmentCount: 3,
      availability: 'AVAILABLE' as const,
    },
    {
      courierId: '33333333-3333-4333-8333-333333333333',
      userId: '44444444-4444-4444-8444-444444444444',
      name: 'Nimal Perera',
      active: true,
      accountStatus: 'ACTIVE',
      assignmentCount: 1,
      availability: 'AVAILABLE' as const,
    },
  ],
  total: 2,
  overloadConfigured: false as const,
};

const auth = {
  user: { id: 'dispatcher-1', name: 'Dispatcher Kamal', roles: ['DISPATCHER' as const], accessToken: 'token-xyz' },
  setUser: vi.fn(),
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  loading: false,
};

function renderCard(ui = <CourierWorkloadCard />) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={auth as never}>
        <MemoryRouter>{ui}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe('CourierWorkloadCard', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows loading state initially', () => {
    vi.spyOn(workloadApi, 'getCourierWorkload').mockReturnValue(new Promise(() => {}));
    renderCard();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('renders live courier count and link to workload page', async () => {
    vi.spyOn(workloadApi, 'getCourierWorkload').mockResolvedValue(workloadResponse);
    renderCard();
    expect(await screen.findByText('Open courier workload')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /Courier workload/ });
    expect(link).toHaveAttribute('href', '/dispatcher/workload');
    expect(link).toHaveTextContent('2');
  });

  it('shows error state when courier workload request fails', async () => {
    vi.spyOn(workloadApi, 'getCourierWorkload').mockRejectedValue(new Error('Network error'));
    renderCard();
    expect(await screen.findByText('Unavailable')).toBeInTheDocument();
    expect(screen.getByText('Could not load workload · Open to retry')).toBeInTheDocument();
  });

  it('renders correctly inside UnassignedParcelsPage overview', async () => {
    vi.spyOn(workloadApi, 'getCourierWorkload').mockResolvedValue(workloadResponse);
    vi.spyOn(apiClient, 'get').mockImplementation(async (url) => {
      if (url === '/api/v1/dispatch/queue') {
        return { data: { items: [], total: 0, matchingTotal: 0, complete: true } };
      }
      if (url === '/api/v1/admin/merchants') {
        return { data: [] };
      }
      return { data: [] };
    });

    renderCard(<UnassignedParcelsPage />);
    expect(screen.getByRole('heading', { name: /Good day, Dispatcher/ })).toBeInTheDocument();
    expect(await screen.findByText('Open courier workload')).toBeInTheDocument();
    const workloadLink = screen.getByRole('link', { name: /Courier workload/ });
    expect(workloadLink).toHaveAttribute('href', '/dispatcher/workload');
    expect(workloadLink).toHaveTextContent('2');
    const queueLink = await screen.findByRole('link', { name: /Dispatch queue/ });
    expect(queueLink).toHaveAttribute('href', '/dispatcher/queue');
  });
});
