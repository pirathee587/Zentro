import { Navigate, Outlet } from 'react-router-dom'; import { useAuth } from './useAuth'; import type { Role } from '@/shared/config/roles';
export function RoleGuard({roles}:{roles:Role[]}){
  const {user}=useAuth();
  const userIsStaff=Boolean(user?.roles.some((role)=>role==='DISPATCHER'||role==='COURIER'));
  const staffOnlyPortal=roles.includes('DISPATCHER')||roles.includes('COURIER');
  const hasRequestedRole=Boolean(user?.roles.some((role)=>roles.includes(role)));
  const allowed=hasRequestedRole&&(!userIsStaff||staffOnlyPortal);
  return allowed?<Outlet/>:<Navigate to={user ? '/forbidden' : '/login'} replace/>;
}
