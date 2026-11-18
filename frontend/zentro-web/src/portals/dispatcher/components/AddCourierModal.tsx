import { useState, useRef, type FormEvent } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { useDialogFocus } from '@/shared/ui/useDialogFocus';
import { friendlyApiError } from '@/shared/api/apiClient';
import { createCourier } from '../api/adminApi';

export interface AddCourierModalProps {
  onClose: () => void;
  onCreated: (email: string) => void;
}

export function AddCourierModal({ onClose, onCreated }: AddCourierModalProps) {
  const dialog = useRef<HTMLDivElement>(null);
  useDialogFocus(dialog, onClose);

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [assignedZone, setAssignedZone] = useState('Colombo Central');
  const [vehicleType, setVehicleType] = useState('MOTORCYCLE');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    if (!name.trim()) {
      setError('Please enter courier name.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setError('Please enter a valid Gmail / email address.');
      return;
    }

    setLoading(true);
    try {
      await createCourier({
        name: name.trim(),
        email: email.trim().toLowerCase(),
        phone: phone.trim() || undefined,
        assignedZone,
        vehicleType,
      });
      onCreated(email.trim());
    } catch (err) {
      setError(friendlyApiError(err, 'Failed to create courier. Please check details and try again.'));
    } finally {
      setLoading(false);
    }
  };

  return createPortal(
    <div
      className="user-edit-backdrop"
      data-testid="add-courier-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget && !loading) onClose();
      }}
    >
      <div
        role="dialog"
        ref={dialog}
        tabIndex={-1}
        aria-modal="true"
        aria-label="Add New Courier"
        className="staff-edit-panel user-edit-dialog"
        style={{ maxWidth: '520px' }}
      >
        <div className="staff-edit-panel-header">
          <div>
            <span className="staff-eyebrow">COURIER ONBOARDING</span>
            <h2>Add New Courier</h2>
            <small>Create courier account with real Gmail. A temporary password will be sent via email.</small>
          </div>
          <button type="button" aria-label="Close" className="staff-close-btn" disabled={loading} onClick={onClose}>
            <X size={20} aria-hidden="true" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="staff-edit-panel-body">
          <div className="staff-field">
            <label htmlFor="courier-name">Full Name *</label>
            <input
              id="courier-name"
              type="text"
              required
              value={name}
              placeholder="e.g. Priyantha Silva"
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="staff-field">
            <label htmlFor="courier-email">Courier Real Gmail *</label>
            <input
              id="courier-email"
              type="email"
              required
              value={email}
              placeholder="courier@gmail.com"
              onChange={(e) => setEmail(e.target.value)}
            />
            <small style={{ color: 'var(--muted, #686868)', marginTop: '4px', display: 'block', fontSize: '12px' }}>
              A temporary password will be emailed to this address. On first sign-in, Keycloak will mandate choosing their own password.
            </small>
          </div>

          <div className="staff-field">
            <label htmlFor="courier-phone">Phone Number</label>
            <input
              id="courier-phone"
              type="tel"
              value={phone}
              placeholder="07XXXXXXXX or +947XXXXXXXX"
              onChange={(e) => setPhone(e.target.value)}
            />
          </div>

          <div className="staff-field">
            <label htmlFor="courier-zone">Assigned Delivery Zone</label>
            <select id="courier-zone" value={assignedZone} onChange={(e) => setAssignedZone(e.target.value)}>
              <option value="Colombo Central">Colombo Central</option>
              <option value="Colombo North">Colombo North</option>
              <option value="Colombo South">Colombo South</option>
              <option value="Dehiwala-Mount Lavinia">Dehiwala-Mount Lavinia</option>
              <option value="Moratuwa">Moratuwa</option>
              <option value="Kaduwela">Kaduwela</option>
              <option value="Gampaha">Gampaha</option>
              <option value="Negombo">Negombo</option>
              <option value="Kandy">Kandy</option>
              <option value="Galle">Galle</option>
              <option value="Jaffna">Jaffna</option>
              <option value="Kurunegala">Kurunegala</option>
            </select>
          </div>

          <div className="staff-field">
            <label htmlFor="courier-vehicle">Vehicle Type</label>
            <select id="courier-vehicle" value={vehicleType} onChange={(e) => setVehicleType(e.target.value)}>
              <option value="MOTORCYCLE">Motorcycle</option>
              <option value="VAN">Van</option>
              <option value="THREE_WHEELER">Three-Wheeler</option>
              <option value="CAR">Car</option>
              <option value="BICYCLE">Bicycle</option>
            </select>
          </div>

          {error && <div className="staff-error" role="alert" style={{ marginBottom: '12px' }}>{error}</div>}

          <div className="staff-edit-panel-footer" style={{ marginTop: '16px' }}>
            <button type="button" className="staff-review-button" disabled={loading} onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="staff-review-button is-approve" disabled={loading} style={{ flex: 1 }}>
              {loading ? 'Creating & Sending Email...' : 'Create Courier & Send Password'}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  );
}
