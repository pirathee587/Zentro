import React from 'react';
import {
  Bell,
  User,
  Sun,
  Moon,
  Package,
  Truck,
  CheckCircle2,
  Phone,
  Download,
  Search,
  MapPin,
  Copy,
  Printer,
  Send,
  MessageCircle,
  AlertCircle,
  ArrowRight,
  Clock,
  HelpCircle,
  Mail,
  ChevronDown,
  type LucideProps,
} from 'lucide-react';

export interface IconProps extends Omit<LucideProps, 'size' | 'strokeWidth'> {
  size?: number | string;
  strokeWidth?: number | string;
  className?: string;
}

const defaultProps = {
  size: 20,
  strokeWidth: 1.75,
  color: 'currentColor',
};

export const BellIcon: React.FC<IconProps> = (props) => <Bell {...defaultProps} {...props} />;
export const UserIcon: React.FC<IconProps> = (props) => <User {...defaultProps} {...props} />;
export const SunIcon: React.FC<IconProps> = (props) => <Sun {...defaultProps} {...props} />;
export const MoonIcon: React.FC<IconProps> = (props) => <Moon {...defaultProps} {...props} />;
export const PackageIcon: React.FC<IconProps> = (props) => <Package {...defaultProps} {...props} />;
export const TruckIcon: React.FC<IconProps> = (props) => <Truck {...defaultProps} {...props} />;
export const CircleCheckIcon: React.FC<IconProps> = (props) => <CheckCircle2 {...defaultProps} {...props} />;
export const PhoneIcon: React.FC<IconProps> = (props) => <Phone {...defaultProps} {...props} />;
export const DownloadIcon: React.FC<IconProps> = (props) => <Download {...defaultProps} {...props} />;
export const SearchIcon: React.FC<IconProps> = (props) => <Search {...defaultProps} {...props} />;
export const MapPinIcon: React.FC<IconProps> = (props) => <MapPin {...defaultProps} {...props} />;
export const CopyIcon: React.FC<IconProps> = (props) => <Copy {...defaultProps} {...props} />;
export const PrinterIcon: React.FC<IconProps> = (props) => <Printer {...defaultProps} {...props} />;
export const SendIcon: React.FC<IconProps> = (props) => <Send {...defaultProps} {...props} />;
export const MessageCircleIcon: React.FC<IconProps> = (props) => <MessageCircle {...defaultProps} {...props} />;
export const CircleAlertIcon: React.FC<IconProps> = (props) => <AlertCircle {...defaultProps} {...props} />;
export const ArrowRightIcon: React.FC<IconProps> = (props) => <ArrowRight {...defaultProps} {...props} />;
export const ClockIcon: React.FC<IconProps> = (props) => <Clock {...defaultProps} {...props} />;
export const HelpCircleIcon: React.FC<IconProps> = (props) => <HelpCircle {...defaultProps} {...props} />;
export const MailIcon: React.FC<IconProps> = (props) => <Mail {...defaultProps} {...props} />;
export const ChevronDownIcon: React.FC<IconProps> = (props) => <ChevronDown {...defaultProps} {...props} />;
