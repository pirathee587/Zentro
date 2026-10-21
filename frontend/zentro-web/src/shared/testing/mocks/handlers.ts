import { http, HttpResponse } from 'msw'; export const handlers=[http.get('*/api/v1/tracking/:trackingNumber',()=>HttpResponse.json({status:'CREATED',timeline:[]}))];
