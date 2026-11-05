import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { createMerchantApplication, getMerchantApplication } from '../../api/merchantApi';
import { MerchantApplicationPage } from '../MerchantApplicationPage';

vi.mock('../../api/merchantApi', () => ({
  createMerchantApplication: vi.fn(),
  getMerchantApplication: vi.fn(),
  getMerchantApiKey: vi.fn(),
  issueMerchantApiKey: vi.fn(),
  revokeMerchantApiKey: vi.fn(),
}));

const auth: Auth = {
  user: { id: 'customer-1', roles: ['CUSTOMER'], accessToken: 'merchant-token' },
  setUser: vi.fn(),
  login: vi.fn(async () => undefined),
  register: vi.fn(async () => undefined),
  logout: vi.fn(async () => undefined),
  loading: false,
};

function renderApplicationPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <AuthContext.Provider value={auth}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/merchant/register-business']}>
          <Routes>
            <Route path="/merchant/status" element={<h1>Merchant status opened</h1>} />
            <Route path="*" element={<MerchantApplicationPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </AuthContext.Provider>,
  );
}

describe('MerchantApplicationPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows registration when no application exists and submits the current contract', async () => {
    vi.mocked(getMerchantApplication).mockRejectedValueOnce({ response: { status: 404 } });
    vi.mocked(createMerchantApplication).mockResolvedValueOnce({
      businessName: 'Good Things',
      status: 'PENDING',
    });
    renderApplicationPage();

    fireEvent.change(await screen.findByLabelText('Business name'), { target: { value: ' Good Things ' } });
    fireEvent.change(screen.getByLabelText('Business registration number'), { target: { value: ' BR-100 ' } });
    fireEvent.change(screen.getByLabelText('Contact phone'), { target: { value: '+94 (77) 000-0000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit for review' }));

    expect(await screen.findByRole('heading', { name: 'Merchant status opened' })).toBeInTheDocument();
    await waitFor(() => expect(createMerchantApplication).toHaveBeenCalledWith(
      { accessToken: 'merchant-token', refreshAccessToken: undefined },
      { businessName: 'Good Things', businessRegNo: 'BR-100', contactPhone: '+94770000000' },
    ));
  });

  it('validates phone numbers before sending the registration request', async () => {
    vi.mocked(getMerchantApplication).mockRejectedValueOnce({ response: { status: 404 } });
    renderApplicationPage();

    fireEvent.change(await screen.findByLabelText('Contact phone'), { target: { value: '123' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit for review' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a phone number with 9 to 15 digits.');
    expect(createMerchantApplication).not.toHaveBeenCalled();
  });
});
