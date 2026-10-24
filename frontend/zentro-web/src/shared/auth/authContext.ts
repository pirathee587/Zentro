import { createContext } from 'react';
import type { Role } from '@/shared/config/roles';

export type EntryPoint = 'customer' | 'merchant' | 'staff';
export type IdentityProvider = 'google';
export type MerchantStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type AccountStatus = 'ACTIVE' | 'DISABLED' | 'SUSPENDED';

export type AuthUser = {
  id: string;
  name?: string;
  roles: Role[];
  accessToken: string;
  returnTo?: string;
  entryPoint?: EntryPoint;
  accountStatus?: AccountStatus;
  merchantStatus?: MerchantStatus;
  profileLoaded?: boolean;
};

export type Auth = {
  user: AuthUser | null;
  setUser: (user: AuthUser | null) => void;
  login: (returnTo?: string, emailHint?: string, entryPoint?: EntryPoint, identityProvider?: IdentityProvider) => Promise<void>;
  register: (returnTo?: string, entryPoint?: EntryPoint) => Promise<void>;
  logout: (returnTo?: string) => Promise<void>;
  refreshAccessToken?: (force?: boolean) => Promise<string | undefined>;
  loading: boolean;
  authError?: string;
};

export const AuthContext = createContext<Auth | undefined>(undefined);
