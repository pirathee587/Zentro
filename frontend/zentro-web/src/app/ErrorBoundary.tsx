import { Component, type ReactNode } from 'react';
import { Button } from '@/shared/ui';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  errorMessage?: string;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, errorMessage: error?.message };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    console.error('ErrorBoundary caught error:', error, errorInfo);
  }

  componentDidMount(): void {
    window.addEventListener('popstate', this.handleReset);
  }

  componentWillUnmount(): void {
    window.removeEventListener('popstate', this.handleReset);
  }

  handleReset = (): void => {
    if (this.state.hasError) {
      this.setState({ hasError: false, errorMessage: undefined });
    }
  };

  render() {
    if (this.state.hasError) {
      return (
        <main
          className="screen-loading"
          role="alert"
          style={{
            textAlign: 'center',
            padding: '40px 20px',
            boxSizing: 'border-box',
          }}
        >
          <div
            style={{
              maxWidth: '440px',
              width: '100%',
              background: 'var(--card)',
              padding: '32px 24px',
              borderRadius: '20px',
              border: '1px solid var(--line)',
            }}
          >
            <h2
              style={{
                fontSize: '22px',
                fontWeight: 700,
                color: 'var(--ink)',
                margin: '0 0 10px 0',
                letterSpacing: '-0.3px',
              }}
            >
              Something went wrong
            </h2>
            <p
              style={{
                fontSize: '14px',
                color: 'var(--muted)',
                lineHeight: 1.5,
                margin: '0 0 24px 0',
              }}
            >
              {this.state.errorMessage ||
                'An unexpected error occurred while loading this page. You can return home or try again.'}
            </p>
            <div style={{ display: 'flex', gap: '12px', justifyContent: 'center', flexWrap: 'wrap' }}>
              <Button
                type="button"
                onClick={() => {
                  this.setState({ hasError: false, errorMessage: undefined });
                  window.location.href = '/';
                }}
                style={{
                  padding: '10px 24px',
                  borderRadius: '9999px',
                }}
              >
                Return to Home
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  this.setState({ hasError: false, errorMessage: undefined });
                  window.location.reload();
                }}
                style={{
                  padding: '10px 24px',
                  borderRadius: '9999px',
                }}
              >
                Try Again
              </Button>
            </div>
          </div>
        </main>
      );
    }
    return this.props.children;
  }
}
