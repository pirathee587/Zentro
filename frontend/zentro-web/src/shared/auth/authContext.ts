import { createContext } from 'react'; import type { Role } from '@/shared/config/roles';
export type Auth={user:{id:string;roles:Role[]}|null;setUser:(user:Auth['user'])=>void}; export const AuthContext=createContext<Auth|undefined>(undefined);
