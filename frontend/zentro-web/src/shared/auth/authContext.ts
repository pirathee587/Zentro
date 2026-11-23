import { createContext } from 'react';
import type { Role } from '@/shared/config/roles';

export type EntryPoint = 'customer' | 'merchant' | 'staff';
export type IdentityProvider = 'google';
export type MerchantStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type AccountStatus = 'ACTIVE' | 'DISABLED' | 'SUSPENDED';

export type AuthUser = {
  id: string;
  userId?: string;
  email?: string;
  name?: string;
  roles: Role[];
  accessToken: string;
  returnTo?: string;
  entryPoint?: EntryPoint;
  accountStatus?: AccountStatus;
  merchantStatus?: MerchantStatus;
  profileLoaded?: boolean;
};

export type LoginOptions = {
  /**
   * Force Keycloak to ask for credentials even when an SSO session exists (default true).
   * Automatic redirects for protected routes pass false so a valid session is reused;
   * explicit sign-in, sign-out and account switching keep forcing credentials.
   */
  reauthenticate?: boolean;
};

export type LoginCredentialsResult = {
  success: boolean;
  error?: string;
  roles?: Role[];
  targetDashboard?: string;
};

export type Auth = {
  user: AuthUser | null;
  setUser: (user: AuthUser | null) => void;
  login: (returnTo?: string, emailHint?: string, entryPoint?: EntryPoint, identityProvider?: IdentityProvider, options?: LoginOptions) => Promise<void>;
  loginWithCredentials?: (email: string, password: string, returnTo?: string, loginMode?: 'customer' | 'merchant') => Promise<LoginCredentialsResult>;
  register: (returnTo?: string, entryPoint?: EntryPoint) => Promise<void>;
  logout: (returnTo?: string) => Promise<void>;
  refreshAccessToken?: (force?: boolean) => Promise<string | undefined>;
  loading: boolean;
  authError?: string;
};

export const AuthContext = createContext<Auth | undefined>(undefined);

export function isAuthPath(path?: string | null): boolean {
  if (!path || typeof path !== 'string') return true;
  const clean = path.split('?')[0].split('#')[0].replace(/\/+$/, '');
  return (
    clean === '' ||
    clean === '/login' ||
    clean === '/merchant/login' ||
    clean === '/staff/login' ||
    clean === '/register' ||
    clean === '/register/customer' ||
    clean === '/register/merchant' ||
    clean === '/signup' ||
    clean === '/forgot-password' ||
    clean === '/callback' ||
    clean === '/silent-callback'
  );
}
