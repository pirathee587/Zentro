import { useAuth } from '@/shared/auth/useAuth';
import { Button } from './Button';

const roleNames: Record<string, string> = { DISPATCHER: 'dispatcher', COURIER: 'courier', CUSTOMER: 'customer', MERCHANT: 'merchant' };

export function RoleHome({ role }: { role: string }) {
  const name = roleNames[role] ?? role.toLowerCase();
  return (
    <main className="portal-role-home">
      <h1 style={{ margin: 0, fontSize: 'clamp(42px, 7vw, 72px)', fontWeight: 600, letterSpacing: '-.06em', textTransform: 'lowercase' }}>
        {name}
      </h1>
    </main>
  );
}

export function PortalPlaceholder({ title, role }: { title: string; role: string }) {
  const { user, logout } = useAuth();
  return (
    <main className="portal-placeholder">
      <p className="eyebrow">ZENTRO {role}</p>
      <h1>{title}</h1>
      <p>Signed in as <strong>{user?.name || user?.id}</strong></p>
      <p>Access role: <strong>{role}</strong></p>
      <Button onClick={() => void logout()}>Log out</Button>
    </main>
  );
}
