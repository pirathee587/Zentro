import React from 'react';
import { DeliveryTruckArt } from './DeliveryTruckArt';

export function AuthIllustration() {
  return (
    <div className="auth-illustration-wrap" role="img" aria-label="Zentro Express Delivery Truck in Transit">
      <DeliveryTruckArt variant="auth" />
    </div>
  );
}
