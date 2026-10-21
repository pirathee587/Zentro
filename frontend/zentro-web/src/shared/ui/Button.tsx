import { cva, type VariantProps } from 'class-variance-authority';
import type { ButtonHTMLAttributes } from 'react';
const button = cva('inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50', {variants:{variant:{primary:'bg-primary text-on-primary hover:bg-primary-hover',secondary:'bg-surface text-text border border-border hover:bg-background',danger:'bg-danger text-surface'},size:{sm:'px-3 py-1.5',md:'px-4 py-2'}},defaultVariants:{variant:'primary',size:'md'}});
export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof button>;
export function Button({variant,size,className,...props}:ButtonProps) { return <button className={button({variant,size,className})} {...props} />; }
