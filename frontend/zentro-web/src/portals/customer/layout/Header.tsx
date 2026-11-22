import React, { useState, useRef, useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Button as BaseButton } from '@/shared/ui';
import { useAuth } from '@/shared/auth/useAuth';
import { useTheme } from './useTheme';
import {
  SunIcon,
  MoonIcon,
  BellIcon,
  UserIcon,
} from '../components/icons';

interface NavItem {
  to: string;
  label: string;
  exact?: boolean;
}

const NAV_ITEMS: NavItem[] = [
  { to: '/customer', label: 'Dashboard', exact: true },
  { to: '/customer/send', label: 'Send parcel' },
  { to: '/customer/track', label: 'Track' },
  { to: '/customer/history', label: 'History' },
  { to: '/customer/receipts', label: 'Receipts' },
  { to: '/customer/support', label: 'Support' },
];

export const Header: React.FC = () => {
  const { user, logout } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const location = useLocation();
  const [profileOpen, setProfileOpen] = useState(false);
  const profileRef = useRef<HTMLDivElement>(null);

  // Close profile dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (profileRef.current && !profileRef.current.contains(event.target as Node)) {
        setProfileOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const displayName = user?.name || 'Customer';
  const displayRole = user?.roles?.join(', ') || 'CUSTOMER';

  return (
    <header className="sd-header-wrap" role="banner">
      <div className="sd-header">
        {/* Logo pill "SwiftDrop" */}
        <Link to="/customer" className="sd-logo-pill" aria-label="SwiftDrop Customer Home">
          <svg
            className="sd-logo-box-svg"
            width="28"
            height="24"
            viewBox="0 0 36 28"
            fill="none"
            aria-hidden="true"
          >
            {/* Speed lines */}
            <rect x="1" y="9" width="7" height="2" rx="1" fill="var(--gold)" />
            <rect x="0" y="13.5" width="9" height="2" rx="1" fill="var(--gold)" />
            <rect x="2" y="18" width="6.5" height="2" rx="1" fill="var(--gold)" />
            {/* Isometric box top */}
            <path d="M21 3L32 8.5L21 14L10 8.5L21 3Z" fill="var(--gold-light)" />
            {/* Isometric box left face */}
            <path d="M10 8.5L21 14V25.5L10 20V8.5Z" fill="var(--ochre)" />
            {/* Isometric box right face */}
            <path d="M32 8.5L21 14V25.5L32 20V8.5Z" fill="var(--espresso)" />
            {/* Tape detail */}
            <path d="M25.5 5.75L14.5 11.25L17.5 12.75L28.5 7.25L25.5 5.75Z" fill="var(--warm-pale)" opacity="0.7" />
          </svg>
          <span>SwiftDrop</span>
        </Link>

        {/* Center pill nav (scrolls horizontally on small screens) */}
        <div className="sd-nav-scroll-wrap" role="region" aria-label="Main Navigation Scroll">
          <nav className="sd-nav-pill" aria-label="Customer Navigation">
            {NAV_ITEMS.map((item) => {
              const isActive = item.exact
                ? location.pathname === item.to
                : location.pathname.startsWith(item.to);

              return (
                <Link
                  key={item.to}
                  to={item.to}
                  className={`sd-nav-link ${isActive ? 'is-active' : ''}`}
                  aria-current={isActive ? 'page' : undefined}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>
        </div>

        {/* Right side actions */}
        <div className="sd-header-actions">
          {/* Theme toggle */}
          <BaseButton
            type="button"
            onClick={toggleTheme}
            className="sd-icon-btn"
            aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
            title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
          >
            {theme === 'dark' ? <SunIcon size={19} strokeWidth={1.75} /> : <MoonIcon size={19} strokeWidth={1.75} />}
          </BaseButton>

          {/* Notifications button */}
          <BaseButton
            type="button"
            className="sd-icon-btn"
            aria-label="View notifications"
            title="Notifications"
          >
            <BellIcon size={19} strokeWidth={1.75} />
          </BaseButton>

          {/* Profile button (circular, --card background) */}
          <div className="sd-profile-menu-wrap" ref={profileRef} onKeyDown={event => {
            if (event.key === 'Escape' && profileOpen) {
              event.preventDefault(); setProfileOpen(false); profileRef.current?.querySelector('button')?.focus();
            }
          }} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setProfileOpen(false); }}>
            <BaseButton
              type="button"
              onClick={() => setProfileOpen((prev) => !prev)}
              className="sd-icon-btn sd-profile-btn"
              aria-label="User profile options"
              aria-expanded={profileOpen}
              aria-controls="customer-profile-panel"
            >
              <UserIcon size={19} strokeWidth={1.75} />
            </BaseButton>

            {profileOpen && (
              <div className="sd-profile-menu" id="customer-profile-panel">
                <div className="sd-profile-info">
                  <div className="sd-profile-name">{displayName}</div>
                  <div className="sd-profile-role">{displayRole}</div>
                </div>

                <BaseButton
                  type="button"
                  className="sd-btn sd-btn-outline sd-btn-small"
                  onClick={() => {
                    setProfileOpen(false);
                    void logout('/login?switch_account=1');
                  }}
                >
                  Switch account
                </BaseButton>

                <BaseButton
                  type="button"
                  className="sd-btn sd-btn-outline sd-btn-small"
                  onClick={() => {
                    setProfileOpen(false);
                    void logout();
                  }}
                >
                  Sign out
                </BaseButton>
              </div>
            )}
          </div>
        </div>
      </div>
    </header>
  );
};
