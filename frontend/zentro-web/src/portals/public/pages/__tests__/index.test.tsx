import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { HomePage, LoginPage, MerchantLoginPage, SignupPage, StaffLoginPage } from '../index';
import { AuthContext, type Auth } from '@/shared/auth/authContext';

describe('public landing page', () => {
  it('does not show Team or Contact navigation', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).not.toHaveTextContent(/team|contact/i);
    expect(screen.getByRole('contentinfo')).not.toHaveTextContent(/team|contact/i);
  });

  it('rejects an invalid tracking number', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    fireEvent.change(screen.getByLabelText('Tracking number'), { target: { value: 'ZTR-ABCDEFG231' } });
    fireEvent.click(screen.getByRole('button', { name: /track parcel/i }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid tracking number');
  });

  it('accepts the required alphabet and routes valid numbers', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/track/:trackingNumber" element={<p>Tracking route opened</p>} />
        </Routes>
      </MemoryRouter>
    );
    fireEvent.change(screen.getByLabelText('Tracking number'), { target: { value: 'ZTR-ABCDEFG234' } });
    fireEvent.click(screen.getByRole('button', { name: /track parcel/i }));
    expect(screen.getByText('Tracking route opened')).toBeInTheDocument();
  });

  it('shows reveal content without movement when reduced motion is preferred', () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })));
    const { container } = render(<MemoryRouter><HomePage /></MemoryRouter>);
    expect(container.querySelector('.reveal')?.classList.contains('is-visible')).toBe(true);
    vi.unstubAllGlobals();
  });

  it('shows customer and business delivery tabs and the real service features', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    expect(screen.getByRole('tab', { name: 'Customers' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('Estimate and book')).toBeInTheDocument();
    expect(screen.getByText('Hand it over')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }));
    expect(screen.getByText('Apply for access')).toBeInTheDocument();
    expect(screen.getAllByText(/signed webhooks/i).length).toBeGreaterThan(0);
  });

  it('renders the interactive Sri Lanka delivery network map and feature cards', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    expect(screen.getByText('Delivery Network')).toBeInTheDocument();
    expect(screen.getByText('Connected hubs: Colombo, Kandy, Jaffna, Galle, Trincomalee')).toBeInTheDocument();
    expect(screen.getByText('Islandwide delivery network')).toBeInTheDocument();
    expect(screen.getByText('Care at every handoff')).toBeInTheDocument();
    expect(screen.getByText('Real-time parcel tracking')).toBeInTheDocument();
  });
});

describe('staff login page redirect', () => {
  it('redirects staff login to shared login page', () => {
    render(
      <MemoryRouter initialEntries={['/staff/login']}>
        <Routes>
          <Route path="/staff/login" element={<StaffLoginPage />} />
          <Route path="/login" element={<p>Shared Login Page</p>} />
        </Routes>
      </MemoryRouter>
    );
    expect(screen.getByText('Shared Login Page')).toBeInTheDocument();
  });
});

describe('merchant login page redirect', () => {
  it('redirects merchant login to shared login in merchant mode', () => {
    render(
      <MemoryRouter initialEntries={['/merchant/login']}>
        <Routes>
          <Route path="/merchant/login" element={<MerchantLoginPage />} />
          <Route path="/login" element={<p>Shared Login Page</p>} />
        </Routes>
      </MemoryRouter>
    );
    expect(screen.getByText('Shared Login Page')).toBeInTheDocument();
  });
});

describe('modern login page', () => {
  it('renders customer login page with form elements', () => {
    const auth: Auth = {
      user: null,
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthContext.Provider value={auth}>
          <LoginPage />
        </AuthContext.Provider>
      </MemoryRouter>
    );
    expect(screen.getByText('WELCOME BACK')).toBeInTheDocument();
    expect(screen.getByText(/good to see you/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/email address/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^password$/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /continue with google/i })).toBeInTheDocument();
  });

  it('switches to merchant mode when clicking Sign in as Merchant', () => {
    const auth: Auth = {
      user: null,
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthContext.Provider value={auth}>
          <LoginPage />
        </AuthContext.Provider>
      </MemoryRouter>
    );
    const merchantSwitchBtn = screen.getByRole('button', { name: /sign in as merchant/i });
    fireEvent.click(merchantSwitchBtn);
    expect(screen.getByText('MERCHANT ACCESS')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /sign in as customer/i })).toBeInTheDocument();
  });
});

describe('modern signup page', () => {
  it('opens customer registration form directly with all required fields', () => {
    const auth: Auth = {
      user: null,
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };
    render(
      <MemoryRouter initialEntries={['/register']}>
        <AuthContext.Provider value={auth}>
          <SignupPage />
        </AuthContext.Provider>
      </MemoryRouter>
    );
    expect(screen.getByText('JOIN ZENTRO')).toBeInTheDocument();
    expect(screen.getByText('Create an Account')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('John')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Doe')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Email Address')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Password')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Confirm Password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create account/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /continue with google/i })).toBeInTheDocument();
  });

  it('redirects already logged-in customer to customer dashboard', () => {
    const auth: Auth = {
      user: { id: 'cust-1', name: 'John Doe', roles: ['CUSTOMER'], accessToken: 'token' },
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };
    render(
      <MemoryRouter initialEntries={['/register']}>
        <AuthContext.Provider value={auth}>
          <Routes>
            <Route path="/register" element={<SignupPage />} />
            <Route path="/customer" element={<p>Customer Dashboard</p>} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>
    );
    expect(screen.getByText('Customer Dashboard')).toBeInTheDocument();
  });
});
