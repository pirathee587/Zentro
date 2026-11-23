import { NotificationBell } from '@/shared/domain/notifications/NotificationBell';
import React, { useState, useRef, useEffect } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
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
import './dispatcher.css';

export function DispatcherLayout() {
  const { logout, user } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const [profileOpen, setProfileOpen] = useState(false);
  const profileRef = useRef<HTMLDivElement>(null);
  const location = useLocation();

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (profileRef.current && !profileRef.current.contains(event.target as Node)) {
        setProfileOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const displayName = user?.name || 'Dispatcher';
  const displayRole = user?.roles?.join(', ') || 'DISPATCHER';

  return (
    <div className="dispatcher-portal sd-dispatcher-shell">
      <RouteAccessibility portal="Dispatcher overview" />

      {/* Top Header */}
      <header className="sd-header-wrap" role="banner">
        <div className="sd-header">
          {/* Logo pill "SwiftDrop Dispatcher" */}
          <LogoPill to="/dispatcher" role="Dispatcher" ariaLabel="SwiftDrop Dispatcher Home" />

          {/* Center pill nav */}
          <div className="sd-nav-scroll-wrap" role="region" aria-label="Main Navigation Scroll">
            <nav className="sd-nav-pill" aria-label="Dispatcher navigation">
              <NavLink
                to="/dispatcher"
                end
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Dashboard
              </NavLink>
              <NavLink
                to="/dispatcher/parcels"
                className={({ isActive }) =>
                  `sd-nav-link ${isActive || location.pathname === '/dispatcher/queue' ? 'active is-active' : ''}`
                }
              >
                Parcels
              </NavLink>
              <NavLink
                to="/dispatcher/couriers"
                className={({ isActive }) =>
                  `sd-nav-link ${isActive || location.pathname.startsWith('/dispatcher/users') ? 'active is-active' : ''}`
                }
              >
                User Management
              </NavLink>
              <NavLink
                to="/dispatcher/zones-pricing"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Zones &amp; pricing
              </NavLink>
              <NavLink
                to="/dispatcher/reports"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Reports
              </NavLink>
              <NavLink to="/dispatcher/proof-reviews" className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}>Proof reviews</NavLink>
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
            <NotificationBell portal="DISPATCHER" />

            {/* User profile dropdown button */}
            <div className="sd-profile-menu-wrap" ref={profileRef}>
              <Button
                type="button"
                onClick={() => setProfileOpen((prev) => !prev)}
                className="sd-icon-btn"
                aria-label="Dispatcher account menu"
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
                    to="/dispatcher/users"
                    className="sd-dropdown-item"
                    role="menuitem"
                    onClick={() => setProfileOpen(false)}
                  >
                    User Management
                  </Link>
                  <Link
                    to="/dispatcher/merchant-approvals"
                    className="sd-dropdown-item"
                    role="menuitem"
                    onClick={() => setProfileOpen(false)}
                  >
                    Merchant Applications
                  </Link>
                  <Link
                    to="/dispatcher/integration-attempts"
                    className="sd-dropdown-item"
                    role="menuitem"
                    onClick={() => setProfileOpen(false)}
                  >
                    Notification Logs
                  </Link>
                  {user?.roles?.includes('COURIER') && (
                    <Link
                      to="/courier"
                      className="sd-dropdown-item"
                      role="menuitem"
                      onClick={() => setProfileOpen(false)}
                    >
                      Courier Portal
                    </Link>
                  )}
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
      <div className="sd-dispatcher-content">
        <Outlet />
      </div>
    </div>
  );
}
