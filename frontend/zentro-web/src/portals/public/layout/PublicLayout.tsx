import { Link, Outlet, useLocation } from 'react-router-dom';
import { AuthIllustration } from '../assets/AuthIllustration';

export function PublicLayout() {
  const { pathname } = useLocation();
  if (pathname === '/' || pathname.startsWith('/track')) return <Outlet />;
  return <main className="auth-page"><section className="auth-art" aria-label="Thoughtful delivery in Sri Lanka"><Link to="/" className="brand auth-brand"><span className="brand-mark">z</span>zentro</Link><AuthIllustration/><p>Thoughtful delivery,<br/><em>made for Sri Lanka.</em></p><span className="auth-art-note">A little closer, every time.</span></section><section className="auth-panel"><Link to="/" className="brand auth-brand-mobile"><span className="brand-mark">z</span>zentro</Link><Outlet /></section></main>;
}
