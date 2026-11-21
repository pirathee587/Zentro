import React, { useState, useEffect, useRef } from 'react';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button, ErrorState, Spinner } from '@/shared/ui';
import {
  useMerchantApiKeyQuery,
  useIssueMerchantApiKeyMutation,
  useRevokeMerchantApiKeyMutation,
} from '../hooks/useMerchantQueries';
import { Info, ChevronDown, ChevronRight } from 'lucide-react';

export function ApiKeysPage() {
  const key = useMerchantApiKeyQuery();
  const issue = useIssueMerchantApiKeyMutation();
  const revoke = useRevokeMerchantApiKeyMutation();

  const [secret, setSecret] = useState('');
  const [copied, setCopied] = useState(false);
  const [localError, setLocalError] = useState('');
  const secretHeading = useRef<HTMLElement>(null);

  useEffect(() => {
    if (secret) secretHeading.current?.focus();
  }, [secret]);

  // Accordion state
  const [openSection, setOpenSection] = useState<'store' | 'leaks' | 'roles' | null>('store');

  const activeKey = key.data?.active === true;
  const prefix = key.data?.prefix ? `${key.data.prefix}...` : '—';
  const keyStatus = key.isLoading
    ? 'Loading'
    : key.isError
      ? 'Unavailable'
      : !key.data
        ? 'Not configured'
        : activeKey
          ? 'Active'
          : 'Inactive';
  const createdDate = key.data?.createdAt
    ? new Date(key.data.createdAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
    : '—';

  const actionError = issue.error || revoke.error;

  return (
    <main className="sd-page-container" aria-label="API Keys Management">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">API keys</h1>
          <p className="sd-page-subtitle">
            One active key per merchant. The full key is shown only once, when it is created.
          </p>
        </div>
      </div>

      {/* Query error/loading if applicable */}
      {key.isLoading && (
        <div style={{ padding: 24, textAlign: 'center' }}>
          <Spinner label="Loading API key status" />
        </div>
      )}
      {key.isError && (
        <div>
          <ErrorState message={friendlyApiError(key.error, 'We could not load your API key.')} />
          <Button type="button" variant="secondary" onClick={() => void key.refetch()}>Retry API key</Button>
        </div>
      )}

      {/* 2-Column Grid */}
      <div className="sd-grid-2col" style={{ gap: 24, alignItems: 'start' }}>
        {/* Left Column: Your API key Card */}
        <section className="sd-card" aria-labelledby="your-key-heading">
          <div className="sd-card-header">
            <h2 id="your-key-heading" className="sd-card-title">
              Your API key
            </h2>
            <span className="sd-badge-dark">{keyStatus}</span>
          </div>

          <div className="sd-health-list" style={{ marginTop: 8 }}>
            <div className="sd-health-row">
              <span className="sd-health-label">Key prefix</span>
              <span className="sd-cell-ref" style={{ color: 'var(--ink)' }}>{prefix}</span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Created</span>
              <span className="sd-health-val">{createdDate}</span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Last used</span>
              <span className="sd-health-val">Not available</span>
            </div>
          </div>

          {/* Secret Display Box if freshly issued */}
          {secret && (
            <div className="sd-secret-panel-box">
              <strong ref={secretHeading} tabIndex={-1}>Copy and store this key now</strong>
              <p className="sd-cell-mute" style={{ fontSize: 12, margin: '4px 0 8px' }}>
                For security, Zentro will not show the full key again.
              </p>
              <code className="sd-secret-code-pill">
                {secret}
              </code>
              <div>
                <Button
                  type="button"
                  variant="secondary"
                  className="sd-btn-pill-light"
                  onClick={() => {
                    void navigator.clipboard
                      .writeText(secret)
                      .then(() => setCopied(true))
                      .catch(() => setLocalError('Clipboard access failed.'));
                  }}
                >
                  {copied ? 'Copied' : 'Copy key'}
                </Button>
                <span role="status">{copied ? 'Key copied.' : ''}</span>
              </div>
            </div>
          )}

          {localError && <ErrorState message={localError} />}
          {actionError && <ErrorState message={friendlyApiError(actionError, 'We could not update the API key.')} />}

          {/* Buttons */}
          <div style={{ display: 'flex', gap: 12, marginTop: 28, flexWrap: 'wrap' }}>
            <Button
              type="button"
              className="sd-btn-yellow"
              aria-label="Generate API key"
              disabled={issue.isPending || revoke.isPending}
              onClick={() => {
                setSecret('');
                issue.mutate(undefined, {
                  onSuccess: (result) => {
                    setSecret(result.secret);
                    setCopied(false);
                    setLocalError('');
                  },
                });
              }}
            >
              {issue.isPending ? 'Generating…' : 'Generate new key'}
            </Button>

            <Button
              type="button"
              className="sd-btn-pill-light"
              disabled={issue.isPending || revoke.isPending || key.isLoading || key.isError || !key.data?.active}
              onClick={() => {
                if (window.confirm('Revoke this key? The current key will stop working immediately.')) {
                  setSecret('');
                  revoke.mutate(undefined, {
                    onSuccess: () => {
                      setSecret('');
                      setLocalError('');
                    },
                  });
                }
              }}
            >
              {revoke.isPending ? 'Revoking…' : 'Revoke key'}
            </Button>
          </div>

          {/* Footnote with info icon */}
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, marginTop: 24, fontSize: 13, color: 'var(--mute)', lineHeight: 1.5 }}>
            <span style={{ display: 'inline-flex', marginTop: 2 }}>
              <Info size={16} />
            </span>
            <span>
              Generating a new key immediately stops the old key from working. Update your integration right after.
            </span>
          </div>
        </section>

        {/* Right Column: Keep your key safe Card */}
        <section className="sd-card" aria-labelledby="key-safe-heading">
          <div className="sd-card-header">
            <h2 id="key-safe-heading" className="sd-card-title">
              Keep your key safe
            </h2>
          </div>

          <div className="sd-accordion">
            {/* Item 1: Where to store it */}
            <div className="sd-accordion-item">
              <Button
                type="button"
                className="sd-accordion-header"
                onClick={() => setOpenSection(openSection === 'store' ? null : 'store')}
                aria-expanded={openSection === 'store'}
              >
                {openSection === 'store' ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                <span>Where to store it</span>
              </Button>
              {openSection === 'store' && (
                <div className="sd-accordion-body">
                  Keep it in your server&apos;s secret store or environment variables. Never put it in a web page, mobile app or public repository.
                </div>
              )}
            </div>

            {/* Item 2: If it leaks */}
            <div className="sd-accordion-item">
              <Button
                type="button"
                className="sd-accordion-header"
                onClick={() => setOpenSection(openSection === 'leaks' ? null : 'leaks')}
                aria-expanded={openSection === 'leaks'}
              >
                {openSection === 'leaks' ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                <span>If it leaks</span>
              </Button>
              {openSection === 'leaks' && (
                <div className="sd-accordion-body">
                  Revoke the key immediately from this page. Any requests using the compromised key will be rejected instantly.
                </div>
              )}
            </div>

            {/* Item 3: Who can create keys */}
            <div className="sd-accordion-item" style={{ borderBottom: 'none' }}>
              <Button
                type="button"
                className="sd-accordion-header"
                onClick={() => setOpenSection(openSection === 'roles' ? null : 'roles')}
                aria-expanded={openSection === 'roles'}
              >
                {openSection === 'roles' ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                <span>Who can create keys</span>
              </Button>
              {openSection === 'roles' && (
                <div className="sd-accordion-body">
                  Only approved merchant accounts with administrative access can generate or revoke API credentials.
                </div>
              )}
            </div>
          </div>
        </section>
      </div>
    </main>
  );
}
