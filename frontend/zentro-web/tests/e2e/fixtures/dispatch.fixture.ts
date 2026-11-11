import { type Page, type Route } from '@playwright/test';

export const PARCELS = [1, 2, 3].map(index => ({
  parcelId: `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`,
  trackingReference: `ZD092-${index}`, pickupSummary: 'Colombo pickup',
  dropoffSummary: 'Kandy drop-off', createdAt: `2026-11-01T0${index}:00:00Z`,
}));

export const COURIERS = [
  { id: '22222222-2222-4222-8222-000000000001', userId: '33333333-3333-4333-8333-000000000001', name: 'Courier One', active: true, accountStatus: 'ACTIVE' },
  { id: '22222222-2222-4222-8222-000000000002', userId: '33333333-3333-4333-8333-000000000002', name: 'Courier Two', active: true, accountStatus: 'ACTIVE' },
  { id: '22222222-2222-4222-8222-000000000003', userId: '33333333-3333-4333-8333-000000000003', name: 'Inactive Courier', active: false, accountStatus: 'ACTIVE' },
  { id: '22222222-2222-4222-8222-000000000004', userId: '33333333-3333-4333-8333-000000000004', name: 'Disabled Courier', active: true, accountStatus: 'DISABLED' },
];

type Assignment = { parcelId: string; courierId: string; assignedAt: string; proofId: null; completionState: null };
const eligible = (courier: typeof COURIERS[number]) => courier.active && courier.accountStatus === 'ACTIVE';

// Browser response model only. S3 tests establish authorization and persistence guarantees.
export class DispatchScenario {
  readonly parcels = PARCELS.map(parcel => ({ ...parcel }));
  readonly couriers = COURIERS.map(courier => ({ ...courier }));
  readonly assignments = new Map<string, Assignment>();
  readonly assignmentResults: number[] = [];
  readonly calls: string[] = [];
  assignFailure?: 401 | 403;
  private arrivals = 0;
  private release?: () => void;
  private gate?: Promise<void>;
  private expectedArrivals = 0;

  coordinateAssignments(count: number) {
    this.expectedArrivals = count;
    this.gate = new Promise(resolve => { this.release = resolve; });
  }

  async install(page: Page, courierId?: string) {
    await page.route('**/api/v1/dispatch/**', async route => {
      const request = route.request();
      const url = new URL(request.url());
      this.calls.push(`${request.method()} ${url.pathname}`);
      if (url.pathname.endsWith('/queue')) {
        const items = this.parcels.filter(parcel => !this.assignments.has(parcel.parcelId));
        return route.fulfill({ json: { items, total: items.length, matchingTotal: items.length, complete: true } });
      }
      if (url.pathname.endsWith('/workload')) {
        const items = this.couriers.map(courier => ({
          courierId: courier.id, userId: courier.userId, name: courier.name,
          active: courier.active, accountStatus: courier.accountStatus,
          assignmentCount: [...this.assignments.values()].filter(item => item.courierId === courier.id).length,
          availability: eligible(courier) ? 'AVAILABLE' : 'UNAVAILABLE',
        })).filter(courier => {
          const term = url.searchParams.get('search')?.toLowerCase() ?? '';
          const availability = url.searchParams.get('availability');
          return courier.name.toLowerCase().includes(term)
            && (!availability || courier.availability.toLowerCase() === availability);
        });
        return route.fulfill({ json: { items, total: items.length, overloadConfigured: false } });
      }
      if (url.pathname.endsWith('/eligible-couriers')) {
        return route.fulfill({ json: this.couriers.filter(eligible) });
      }
      if (request.method() === 'POST' && url.pathname.endsWith('/assignments')) {
        const body = request.postDataJSON() as { parcelId: string; courierId: string };
        if (this.gate) {
          this.arrivals++;
          if (this.arrivals === this.expectedArrivals) this.release!();
          await this.gate;
        }
        if (this.assignFailure) {
          this.assignmentResults.push(this.assignFailure);
          return this.problem(route, this.assignFailure, 'ACCESS_DENIED');
        }
        if (this.assignments.has(body.parcelId)) {
          this.assignmentResults.push(409);
          return this.problem(route, 409, 'DELIVERY_ALREADY_ASSIGNED');
        }
        const courier = this.couriers.find(item => item.id === body.courierId);
        if (!courier || !eligible(courier)) {
          this.assignmentResults.push(409);
          return this.problem(route, 409, 'COURIER_INELIGIBLE');
        }
        const assignment: Assignment = { ...body, assignedAt: '2026-11-01T12:00:00Z', proofId: null, completionState: null };
        this.assignments.set(body.parcelId, assignment);
        this.assignmentResults.push(201);
        return route.fulfill({ status: 201, json: {
          parcelId: assignment.parcelId, courierId: assignment.courierId,
          courierUserId: courier.userId, assignedBy: 'dispatcher-e2e-id', assignedAt: assignment.assignedAt,
        } });
      }
      throw new Error(`Unexpected dispatch request: ${request.method()} ${url.pathname}`);
    });

    await page.route(/\/api\/v1\/courier\/deliveries(?:\/[^?]*)?(?:\?.*)?$/, async route => {
      const path = new URL(route.request().url()).pathname;
      this.calls.push(`${route.request().method()} ${path}`);
      const id = path.split('/')[5];
      if (!id) return route.fulfill({ json: [...this.assignments.values()].filter(item => item.courierId === courierId) });
      const assignment = this.assignments.get(id);
      if (!assignment) return this.problem(route, 404, 'ASSIGNMENT_NOT_FOUND');
      if (assignment.courierId !== courierId) return this.problem(route, 403, 'DELIVERY_FORBIDDEN');
      if (path.endsWith('/proof')) return this.problem(route, 404, 'PROOF_NOT_FOUND');
      return route.fulfill({ json: assignment });
    });
  }

  private problem(route: Route, status: number, code: string) {
    return route.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({
      status, code, title: code, detail: code,
    }) });
  }
}
