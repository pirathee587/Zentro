import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { HomePage, LoginPage, MerchantLoginPage, SignupPage, StaffLoginPage } from './index';
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
    render(<MemoryRouter initialEntries={['/']}><Routes><Route path="/" element={<HomePage />} /><Route path="/track/:trackingNumber" element={<p>Tracking route opened</p>} /></Routes></MemoryRouter>);
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
    expect(screen.getByText('Clear fee estimates')).toBeInTheDocument();
    expect(screen.getByText('PDF receipts')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }));
    expect(screen.getByText('Apply for access')).toBeInTheDocument();
    expect(screen.getAllByText(/signed webhooks/i).length).toBeGreaterThan(0);
  });
});

describe('staff login page', () => {
  it('offers staff login without a registration link', () => {
    const auth: Auth = { user: null, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter><AuthContext.Provider value={auth}><StaffLoginPage /></AuthContext.Provider></MemoryRouter>);
    expect(screen.getByRole('button', { name: /continue securely/i })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /create an account|register/i })).not.toBeInTheDocument();
  });
});

describe('customer sign in form', () => {
  it('explains the account email and blocks invalid addresses before redirecting', () => {
    const auth: Auth = { user: null, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter><AuthContext.Provider value={auth}><LoginPage /></AuthContext.Provider></MemoryRouter>);
    expect(screen.getByText('Use your Zentro account email.')).toBeInTheDocument();
    expect(screen.queryByLabelText(/password/i)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'not-an-email' } });
    fireEvent.submit(screen.getByLabelText('Email address').closest('form')!);
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid email address');
    expect(auth.login).not.toHaveBeenCalled();
  });

  it('sends only the email hint to the OIDC login action', () => {
    const auth: Auth = { user: null, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter><AuthContext.Provider value={auth}><LoginPage /></AuthContext.Provider></MemoryRouter>);
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: ' person@example.com ' } });
    fireEvent.click(screen.getByRole('button', { name: /continue securely/i }));
    expect(auth.login).toHaveBeenCalledWith('/customer', 'person@example.com', 'customer');
  });
});

describe('merchant sign in and registration', () => {
  it('starts email sign in with the merchant dashboard return path and merchant intent', () => {
    const auth: Auth = { user: null, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/merchant/login']}><AuthContext.Provider value={auth}><MerchantLoginPage /></AuthContext.Provider></MemoryRouter>);

    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'business@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /continue securely/i }));
    expect(auth.login).toHaveBeenCalledWith('/merchant/dashboard', 'business@example.com', 'merchant');
  });

  it('keeps the merchant Google sign in on the merchant dashboard flow', () => {
    const auth: Auth = { user: null, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/merchant/login']}><AuthContext.Provider value={auth}><MerchantLoginPage /></AuthContext.Provider></MemoryRouter>);

    expect(screen.queryByRole('link', { name: /customer sign in/i })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /continue with google/i }));
    expect(auth.login).toHaveBeenCalledWith('/merchant/dashboard', undefined, 'merchant', 'google');
  });

  it('returns merchant registration sign in to merchant login', () => {
    const auth: Auth = { user: null, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/register/merchant']}><AuthContext.Provider value={auth}><SignupPage /></AuthContext.Provider></MemoryRouter>);

    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/merchant/login');
    fireEvent.click(screen.getByRole('button', { name: /continue with google/i }));
    expect(auth.login).toHaveBeenCalledWith('/merchant/register-business', undefined, 'merchant', 'google');
  });
});

describe('customer registration page', () => {
  it('opens customer registration directly from /register', () => {
    const auth: Auth = { user: null, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/register']}><AuthContext.Provider value={auth}><SignupPage /></AuthContext.Provider></MemoryRouter>);

    expect(screen.queryByText(/how will you use zentro/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create customer account/i })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /i'm a business/i })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /create customer account/i }));
    expect(auth.register).toHaveBeenCalledWith('/customer', 'customer');
  });

  it('offers to continue with the existing account', () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: '' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/register']}><AuthContext.Provider value={auth}><Routes><Route path="/register" element={<SignupPage />} /><Route path="/customer" element={<p>Customer page</p>} /></Routes></AuthContext.Provider></MemoryRouter>);

    fireEvent.click(screen.getByRole('button', { name: /continue with this account/i }));
    expect(screen.getByText('Customer page')).toBeInTheDocument();
    expect(auth.register).not.toHaveBeenCalled();
  });

  it('can still start Keycloak signup when a session is already active', () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: '' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/register']}><AuthContext.Provider value={auth}><SignupPage /></AuthContext.Provider></MemoryRouter>);

    fireEvent.click(screen.getByRole('button', { name: /create a different account/i }));
    expect(auth.register).toHaveBeenCalledWith('/customer', 'customer');
  });
});
