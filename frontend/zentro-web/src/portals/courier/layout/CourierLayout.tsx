import { NotificationBell } from '@/shared/domain/notifications/NotificationBell';
import React, { useState, useRef, useEffect } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { principalCacheScope } from '@/shared/api/queryKeys';
import { useAuth } from '@/shared/auth/useAuth';
import {
  useTheme,
  Button,
  SunIcon,
  MoonIcon,
  UserIcon,
} from '@/shared/ui';
import { RouteAccessibility } from '@/shared/ui/RouteAccessibility';
import './courier.css';

export function CourierLayout() {
  const { logout, user } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const [profileOpen, setProfileOpen] = useState(false);
  const profileRef = useRef<HTMLDivElement>(null);
  const client = useQueryClient();
  const scope = principalCacheScope(user?.id);

  useEffect(() => () => {
    const filters = {
      predicate: (query: { queryKey: readonly unknown[] }) =>
        query.queryKey[0] === 'courier' &&
        query.queryKey.some(
          (part) => typeof part === 'object' && part !== null && 'scope' in part && part.scope === scope,
        ),
    };
    void client.cancelQueries(filters);
    client.removeQueries(filters);
  }, [client, scope]);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (profileRef.current && !profileRef.current.contains(event.target as Node)) {
        setProfileOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const displayName = user?.name?.trim() || 'Courier';
  const displayRole = user?.roles?.join(', ') || 'COURIER';

  return (
    <div className="courier-portal sd-courier-shell">
      <RouteAccessibility portal="Courier assignments" />
      <Link to="/courier/profile" className="sr-only">
        My profile
      </Link>

      {/* Top Header */}
      <header className="sd-header-wrap" role="banner">
        <div className="sd-header">
          {/* Logo pill "Zentro Courier" */}
          <Link
            to="/courier"
            className="sd-logo-pill sd-courier-logo"
            aria-label="Zentro Courier Home"
          >
            <span className="sd-brand-name">Zentro</span>
            <span className="sd-brand-role">Courier</span>
          </Link>

          {/* Center pill nav */}
          <div className="sd-nav-scroll-wrap" role="region" aria-label="Main Navigation Scroll">
            <nav className="sd-nav-pill" aria-label="Courier navigation">
              <NavLink
                to="/courier"
                end
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Today
              </NavLink>
              <NavLink
                to="/courier/route"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Route
              </NavLink>
              <NavLink
                to="/courier/history"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                History
              </NavLink>
              <NavLink
                to="/courier/earnings"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Earnings
              </NavLink>
              <NavLink
                to="/courier/profile"
                className={({ isActive }) => `sd-nav-link ${isActive ? 'active is-active' : ''}`}
              >
                Profile
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
            <NotificationBell portal="COURIER" />

            {/* User profile dropdown button */}
            <div className="sd-profile-menu-wrap" ref={profileRef}>
              <Button
                type="button"
                onClick={() => setProfileOpen((prev) => !prev)}
                className="sd-icon-btn"
                aria-label="Courier account menu"
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
                    to="/courier/profile"
                    className="sd-dropdown-item"
                    role="menuitem"
                    onClick={() => setProfileOpen(false)}
                  >
                    My Profile
                  </Link>
                  <Link
                    to="/courier/earnings"
                    className="sd-dropdown-item"
                    role="menuitem"
                    onClick={() => setProfileOpen(false)}
                  >
                    Earnings
                  </Link>
                  <hr className="sd-dropdown-divider" />
                  <Button
                    type="button"
                    className="sd-dropdown-logout-btn"
                    role="menuitem"
                    onClick={() => {
                      setProfileOpen(false);
                      void logout('/staff/login?switch_account=1');
                    }}
                  >
                    Switch account
                  </Button>
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
      <div className="sd-courier-content" key={scope}>
        <Outlet />
      </div>
    </div>
  );
}
