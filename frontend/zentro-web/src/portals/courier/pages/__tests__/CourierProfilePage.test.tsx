import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext } from '@/shared/auth/authContext';
import { auditAccessibility } from '@/shared/testing/accessibility';
import { CourierProfilePage } from '../CourierProfilePage';
import * as courierApi from '../../api/courierApi';

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

function renderComponent(user: unknown = mockAuth.user) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const tree = (value: unknown) => (
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={{ ...mockAuth, user: value } as never}>
        <MemoryRouter>
          <CourierProfilePage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>
  );
  const result = render(tree(user));
  return { ...result, changeAccount: (value: unknown) => result.rerender(tree(value)) };
}

describe('CourierProfilePage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders courier profile personal details and dispatcher-managed attributes', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue(mockCourierProfile);

    renderComponent();

    expect(screen.getByText(/Loading your courier profile/)).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.queryByText(/Loading your courier profile/)).not.toBeInTheDocument();
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
    expect(screen.queryByText(mockCourierProfile.id)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Plate number')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.getByText('MOTORCYCLE')).toBeInTheDocument();
    expect(screen.getByText('Profile active')).toBeInTheDocument();
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
    expect(screen.queryByText('cp-0000-1111-2222')).not.toBeInTheDocument();
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
    expect(nameInput).toHaveFocus();
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
        'mock-courier-token',
      );
    });

    expect(
      await screen.findByText('Your personal details have been updated successfully.'),
    ).toBeInTheDocument();
  });
  it('does not render fake defaults while loading or after failure', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockRejectedValue(new Error('private'));
    renderComponent();
    expect(await screen.findByRole('alert')).toHaveTextContent('Profile unavailable');
    expect(screen.queryByLabelText(/Full Name/)).not.toBeInTheDocument();
    expect(screen.queryByText('Saman Perera')).not.toBeInTheDocument();
    expect(screen.queryByText('Colombo Central')).not.toBeInTheDocument();
  });
  it('shows missing operational fields without inventing assignments', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue({
      ...mockCourierProfile,
      assignedZone: null,
      vehicleType: null,
      phone: '',
      email: '',
    });
    renderComponent();
    await screen.findByLabelText(/Full Name/);
    expect(screen.getAllByText('Not assigned')).toHaveLength(2);
    expect(screen.getByLabelText(/Phone Number/)).toHaveValue('');
  });
  it('does not label an inactive account eligible even if the profile flag is true', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue({
      ...mockCourierProfile,
      accountStatus: 'SUSPENDED',
      active: true,
    });
    renderComponent();
    expect(await screen.findByText('Profile inactive')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save Personal Details' })).toBeDisabled();
  });
  it('does not request a profile without a session', () => {
    const read = vi.spyOn(courierApi, 'getCourierProfile');
    renderComponent(null);
    expect(screen.getByText('Sign in to view your courier profile.')).toBeInTheDocument();
    expect(read).not.toHaveBeenCalled();
  });
  it('reports a failed save without claiming success', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue(mockCourierProfile);
    vi.spyOn(courierApi, 'updateCourierPersonalInfo').mockRejectedValue(new Error('private'));
    renderComponent();
    await screen.findByLabelText(/Full Name/);
    fireEvent.click(screen.getByRole('button', { name: 'Save Personal Details' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not save profile changes');
    expect(
      screen.queryByText('Your personal details have been updated successfully.'),
    ).not.toBeInTheDocument();
  });
  it('cannot clear a phone when the existing API would retain it', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue(mockCourierProfile);
    const save = vi.spyOn(courierApi, 'updateCourierPersonalInfo');
    renderComponent();
    await screen.findByLabelText(/Phone Number/);
    fireEvent.change(screen.getByLabelText(/Phone Number/), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Personal Details' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be cleared');
    expect(screen.getByLabelText(/Phone Number/)).toHaveFocus();
    expect(save).not.toHaveBeenCalled();
  });

  it('remounts editable fields when the authenticated courier changes', async () => {
    vi.spyOn(courierApi, 'getCourierProfile')
      .mockResolvedValueOnce(mockCourierProfile)
      .mockResolvedValue({
        ...mockCourierProfile,
        name: 'Other fixture courier',
        phone: '0779876543',
      });
    const view = renderComponent();
    await screen.findByLabelText(/Full Name/);
    view.changeAccount({ ...mockAuth.user, id: 'other-user', accessToken: 'other-token' });
    await waitFor(() =>
      expect(screen.getByLabelText(/Full Name/)).toHaveValue('Other fixture courier'),
    );
    expect(screen.getByLabelText(/Phone Number/)).toHaveValue('0779876543');
  });
  it('shows a sign-in action on expired authentication without rendering an editable profile', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockRejectedValue({
      isAxiosError: true,
      response: { status: 401 },
    });
    renderComponent();
    fireEvent.click(await screen.findByRole('button', { name: 'Sign in again' }));
    expect(mockAuth.login).toHaveBeenCalledWith('/courier/profile', undefined, 'staff');
    expect(screen.queryByLabelText(/Full Name/)).not.toBeInTheDocument();
  });
  it('supports labeled keyboard-accessible fields without DOM accessibility violations', async () => {
    vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue(mockCourierProfile);
    const view = renderComponent();
    await screen.findByLabelText(/Full Name/);
    const report = await auditAccessibility(view.container);
    expect(report.violations).toEqual([]);
  });
  it('reloads saved values explicitly without an extra provisioning GET after a save', async () => {
    const read = vi.spyOn(courierApi, 'getCourierProfile').mockResolvedValue(mockCourierProfile);
    vi.spyOn(courierApi, 'updateCourierPersonalInfo').mockResolvedValue(undefined);
    renderComponent();
    await screen.findByLabelText(/Full Name/);
    fireEvent.change(screen.getByLabelText(/Full Name/), { target: { value: 'Saved fixture' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Personal Details' }));
    await screen.findByText('Your personal details have been updated successfully.');
    expect(read).toHaveBeenCalledTimes(1);
    read.mockResolvedValue({ ...mockCourierProfile, name: 'Server fixture' });
    fireEvent.click(screen.getByRole('button', { name: /Reload saved profile/ }));
    await waitFor(() => expect(screen.getByLabelText(/Full Name/)).toHaveValue('Server fixture'));
  });
});
