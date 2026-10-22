import { useEffect } from 'react'; import { Outlet } from 'react-router-dom'; import { useAuth } from './useAuth';
export function ProtectedRoute(){const {user,login}=useAuth();useEffect(()=>{if(!user)void login(window.location.pathname+window.location.search);},[login,user]);return user?<Outlet/>:<main className="screen-loading">Redirecting to sign in…</main>;}
