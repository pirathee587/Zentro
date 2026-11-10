# Prepared follow-up issue text for #176

These are proposals only. No issues, contracts, frontend changes, migrations or caches are created by this document. Measurements and raw CSV live in [performance-smoke-tests.md](performance-smoke-tests.md) and its reference evidence directory. Runtime volumes below are sensitivity assumptions, not verified business forecasts. Owners are recent code authors verified against GitHub commit authorship; this does not assign work to them.

## S3: implement the parcel-source contract, then page queue and workload

Owner: @Nilakshana23, verified by commit `09ec1f7f8c3c1407f18115fed632e1b753b87c55` on `DispatchService` (next action: coordinate eligibility/conditional-claim contract with S2's maintainer).

Current behavior: queue/unassigned return an entire snapshot and workload an entire courier collection. Both have unique ordering ties already. Queue and assignments use `UnavailableParcelSource`: source 503 is sanitized to gateway 502; successful queue volume/growth and assignment-race evidence are **not available**. No replacement source was supplied. Workload growth is measured at 100/1k/5k/10k courier profiles with independently seeded assignments; the per-volume numbers and concurrency table are attached below. The assignment seed belongs to one courier to test a skewed workload, not a realistic fleet distribution. At 10k scope, workload returns 10,001 rows / approximately 2.11 MB. No missing-index or pool cause is proven by those timings alone.

Proposed contract, subject to approval: opt-in `/api/v1/dispatch/queue/page` and `/workload/page` variants, size default 25/max 100, preserve existing sort directions and use existing parcel/courier ID ties. Queue cursor includes creation time and parcel ID after eligibility reconciliation; workload may start with offset paging because it is a changing operational aggregate. Return `hasNext` and make expensive exact totals opt-in. Preserve old endpoints during a documented migration window. A snapshot's `complete` signal must not be confused with page completeness. Do not cache S1 approval or change consistency without separate review.

The blocked `/dispatch/eligible-couriers` whole-list endpoint belongs in this same contract review: an opt-in paged variant with default 25/max 100, name/courier-ID cursor and `hasNext` must still re-check parcel eligibility. `AssignmentDialog` currently uses `['dispatcher','eligible-couriers',{parcelId,scope}]`; paging/search must enter that key and preserve parcel/account scoping. No eligible-courier growth is measurable until the source contract exists.

Frontend impact: dispatcher `DispatchQueuePage`, `WorkloadPage`, `CourierWorkloadCard`, `AssignmentDialog`; `useDispatchQueue` in `api/dispatchApi.ts`, `useCourierWorkload`/`workloadQueryKey` in `api/workloadApi.ts`; shared keys `queryKeys.dispatcher.queue(filters, scope)` and `.workload(params, scope)`. Add cursor/page and size to keys; selection/assignment invalidations must refresh affected pages. Keep the existing queue envelope adapter during migration.

Tests needed: real source eligibility and single-winner claim race through gateway; stable ties; first/deep/beyond-last pages; ownership/dispatcher permissions on every page; filters and sort validation; concurrent inserts; bounded counts/payloads; growth with an actual queue. Prioritisation scenarios: 1k and 10k open items, pending real operational data. Queue currently has no performance threshold crossing to report because integration is unavailable.

## S3: bounded courier delivery history

Owner: @pavi3alnx, verified by commit `fdaa103f0901d0f27e2cbc7a8d66581a4903179f` on `ProofRepository` (next action: agree daily versus lifetime history semantics).

Current behavior: all lifetime assignments for the authenticated profile/user pair, with optional proof join; deterministic `assigned_at DESC, parcel_id` order. No date window, page or maximum. Growth at 100/1k/5k/10k assignments is recorded in both reference CSVs with owning-database EXPLAIN plans and independent ownership-set checks. A daily planning assumption of 100 assignments does not bound the present lifetime query; 10k lifetime assignments is plausible as a sensitivity case, not a product forecast. Threshold crossing and bytes belong in the reference growth table, not an inferred capacity claim.

Proposed contract: opt-in `/api/v1/courier/deliveries/page`, cursor `(assignedAt, parcelId)`, default 25/max 100, unchanged existing tie order, `hasNext` rather than mandatory lifetime total. Add an explicit date filter only after agreeing its meaning. Keep the old array endpoint until clients migrate; do not silently restrict it to today.

Frontend impact: `TodayDeliveriesPage`, `DeliveryDetailPage`'s list-cache updates, `api/deliveryApi.ts`; `queryKeys.courier.deliveries(scope)` needs date/cursor/size; detail/proof keys remain scoped by account and delivery. Existing code assumes an array and optimistic list updates need paged-cache support.

Tests needed: same-role different-owner isolation at every size/page, primary and tied sort keys, all-page union versus independent owning-DB set, last/beyond-last, cursor tampering, proof join completeness, date boundaries, cancellation and query invalidations.

## S2: traversable merchant order history (already bounded)

Owner: @pirathee587, verified by commit `fdd9133b3f92f4e73c9c0f873c007365d7318554` on `MerchantOrderController` (next action: agree a backward-compatible history contract with merchant frontend ownership).

Current behavior: plain array with `limit=50`, clamped 1–100; no page/cursor or total. Repository filters by merchant subject and orders by `created_at DESC` without a unique tie. Every list call verifies merchant approval through S1. Growth seed is 100/1k/5k/10k orders in one merchant scope, but the default response stays at 50; do not classify it as an unbounded response. API response and DB-call measurements include the approval dependency; no cache was added. Monthly volumes 1k–10k are sensitivity cases only.

Proposed contract: opt-in `/api/v1/merchant/orders/page`, cursor `(createdAt, id)` descending, default 50/max 100, `hasNext`, optional exact count if product needs one. Preserve the current `limit` array variant; migrating clients explicitly accept the envelope and new tie semantics. No per-request S1 cache without an approved consistency/authorization decision.

Frontend impact: merchant `DashboardPage` consumers of `useMerchantOrdersQuery` in `hooks/useMerchantQueries.ts`, `api/merchantApi.ts`, keys `merchantQueryKeys.orders(userId)` → shared `queryKeys.merchant.orders(params, scope)`. Add cursor/size/filter to keys and keep authentication scope. Confirm every consumer before removing the array adapter.

Tests needed: 100-row bound and invalid limit conversion; equal-created timestamps; complete traversal beyond the present first 100; merchant approval/revocation behavior; independent owner set; concurrent submissions and unique external refs; remote approval latency/failure isolation. Index review should use the existing merchant/created partial index before proposing another.

## S1: paged dispatcher user, merchant and courier lists

Owners from recent code history: @14Jannan for users (`0149957248e937c957b7b177e013329ee24df68f`), @kirusigan03 for merchant applications (`84f078a7716bd3fcba1276a0d0ab5d597b3fbbf4`), @pirathee587 for courier profiles (`fdd9133b3f92f4e73c9c0f873c007365d7318554`). Next action: confirm one S1 maintainer and approve a coordinated list contract.

Current behavior: whole arrays with no max/count. User/courier lists sort by created time descending and merchant applications ascending; none has a unique tie. Administrative access belongs to DISPATCHER. Growth seeds 100/1k/5k/10k synthetic records and records times, bytes, rows, DB time/calls and host memory. Whole-table scans can be appropriate for the current all-row contract; a scan alone is not a proven missing-index bottleneck. These platform-wide lists plausibly grow beyond daily operational views, but actual account forecasts were not available.

Proposed contract: opt-in `/api/v1/admin/users/page`, `/merchants/page`, `/couriers/page`, page=0/size=25, max=100, preserve respective primary sort direction with explicit ID tie, exact totals only if justified by measured COUNT cost. Cursor versions may be preferable for large histories. Keep existing arrays during migration, reject unknown sort names through an allowlist, bind filters and document snapshot semantics.

Frontend impact: dispatcher `UsersPage` (`usersKey`: dispatcher/admin-users + filters), `MerchantApprovalsPage`/`useMerchantApplications` (`applicationsKey=['dispatcher','merchant-applications']`), `CouriersPage` (`['dispatcher','couriers', queryParams]`), `api/adminApi.ts`, `api/courierAdminApi.ts`; S3's S1 courier client also consumes the courier array and must explicitly adopt a traversable API before that legacy path changes. Include page/size and owner scope where applicable in query keys and approval/update invalidations.

Tests needed: Dispatcher-only checks at each page/size; stable ties and full-union counts; filters/search injection-like strings; bounded response fields and PII minimisation; empty/last/absurd pages; S3 remote pagination compatibility; counts and EXPLAIN at realistic growth.

## S4: unbounded public tracking histories

Owner: @kirusigan03, verified by commit `af25429ccd41d56d0678a312c1e54b0f19093275` on `TrackingService`. Next action: agree a per-parcel event retention/pagination contract and obtain expected history cardinalities.

Current behavior: public tracking summary embeds every event; `/events` returns the whole chronological history. Missing event-ID tie remains unchanged. Both reference runs include independent 100/1k/5k/10k-event growth curves, attached below with owning-database plans. At 10k events, public summary is approximately 4.05 MB and `/events` 2.27 MB. These generous bounds do not justify allowing histories to grow indefinitely. Public history excludes contact details/fees through the current mapping; large-history field-by-field minimisation remains incomplete.

Proposed contract: add a separate opt-in cursor history resource with `(occurredAt,id)`, default 50/max 100, `hasNext` and optional total, preserving old summary/history responses until frontend migration. Resolve whether summary should show only milestones as a separate approved change. No existing response is truncated here.

Frontend impact: public `TrackingPages.tsx`, customer `HistoryPage`/`TrackView`, shared `trackingApi.ts`/`useTrackingStream.ts` and customer `services/customerApi.ts`; customer keys `queryKeys.customer.parcels(params)` / `.trackingMine()` and `queryKeys.tracking.parcel(ref)`. SSE reconnection and event deduplication need event-ID-aware cursors rather than an unrelated offset page.

Tests needed: privacy/ownership across all history pages, tie order, insertion and SSE catch-up, payload bytes and serialization/DB time for a single long-lived parcel, safe unsupported parameters, stale cursor behavior, all consumers and query-key invalidations.

## S2: active-zone configuration list

Owner: @Kiruthiyan, verified by commit `7e1e0d569ab1a41f1645a1bdd03b7732361f0f97` on `ZoneService`. Next action: confirm realistic configured-zone counts before prioritising a paged contract.

Current behavior: `/api/v1/zones` returns the whole active-zone array ordered by name, without a unique tie or maximum. Both reference runs measure 100/1k/5k/10k additional zones (plus five existing zones); at the largest volume the payload is 1,318,647 bytes. HTTP/DB time, row counts and plans are attached below. Such counts are sensitivity cases, not an expected zoning model.

Proposed contract: only if realistic cardinality warrants it, an opt-in `/api/v1/zones/page`, default 50/max 100, name/code/ID stable tie, optional total or `hasNext`, keeping the old array until migration. Current consumers found read-only: dispatcher `api/pricingApi.ts` → `getZones`, `pages/usePricing.ts` → `pricingKeys.zones=['dispatcher','zones']`; update the consuming pricing page, hook and key with page/size. No customer-portal `/api/v1/zones` caller was found in the source search; recheck before a contract release. Tests needed: active filtering, ties, empty/beyond-last pages, count policy, payload bound, quotation compatibility and frontend adapter migration.

## S4: malformed page parameters and deterministic page order

Owner: @kirusigan03 (same verified history). Next action: handle conversion/overflow errors as a safe client error under an approved error contract; agree a visible timestamp-ID tie separately. Current reproduction: `GET /api/v1/tracking/mine?size=abc` with CUSTOMER token returns 500 on the disposable PostgreSQL-backed application. Negative numeric values clamp rather than reject; maximum size already caps at 100. The normal test records this defect and the PR does not claim invalid-parameter acceptance is complete. Offset insert behavior and merchant-subject query fallback also need explicit regression coverage before any authorization/query redesign.

## S4: reporting smoke blocked by missing API contract

Owner: **to be assigned** (no reports controller history exists). Next action: nominate the S4 API owner and agree the reporting API/metrics contract, then add a real gateway scenario. #74 tracks the consuming reports dashboard, not an implemented API. No fabricated reporting benchmark, schema access to another service or placeholder endpoint is proposed.

## Measured growth attachment for proposed issues

Every area above uses its own query plans and owning-database diagnostics. No whole-list request exceeded 1000 ms or 16 MiB at the measured single-request volumes through 10k; the crossing volume is therefore **not observed**, not an estimated capacity. Workload's 25-client p95 missed the 1000 ms bound in both final runs (see concurrency evidence). Rows include fixture records as returned, not only nominal seed. Each volume also repeats the query without writes and checks equal responses; missing unique sort ties still have no deterministic SQL guarantee. Contrasting foreign merchant/courier rows make independent ownership-set checks meaningful at every size.

### Run 1: latency and byte growth by owner scope

| Endpoint | Seed | Rows | Bytes | HTTP ms | DB ms | Calls |
|---|---:|---:|---:|---:|---:|---:|
| /api/v1/admin/users | 100 | 102 | 17509 | 45.420 | 0.246 | 1 |
| /api/v1/admin/merchants | 100 | 101 | 30243 | 34.870 | 0.248 | 1 |
| /api/v1/admin/couriers | 100 | 101 | 30685 | 37.444 | 0.202 | 1 |
| /api/v1/dispatch/workload | 100 | 101 | 21141 | 140.159 | 0.613 | 2 |
| /api/v1/courier/deliveries | 100 | 100 | 18401 | 73.716 | 0.366 | 4 |
| /api/v1/merchant/orders | 100 | 50 | 26683 | 433.876 | 1.445 | 3 |
| /api/v1/zones | 100 | 105 | 13643 | 61.365 | 0.475 | 1 |
| /api/v1/tracking/PERF-HISTORY | 100 | 100 | 40841 | 388.997 | 0.321 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 100 | 100 | 22701 | 51.498 | 0.359 | 2 |
| /api/v1/admin/users | 1000 | 1002 | 173210 | 84.027 | 2.325 | 1 |
| /api/v1/admin/merchants | 1000 | 1001 | 299343 | 49.185 | 2.134 | 1 |
| /api/v1/admin/couriers | 1000 | 1001 | 305186 | 53.525 | 2.661 | 1 |
| /api/v1/dispatch/workload | 1000 | 1001 | 210144 | 88.708 | 3.057 | 2 |
| /api/v1/courier/deliveries | 1000 | 1000 | 184001 | 164.835 | 5.471 | 4 |
| /api/v1/merchant/orders | 1000 | 50 | 26801 | 133.600 | 0.694 | 3 |
| /api/v1/zones | 1000 | 1005 | 130645 | 73.686 | 2.509 | 1 |
| /api/v1/tracking/PERF-HISTORY | 1000 | 1000 | 405341 | 120.145 | 2.095 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 1000 | 1000 | 227001 | 64.704 | 1.687 | 2 |
| /api/v1/admin/users | 5000 | 5002 | 869210 | 155.893 | 18.024 | 1 |
| /api/v1/admin/merchants | 5000 | 5001 | 1495343 | 63.786 | 9.014 | 1 |
| /api/v1/admin/couriers | 5000 | 5001 | 1529186 | 109.337 | 10.373 | 1 |
| /api/v1/dispatch/workload | 5000 | 5001 | 1054144 | 136.566 | 9.473 | 2 |
| /api/v1/courier/deliveries | 5000 | 5000 | 920001 | 120.002 | 15.441 | 4 |
| /api/v1/merchant/orders | 5000 | 50 | 26901 | 103.798 | 0.576 | 2 |
| /api/v1/zones | 5000 | 5005 | 658645 | 97.906 | 10.396 | 1 |
| /api/v1/tracking/PERF-HISTORY | 5000 | 5000 | 2025341 | 233.710 | 11.236 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 5000 | 5000 | 1135001 | 73.177 | 5.478 | 2 |
| /api/v1/admin/users | 10000 | 10002 | 1739211 | 83.286 | 27.392 | 1 |
| /api/v1/admin/merchants | 10000 | 10001 | 2990343 | 70.159 | 16.911 | 1 |
| /api/v1/admin/couriers | 10000 | 10001 | 3059187 | 98.716 | 11.632 | 1 |
| /api/v1/dispatch/workload | 10000 | 10001 | 2109147 | 113.346 | 18.391 | 2 |
| /api/v1/courier/deliveries | 10000 | 10000 | 1840001 | 145.757 | 24.535 | 4 |
| /api/v1/merchant/orders | 10000 | 50 | 26901 | 54.669 | 0.711 | 2 |
| /api/v1/zones | 10000 | 10005 | 1318647 | 118.941 | 29.839 | 1 |
| /api/v1/tracking/PERF-HISTORY | 10000 | 10000 | 4050341 | 166.268 | 9.836 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 10000 | 10000 | 2270001 | 75.029 | 7.055 | 2 |

### Run 2: latency and byte growth by owner scope

| Endpoint | Seed | Rows | Bytes | HTTP ms | DB ms | Calls |
|---|---:|---:|---:|---:|---:|---:|
| /api/v1/admin/users | 100 | 102 | 17509 | 63.959 | 0.286 | 1 |
| /api/v1/admin/merchants | 100 | 101 | 30243 | 69.305 | 0.467 | 1 |
| /api/v1/admin/couriers | 100 | 101 | 30685 | 60.678 | 0.482 | 1 |
| /api/v1/dispatch/workload | 100 | 101 | 21141 | 144.827 | 0.281 | 2 |
| /api/v1/courier/deliveries | 100 | 100 | 18401 | 64.399 | 0.349 | 4 |
| /api/v1/merchant/orders | 100 | 50 | 26683 | 888.872 | 0.395 | 3 |
| /api/v1/zones | 100 | 105 | 13643 | 35.859 | 0.258 | 1 |
| /api/v1/tracking/PERF-HISTORY | 100 | 100 | 40841 | 332.533 | 0.366 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 100 | 100 | 22701 | 41.639 | 0.278 | 2 |
| /api/v1/admin/users | 1000 | 1002 | 173210 | 37.932 | 1.572 | 1 |
| /api/v1/admin/merchants | 1000 | 1001 | 299343 | 39.413 | 1.535 | 1 |
| /api/v1/admin/couriers | 1000 | 1001 | 305186 | 41.004 | 1.900 | 1 |
| /api/v1/dispatch/workload | 1000 | 1001 | 210144 | 84.821 | 1.976 | 2 |
| /api/v1/courier/deliveries | 1000 | 1000 | 184001 | 102.082 | 3.445 | 4 |
| /api/v1/merchant/orders | 1000 | 50 | 26801 | 77.847 | 0.361 | 2 |
| /api/v1/zones | 1000 | 1005 | 130645 | 39.580 | 2.206 | 1 |
| /api/v1/tracking/PERF-HISTORY | 1000 | 1000 | 405341 | 121.338 | 2.428 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 1000 | 1000 | 227001 | 51.026 | 1.896 | 2 |
| /api/v1/admin/users | 5000 | 5002 | 869210 | 56.665 | 8.174 | 1 |
| /api/v1/admin/merchants | 5000 | 5001 | 1495343 | 72.887 | 7.500 | 1 |
| /api/v1/admin/couriers | 5000 | 5001 | 1529186 | 65.917 | 9.045 | 1 |
| /api/v1/dispatch/workload | 5000 | 5001 | 1054144 | 152.980 | 16.066 | 2 |
| /api/v1/courier/deliveries | 5000 | 5000 | 920001 | 71.862 | 8.617 | 4 |
| /api/v1/merchant/orders | 5000 | 50 | 26901 | 65.818 | 0.300 | 2 |
| /api/v1/zones | 5000 | 5005 | 658645 | 65.009 | 13.352 | 1 |
| /api/v1/tracking/PERF-HISTORY | 5000 | 5000 | 2025341 | 143.709 | 7.141 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 5000 | 5000 | 1135001 | 105.655 | 6.436 | 2 |
| /api/v1/admin/users | 10000 | 10002 | 1739211 | 80.489 | 24.495 | 1 |
| /api/v1/admin/merchants | 10000 | 10001 | 2990343 | 57.226 | 13.843 | 1 |
| /api/v1/admin/couriers | 10000 | 10001 | 3059187 | 69.823 | 12.398 | 1 |
| /api/v1/dispatch/workload | 10000 | 10001 | 2109147 | 127.998 | 26.784 | 2 |
| /api/v1/courier/deliveries | 10000 | 10000 | 1840001 | 97.222 | 17.230 | 4 |
| /api/v1/merchant/orders | 10000 | 50 | 26901 | 44.581 | 0.385 | 2 |
| /api/v1/zones | 10000 | 10005 | 1318647 | 87.718 | 34.138 | 1 |
| /api/v1/tracking/PERF-HISTORY | 10000 | 10000 | 4050341 | 101.479 | 8.634 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 10000 | 10000 | 2270001 | 83.937 | 9.654 | 2 |

### Workload concurrency evidence at 10k courier scope

| Run | Clients | req/s | p95 ms | p99 ms | Error % |
|---|---:|---:|---:|---:|---:|
| 1 | 1 | 9.447 | 177.783 | 198.801 | 0.00 |
| 1 | 5 | 35.084 | 273.369 | 282.292 | 0.00 |
| 1 | 10 | 25.463 | 696.521 | 701.936 | 0.00 |
| 1 | 25 | 23.669 | 1718.516 | 1755.466 | 0.00 |
| 2 | 1 | 10.484 | 137.035 | 146.795 | 0.00 |
| 2 | 5 | 25.878 | 239.617 | 241.690 | 0.00 |
| 2 | 10 | 20.498 | 691.791 | 733.349 | 0.00 |
| 2 | 25 | 29.253 | 1051.589 | 1082.212 | 0.00 |
