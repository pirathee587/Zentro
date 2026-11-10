# ZD-082 frontend route and permission regression (#157)

Owner: @14Jannan. Independent reviewer: @hephzibah-peries. Frontend platform owner (fixes in `shared/auth` and the public portal): @pirathee587.
Scope: portal route guards, navigation visibility and unauthorized redirects in `frontend/zentro-web` for the four roles CUSTOMER, MERCHANT, COURIER and DISPATCHER. Route guards and hidden links are user experience only: the backend (gateway and services) authorizes every request and stays the security boundary ([permission matrix](../security/permission-matrix.md)).
Code audited: `develop` at 9e42d14. No backend file changed.

## 1. Result

**The guards send every role to the right portal and fail closed.** Wrong roles, DISABLED or SUSPENDED accounts and tokens with only ADMIN or SERVICE get `/forbidden`; staff cannot open customer or merchant routes; merchant pages need an approved profile, and a profile that cannot be loaded denies access. Each portal shows only its own links, and administration links appear only in the Dispatcher portal.

Three defects were found and fixed, each with a regression test:

| # | Defect | Fix | Proof |
|---|---|---|---|
| F1 | A signed-out dispatcher opening `/dispatcher` started sign-in through the **customer** entry point and, after signing in, saw "This account does not have access to a Zentro portal." `ProtectedRoute` treated `/admin`, `/courier` and `/staff` as staff paths but not `/dispatcher`. | `/dispatcher` added to the staff paths (`shared/auth/ProtectedRoute.tsx`, one line) | `ProtectedRoute.test.tsx` entry-point table (the `/dispatcher` rows fail on `develop`); `CallbackPage.test.tsx` |
| F2 | A pending merchant applicant who reloaded `/merchant/dashboard` was sent to `/forbidden` before the profile arrived, because only users with the MERCHANT role waited for it. | The guard also waits for a CUSTOMER on `/merchant/dashboard` (`shared/auth/RoleGuard.tsx`) | `RoleGuard.test.tsx` (2 cases fail on `develop`); router-level case in `routeAccessMatrix.test.tsx` |
| F3 | Unknown paths rendered an empty page: the public portal's `/*` route matched everything but had no fallback, so the router's "Page not found" was unreachable. | `*` route with a new `NotFoundPage` (`portals/public/routes.tsx`, `pages/NotFoundPage.tsx`) | `portals/public/routes.test.tsx` (4 cases fail on `develop`); unknown path for every role in the matrix |

## 2. Access matrix (verified by `app/routeAccessMatrix.test.tsx` through the real `AppRouter` and `AuthProvider`)

"sign-in" = `ProtectedRoute` starts sign-in with the requested path as the return path. "open" = the portal renders (its navigation landmark is checked) at the same path.

| Route | Visitor | CUSTOMER | MERCHANT (approved) | CUSTOMER + MERCHANT | COURIER | DISPATCHER | DISABLED / SUSPENDED | ADMIN-only / SERVICE-only |
|---|---|---|---|---|---|---|---|---|
| `/customer/*` | sign-in | open | /forbidden | open | /forbidden | /forbidden | /forbidden | /forbidden |
| `/merchant/register-business`, `/merchant/status` | sign-in | open | /forbidden | open | /forbidden | /forbidden | /forbidden | /forbidden |
| `/merchant/dashboard/*` | sign-in | /forbidden (pending exception below) | open | open | /forbidden | /forbidden | /forbidden | /forbidden |
| `/courier/*` | sign-in (staff) | /forbidden | /forbidden | /forbidden | open | /forbidden | /forbidden | /forbidden |
| `/dispatcher/*` | sign-in (staff, F1) | /forbidden | /forbidden | /forbidden | /forbidden | open | /forbidden | /forbidden |
| `/admin/*` | sign-in (staff) | /forbidden | /forbidden | /forbidden | /forbidden | redirect to `/dispatcher` | /forbidden | /forbidden |
| `/styleguide`, `/forbidden` | open | open | open | open | open | open | open | open |
| unknown path | Page not found (F3) | Page not found | Page not found | Page not found | Page not found | Page not found | Page not found | Page not found |
| `/customer-preview/*` | open (F4) | open | open | open | open | open | open | open |

Merchant approval states (same file):

| User | `/merchant/dashboard` | `/merchant/dashboard/webhooks`, `/api-credentials` | `/merchant/status` |
|---|---|---|---|
| CUSTOMER with PENDING application | pending review page | /forbidden | status page ("Your application is PENDING") |
| CUSTOMER with REJECTED application or none | /forbidden | /forbidden | status page / application form |
| MERCHANT, approval PENDING, REJECTED or missing | /forbidden | /forbidden | — |
| MERCHANT approved | open | open | — |
| Profile still loading | "Checking merchant approval" spinner, then the result (F2) | — | — |
| Profile request fails (503) | /forbidden (fails closed) | — | — |

**Landing after sign-in** (`CallbackPage.test.tsx`):
- **Staff sign-in:** DISPATCHER (also with COURIER) goes to `/dispatcher`; COURIER goes to `/courier`; others see "This account is not enabled for staff."
- **Merchant sign-in:** MERCHANT goes to `/merchant/dashboard`; staff see "Use your staff sign-in."; a CUSTOMER applicant goes to `/merchant/status` or `/merchant/register-business`.
- **Customer sign-in:** a CUSTOMER goes to the requested `/customer/...` page or `/customer`, including when the return path points at another portal. Staff and tokens without a portal role see "This account does not have access to a Zentro portal."

**Navigation** (one test next to each layout):

| Layout | Links (exact) |
|---|---|
| Customer `Header` | Dashboard, Send parcel, Track, History, Receipts, Support (all `/customer...`) |
| `MerchantLayout` | Overview, API credentials, Webhooks (all `/merchant/dashboard...`) |
| `CourierLayout` | My dashboard, My profile (all `/courier...`) |
| `DispatcherLayout` | Overview, Dispatch Queue, Couriers, Workload, Merchant applications, Zones & pricing, Users, Integration attempts (all `/dispatcher...`; the only place with administration links) |
| Public home header | Login, Get Started, the same for a visitor and every role |

Multi-role users (CUSTOMER + MERCHANT, COURIER + DISPATCHER) see the same links as the single role: the layouts never show another portal's links. No `/admin` link or "Admin portal" exists anywhere.

**Backend permission errors:**
- **API client:** a gateway 401 or 403 becomes a normalized error and a safe message ("Your session has expired. Please sign in again." / "Your account does not have permission to do that."). It is not retried, the token is kept, and backend detail never reaches the message.
- **Pages:** in the merchant dashboard, the courier deliveries and the dispatcher Users page, a 401 or 403 keeps the portal open and shows that message; nothing crashes, goes blank or redirects.

## 3. Coverage before and after

| Area | Before | Added |
|---|---|---|
| Router-level role x route | 4 cells (`router.test.tsx`) | 82 matrix tests + 16 merchant-state tests + 3 permission-error tests (`app/routeAccessMatrix.test.tsx`) |
| `RoleGuard` | 7 | 5 (pending applicant while loading, F2) |
| `ProtectedRoute` | 2 | 12 (entry point per path, F1) |
| `CallbackPage` landing | 4 | 19 |
| Unknown paths | 0 | 5 (`portals/public/routes.test.tsx`, F3) |
| Navigation visibility | 0 | 17 (five layout tests) |
| API 401/403 | 1 (message mapping) | 2 (through the client) |

161 new tests; no existing test was removed, skipped or weakened. Every new table was checked against wrong expectations (flipped matrix cells fail), and each fix's test was run against unfixed `develop` to show it fails there.

## 4. Test results (actual commands, `frontend/zentro-web`)

| Script | `develop` (9e42d14) | This branch |
|---|---|---|
| `npm run lint` | 0 errors, 6 warnings | 0 errors, 6 warnings (the same existing warnings) |
| `npm run type-check` | pass | pass |
| `npm run test` | 52 files, 506 passed | 59 files, **667 passed** |
| `npm run build` | pass | pass (2196 modules, 29.8 s) |

Backend (`mvn --batch-mode --no-transfer-progress test package` from `backend/`, no backend file changed):

```
Zentro Security Starter ............ SUCCESS   Tests run: 7,   Failures: 0, Errors: 0, Skipped: 0
api-gateway ........................ SUCCESS   Tests run: 194, Failures: 0, Errors: 0, Skipped: 0
identity-partner-service ........... SUCCESS   Tests run: 347, Failures: 0, Errors: 0, Skipped: 0
parcel-pricing-service ............. SUCCESS   Tests run: 648, Failures: 0, Errors: 0, Skipped: 0
dispatch-courier-service ........... SUCCESS   Tests run: 257, Failures: 0, Errors: 0, Skipped: 0
tracking-reporting-service ......... SUCCESS   Tests run: 78,  Failures: 0, Errors: 0, Skipped: 0
notification-integration-service ... SUCCESS   Tests run: 419, Failures: 0, Errors: 0, Skipped: 0
merchant-order-webhook-e2e ......... SUCCESS   (compiled; its end-to-end tests run separately)
BUILD SUCCESS (13:28 min)
```

An earlier run, made while the frontend suite was running on the same machine, stopped because the gateway's Keycloak test container did not start in time; the unchanged module passed on the rerun above.

## 5. Findings and follow-ups

| # | Status | Finding | Owner |
|---|---|---|---|
| F1 | fixed | `/dispatcher` deep link signed staff in through the customer entry point | @pirathee587 (review) |
| F2 | fixed | Pending applicant reloading `/merchant/dashboard` was forbidden before the profile loaded | @pirathee587 (review) |
| F3 | fixed (public paths) | Unknown paths rendered an empty page. Unknown sub-paths inside a portal (for example `/courier/nope`) still render that portal's empty outlet. | @pirathee587; portal owners for portal sub-paths |
| F4 | open | `/customer-preview/*` renders the customer portal, with sample data, without sign-in. Data APIs still need a token. | @pirathee587 |
| F5 | open | The return path is kept only for `/customer/...`; staff and merchants land on their portal home (`/dispatcher/users` ends at `/dispatcher`). | @pirathee587 |
| F6 | open | `routeForRoles` (`shared/config/roles.ts`) is not used by production code; `CallbackPage` has its own landing order. | @pirathee587 |
| F7 | open | Until `/api/v1/users/me` loads, account status is unknown, so a DISABLED user can briefly see a portal before `/forbidden`. The backend rejects every request. | @pirathee587 |
| F8 | open | No global 401 handling: an expired session shows "Your session has expired" on the page but does not start sign-in. | @pirathee587 |
| F9 | open | A merchant-only account that signs in through the customer sign-in page sees "This account does not have access to a Zentro portal." instead of its dashboard. | @pirathee587 |
| F10 | open | Customer list pages use built-in sample data unless `VITE_USE_CUSTOMER_MOCKS=false` (only booking and fee estimates call S2), so backend 401/403 cannot appear there in the default mode. | customer portal owners |

**Role naming:** the code, WARP.md and the permission matrix use `DISPATCHER`; issues #190-#195 say `DISPATCHER_ADMIN`. This work keeps `DISPATCHER` (no new role and no Admin portal).
