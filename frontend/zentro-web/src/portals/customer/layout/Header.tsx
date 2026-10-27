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
            {theme === 'dark' ? <SunIcon size={22} strokeWidth={2} /> : <MoonIcon size={22} strokeWidth={2} />}
          </BaseButton>

          {/* Notifications button */}
          <BaseButton
            type="button"
            className="sd-icon-btn"
            aria-label="View notifications"
            title="Notifications"
          >
            <BellIcon size={22} strokeWidth={2} />
            <span className="sd-notification-badge" aria-hidden="true" />
          </BaseButton>

          {/* Profile button (circular, --card background) */}
          <div className="sd-profile-menu-wrap" ref={profileRef}>
            <BaseButton
              type="button"
              onClick={() => setProfileOpen((prev) => !prev)}
              className="sd-icon-btn sd-profile-btn"
              aria-label="User profile options"
              aria-expanded={profileOpen}
              aria-haspopup="menu"
            >
              <UserIcon size={22} strokeWidth={2} />
            </BaseButton>

            {profileOpen && (
              <div className="sd-profile-menu" role="menu">
                <div className="sd-profile-info">
                  <div className="sd-profile-name">{displayName}</div>
                  <div className="sd-profile-role">{displayRole}</div>
                </div>

                <BaseButton
                  type="button"
                  className="sd-btn sd-btn-outline sd-btn-small"
                  onClick={() => {
                    setProfileOpen(false);
                    void logout();
                  }}
                  role="menuitem"
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
