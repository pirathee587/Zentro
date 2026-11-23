import { NotificationBell } from '@/shared/domain/notifications/NotificationBell';
import React, { useState, useRef, useEffect } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import {
  useTheme,
  Button,
  SunIcon,
  MoonIcon,
  UserIcon,
  LogoPill,
} from '@/shared/ui';
import { RouteAccessibility } from '@/shared/ui/RouteAccessibility';
import './merchant.css';

export function MerchantLayout() {
  const { logout, user } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const [profileOpen, setProfileOpen] = useState(false);
  const profileRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (profileRef.current && !profileRef.current.contains(event.target as Node)) {
        setProfileOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const displayName = user?.name || 'Merchant';
  const displayRole = user?.roles?.join(', ') || 'MERCHANT';

  return (
    <div className="merchant-portal sd-merchant-shell">
      <RouteAccessibility portal="Merchant dashboard" />

      {/* Top Header */}
      <header className="sd-header-wrap" role="banner">
        <div className="sd-header">
          {/* Logo pill "SwiftDrop Merchant" */}
          <LogoPill to="/merchant/dashboard" role="Merchant" ariaLabel="SwiftDrop Merchant Home" />

          {/* Center pill nav */}
          <div className="sd-nav-scroll-wrap" role="region" aria-label="Main Navigation Scroll">
            <nav className="sd-nav-pill" aria-label="Merchant navigation">
              <NavLink
                to="/merchant/dashboard"
                end
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Dashboard
              </NavLink>
              <NavLink
                to="/merchant/dashboard/orders"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Orders
              </NavLink>
              <NavLink
                to="/merchant/dashboard/api-keys"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                API keys
              </NavLink>
              <NavLink
                to="/merchant/dashboard/webhooks"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Webhooks
              </NavLink>
              <NavLink
                to="/merchant/dashboard/billing"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Billing
              </NavLink>
            </nav>
          </div>

          {/* Right side actions */}
          <div className="sd-header-actions">
            {/* Theme toggle */}
            <Button
              type="button"
              onClick={toggleTheme}
              className="sd-icon-btn"
              aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
              title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
            >
              {theme === 'dark' ? (
                <SunIcon size={20} strokeWidth={2} />
              ) : (
                <MoonIcon size={20} strokeWidth={2} />
              )}
            </Button>

            {/* Notifications button */}
            <NotificationBell portal="MERCHANT" />

            {/* User profile dropdown button */}
            <div className="sd-profile-menu-wrap" ref={profileRef}>
              <Button
                type="button"
                onClick={() => setProfileOpen((prev) => !prev)}
                className="sd-icon-btn"
                aria-label="Merchant account menu"
                aria-expanded={profileOpen}
                title="Account"
              >
                <UserIcon size={20} strokeWidth={2} />
              </Button>

              {profileOpen && (
                <div className="sd-profile-dropdown" role="menu">
                  <div className="sd-profile-user-info">
                    <strong>{displayName}</strong>
                    <small>{displayRole}</small>
                  </div>
                  <hr className="sd-dropdown-divider" />
                  <Link
                    to="/merchant/dashboard/orders"
                    className="sd-dropdown-item"
                    role="menuitem"
                    onClick={() => setProfileOpen(false)}
                  >
                    Orders
                  </Link>
                  <Link
                    to="/merchant/dashboard/billing"
                    className="sd-dropdown-item"
                    role="menuitem"
                    onClick={() => setProfileOpen(false)}
                  >
                    Billing
                  </Link>
                  {user?.roles?.includes('CUSTOMER') && (
                    <Link
                      to="/customer"
                      className="sd-dropdown-item"
                      role="menuitem"
                      onClick={() => setProfileOpen(false)}
                    >
                      Customer Portal
                    </Link>
                  )}
                  <hr className="sd-dropdown-divider" />
                  <Button
                    type="button"
                    className="sd-dropdown-logout-btn"
                    role="menuitem"
                    onClick={() => {
                      setProfileOpen(false);
                      void logout();
                    }}
                  >
                    Log out
                  </Button>
                </div>
              )}
            </div>
          </div>
        </div>
      </header>

      {/* Main Content Area */}
      <Outlet />
    </div>
  );
}
