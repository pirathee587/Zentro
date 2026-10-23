import { createContext } from 'react'; import type { Role } from '@/shared/config/roles';
export type EntryPoint = 'customer' | 'staff' | 'merchant';
export type Auth = {
  user: { id: string; name?: string; roles: Role[]; accessToken: string; returnTo?: string; entryPoint?: EntryPoint } | null;
  setUser: (user: Auth['user']) => void;
  login: (returnTo?: string, hint?: string, entryPoint?: EntryPoint) => Promise<void>;
  register: (returnTo?: string, entryPoint?: EntryPoint) => Promise<void>;
  logout: (returnTo?: string) => Promise<void>;
  loading: boolean;
  authError?: string;
};
export const AuthContext = createContext<Auth | undefined>(undefined);
