import React from 'react';
import { Outlet } from 'react-router-dom';
import { Header } from './Header';
import { ToastProvider } from '../components/Toast';
import './customer.css';

export const CustomerLayout: React.FC = () => {
  return (
    <ToastProvider>
      <div className="customer-portal">
        <Header />
        <main className="sd-main" id="main-content">
          <Outlet />
        </main>
      </div>
    </ToastProvider>
  );
};

export default CustomerLayout;
