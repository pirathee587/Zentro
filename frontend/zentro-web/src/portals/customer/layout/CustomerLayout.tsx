import React from 'react';
import { Outlet } from 'react-router-dom';
import { Header } from './Header';
import { ToastProvider } from '../components/Toast';
import { RouteAccessibility } from '@/shared/ui/RouteAccessibility';
import './customer.css';

export const CustomerLayout: React.FC = () => {
  return (
    <ToastProvider>
      <div className="customer-portal">
        <RouteAccessibility portal="Customer dashboard" />
        <Header />
        <main className="sd-main" id="main-content">
          <Outlet />
        </main>
      </div>
    </ToastProvider>
  );
};

export default CustomerLayout;
