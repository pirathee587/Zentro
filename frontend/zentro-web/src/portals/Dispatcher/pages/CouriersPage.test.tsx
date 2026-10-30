import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext } from '@/shared/auth/authContext';
import { CouriersPage } from './CouriersPage';
import * as courierAdminApi from '../api/courierAdminApi';
import type { CourierProfile } from '@/portals/courier/api/courierApi';

const mockCouriers: CourierProfile[] = [
  {
    id: 'cp-1',
    userId: 'usr-1',
    email: 'kamal.courier@zentro.lk',
    name: 'Kamal Silva',
    phone: '+94711112222',
    active: true,
    accountStatus: 'ACTIVE',
    assignedZone: 'Colombo Central',
    vehicleType: 'VAN',
    createdAt: '2026-10-10T10:00:00Z',
    updatedAt: '2026-10-25T11:00:00Z',
  },
  {
    id: 'cp-2',
    userId: 'usr-2',
    email: 'nimal.courier@zentro.lk',
    name: 'Nimal Fernando',
    phone: '+94722223333',
    active: false,
    accountStatus: 'ACTIVE',
    assignedZone: 'Kandy Hub',
    vehicleType: 'MOTORCYCLE',
    createdAt: '2026-10-12T09:00:00Z',
    updatedAt: '2026-10-26T12:00:00Z',
  },
];

const mockDispatcherAuth = {
  user: {
    id: 'dispatcher-1',
    name: 'Dilshan Dispatcher',
    roles: ['DISPATCHER' as const],
    accessToken: 'mock-dispatcher-token',
  },
  setUser: vi.fn(),
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  loading: false,
};

function renderComponent() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={mockDispatcherAuth as never}>
        <MemoryRouter>
          <CouriersPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>
  );
}

describe('CouriersPage (Dispatcher Portal)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders registered courier directory with operational details', async () => {
    vi.spyOn(courierAdminApi, 'listCouriersAdmin').mockResolvedValue(mockCouriers);

    renderComponent();

    await waitFor(() => {
      expect(screen.queryByText('Loading courier directory…')).not.toBeInTheDocument();
    });

    expect(screen.getByText('Kamal Silva')).toBeInTheDocument();
    expect(screen.getByText('kamal.courier@zentro.lk • +94711112222')).toBeInTheDocument();
    expect(screen.getByText('Colombo Central')).toBeInTheDocument();
    expect(screen.getByText('VAN')).toBeInTheDocument();

    expect(screen.getByText('Nimal Fernando')).toBeInTheDocument();
    expect(screen.getByText('Kandy Hub')).toBeInTheDocument();
    expect(screen.getByText('MOTORCYCLE')).toBeInTheDocument();
  });

  it('opens edit modal and submits courier operational updates', async () => {
    vi.spyOn(courierAdminApi, 'listCouriersAdmin').mockResolvedValue(mockCouriers);
    const updateSpy = vi.spyOn(courierAdminApi, 'updateCourierAdmin').mockResolvedValue({
      ...mockCouriers[0],
      assignedZone: 'Galle Zone',
      vehicleType: 'TRUCK',
    });

    renderComponent();

    await waitFor(() => {
      expect(screen.getByText('Kamal Silva')).toBeInTheDocument();
    });

    const editBtns = screen.getAllByRole('button', { name: /Edit profile for/i });
    fireEvent.click(editBtns[0]);

    // Modal should be open
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Edit Courier Profile' })).toBeInTheDocument();

    const zoneInput = screen.getByLabelText(/Assigned Delivery Zone/i);
    const vehicleSelect = screen.getByLabelText(/Vehicle Category/i);

    fireEvent.change(zoneInput, { target: { value: 'Galle Zone' } });
    fireEvent.change(vehicleSelect, { target: { value: 'TRUCK' } });

    const saveBtn = screen.getByRole('button', { name: 'Save Changes' });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledWith(
        'cp-1',
        {
          active: true,
          assignedZone: 'Galle Zone',
          vehicleType: 'TRUCK',
          name: 'Kamal Silva',
          phone: '+94711112222',
        },
        'mock-dispatcher-token'
      );
    });

    expect(await screen.findByText('Courier profile successfully updated.')).toBeInTheDocument();
  });

  it('filters couriers when search text or status is changed', async () => {
    const listSpy = vi.spyOn(courierAdminApi, 'listCouriersAdmin').mockResolvedValue([mockCouriers[0]]);

    renderComponent();

    await waitFor(() => {
      expect(screen.getByText('Kamal Silva')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/Search by name, email, phone…/i);
    fireEvent.change(searchInput, { target: { value: 'Kamal' } });

    await waitFor(() => {
      expect(listSpy).toHaveBeenCalledWith(
        expect.objectContaining({ search: 'Kamal' }),
        'mock-dispatcher-token'
      );
    });
  });
});
