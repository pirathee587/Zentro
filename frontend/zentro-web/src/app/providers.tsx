import type { PropsWithChildren } from 'react'; import { QueryClientProvider } from '@tanstack/react-query'; import { queryClient } from '@/shared/api/queryClient'; import { AuthProvider } from '@/shared/auth/AuthProvider';
export function Providers({children}:PropsWithChildren){return <QueryClientProvider client={queryClient}><AuthProvider>{children}</AuthProvider></QueryClientProvider>;}
