import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext } from '@/shared/auth/authContext';
import { CourierProfilePage } from './CourierProfilePage';
import * as courierApi from '../api/courierApi';

const mockCourierProfile: courierApi.CourierProfile = {
  id: 'cp-0000-1111-2222',
  userId: 'usr-3333-4444-5555',
  email: 'courier.perera@zentro.lk',
  name: 'Sunil Perera',
  phone: '+94712345678',
  active: true,
  accountStatus: 'ACTIVE',
  assignedZone: 'Colombo Central',
  vehicleType: 'MOTORCYCLE',
  createdAt: '2026-10-15T08:00:00Z',
  updatedAt: '2026-10-28T14:30:00Z',
};

const mockAuth = {
  user: {
    id: 'usr-3333-4444-5555',
    name: 'Sunil Perera',
    roles: ['COURIER' as const],
    accessToken: 'mock-courier-token',
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
      <AuthContext.Provider value={mockAuth as never}>
        <MemoryRouter>
          <CourierProfilePage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>
  );
}

describe('CourierProfilePage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders courier profile personal details and dispatcher-managed attributes', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue(mockCourierProfile);

    renderComponent();

    expect(screen.getByText('Loading your courier profile…')).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.queryByText('Loading your courier profile…')).not.toBeInTheDocument();
    });

    // Check personal fields in form
    const nameInput = screen.getByLabelText(/Full Name/i) as HTMLInputElement;
    const phoneInput = screen.getByLabelText(/Phone Number/i) as HTMLInputElement;
    const emailInput = screen.getByLabelText(/Account Email/i) as HTMLInputElement;

    expect(nameInput.value).toBe('Sunil Perera');
    expect(phoneInput.value).toBe('+94712345678');
    expect(emailInput.value).toBe('courier.perera@zentro.lk');
    expect(emailInput).toBeDisabled();

    // Check operational attributes
    expect(screen.getByText('Colombo Central')).toBeInTheDocument();
    expect(screen.getByText('MOTORCYCLE')).toBeInTheDocument();
    expect(screen.getByText('Active & Approved')).toBeInTheDocument();
    expect(screen.getByText('Eligible for Dispatch')).toBeInTheDocument();
    expect(screen.getByText('cp-0000-1111-2222')).toBeInTheDocument();
  });

  it('shows missing profile 404 state and allows initialization/provisioning', async () => {
    const error404 = new Error('Not found') as Error & { response: { status: number } };
    error404.response = { status: 404 };
    vi.spyOn(courierApi, 'getCourierProfile').mockRejectedValue(error404);
    const provisionSpy = vi
      .spyOn(courierApi, 'provisionCourierProfile')
      .mockResolvedValue(mockCourierProfile);

    renderComponent();

    await waitFor(() => {
      expect(screen.getByText('No courier profile registered yet')).toBeInTheDocument();
    });

    const initButton = screen.getByRole('button', { name: /Initialize Courier Profile/i });
    expect(initButton).toBeInTheDocument();

    fireEvent.click(initButton);

    await waitFor(() => {
      expect(provisionSpy).toHaveBeenCalled();
    });
  });

  it('validates name input and shows error when empty', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue(mockCourierProfile);
    const updateSpy = vi.spyOn(courierApi, 'updateCourierPersonalInfo');

    renderComponent();

    await waitFor(() => {
      expect(screen.getByLabelText(/Full Name/i)).toBeInTheDocument();
    });

    const nameInput = screen.getByLabelText(/Full Name/i);
    fireEvent.change(nameInput, { target: { value: '   ' } });

    const submitBtn = screen.getByRole('button', { name: /Save Personal Details/i });
    fireEvent.click(submitBtn);

    expect(await screen.findByRole('alert')).toHaveTextContent('Display name cannot be empty.');
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('submits updated personal details and displays success banner', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue(mockCourierProfile);
    const updateSpy = vi
      .spyOn(courierApi, 'updateCourierPersonalInfo')
      .mockResolvedValue(undefined);

    renderComponent();

    await waitFor(() => {
      expect(screen.getByLabelText(/Full Name/i)).toBeInTheDocument();
    });

    const nameInput = screen.getByLabelText(/Full Name/i);
    const phoneInput = screen.getByLabelText(/Phone Number/i);

    fireEvent.change(nameInput, { target: { value: 'Sunil P. Updated' } });
    fireEvent.change(phoneInput, { target: { value: '+94779998877' } });

    const submitBtn = screen.getByRole('button', { name: /Save Personal Details/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledWith(
        { name: 'Sunil P. Updated', phone: '+94779998877' },
        'mock-courier-token'
      );
    });

    expect(
      await screen.findByText('Your personal details have been updated successfully.')
    ).toBeInTheDocument();
  });
});
