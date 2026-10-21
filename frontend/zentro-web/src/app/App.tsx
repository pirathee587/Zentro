import { BrowserRouter } from 'react-router-dom'; import { Providers } from './providers'; import { AppRouter } from './router'; import { ErrorBoundary } from './ErrorBoundary';
export function App(){return <ErrorBoundary><Providers><BrowserRouter><AppRouter/></BrowserRouter></Providers></ErrorBoundary>;}
