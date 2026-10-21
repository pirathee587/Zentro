import { Navigate, Outlet, useLocation } from 'react-router-dom'; import { useAuth } from './useAuth'; import { paths } from '@/shared/config/paths';
export function ProtectedRoute(){const {user}=useAuth();const location=useLocation();return user?<Outlet/>:<Navigate to={paths.login} state={{from:location}} replace/>;}
