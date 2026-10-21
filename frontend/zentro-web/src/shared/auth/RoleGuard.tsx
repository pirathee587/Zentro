import { Navigate, Outlet } from 'react-router-dom'; import { useAuth } from './useAuth'; import type { Role } from '@/shared/config/roles';
export function RoleGuard({roles}:{roles:Role[]}){const {user}=useAuth();return user?.roles.some((role)=>roles.includes(role))?<Outlet/>:<Navigate to="/forbidden" replace/>;}
