import React from 'react';
import { Link } from 'react-router-dom';
import { Button as BaseButton } from '@/shared/ui';
import { useTheme } from '../layout/useTheme';
import { TrackView } from '../components/TrackView';
import { ToastProvider } from '../components/Toast';
import { PackageIcon, SunIcon, MoonIcon, UserIcon } from '../components/icons';
import '../layout/customer.css';

export const PublicTrackPage: React.FC = () => {
  const { theme, toggleTheme } = useTheme();

  return (
    <ToastProvider>
      <div className="customer-portal sd-public-track-page">
        {/* Mobile-friendly Public Header */}
        <header className="sd-header-wrap" role="banner">
          <div className="sd-header">
            <Link to="/" className="sd-logo-pill" aria-label="SwiftDrop Home">
              <span className="sd-logo-mark" aria-hidden="true">
                <PackageIcon size={14} />
              </span>
              <span>SwiftDrop</span>
            </Link>

            <div className="sd-header-actions">
              <BaseButton
                type="button"
                onClick={toggleTheme}
                className="sd-icon-btn"
                aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
                title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
              >
                {theme === 'dark' ? <SunIcon size={22} strokeWidth={2} /> : <MoonIcon size={22} strokeWidth={2} />}
              </BaseButton>

              <Link
                to="/login"
                className="sd-btn sd-btn-outline sd-btn-small"
                style={{ borderRadius: '9999px', display: 'inline-flex', alignItems: 'center', gap: '6px' }}
              >
                <UserIcon size={16} />
                <span>Customer sign in</span>
              </Link>
            </div>
          </div>
        </header>

        {/* Main tracking area */}
        <main className="sd-main" id="public-tracking-content">
          <div className="sd-page-header">
            <h1 className="sd-page-title">
              Track a parcel <span className="sr-only">Track Parcel</span>
            </h1>
            <p className="sd-page-subtitle">
              No login needed. Anyone with the tracking number can follow it, on any device.
            </p>
          </div>

          <TrackView isPublic={true} />
        </main>
      </div>
    </ToastProvider>
  );
};

export default PublicTrackPage;
