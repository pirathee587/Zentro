import { createPortalRoutes } from '@/portals/public/routes';
import { PublicTrackPage } from '@/portals/customer/pages/PublicTrackPage';

// Compose cross-portal views in the app layer.
export const portalRoutes = createPortalRoutes(<PublicTrackPage />);
