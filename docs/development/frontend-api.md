# Frontend API and server state

The frontend routes REST calls through `frontend/zentro-web/src/shared/api/apiClient.ts`. It is the only configured Axios instance and takes its base URL from the shared env configuration. Its timeout remains 5 seconds. `setAccessToken` supplies the current auth-provider token; `withBearerToken` applies request-specific tokens for existing auth contexts that need them. No request or response body, token, address, or phone is logged.

The shared response interceptor maps Axios failures to `ApiClientError`, an Axios-compatible subclass. It exposes `category` (`http`, `network`, `timeout`, or `canceled`), optional `status`, and problem details (`title`, `detail`, `fieldErrors`, `invalidFields`, `instance`). Feature helpers such as `BookingError` keep their existing user-facing messages and field mappings. The existing auth provider still owns its established profile re-login behavior; ordinary 401/403 responses are not retried by query defaults.

The tracking SSE connection in `shared/domain/tracking/useTrackingStream.ts` intentionally uses `fetch`, because EventSource-style streaming is not an Axios request. It uses the shared env base URL and current access token. New REST endpoints belong in their portal service module and use `apiClient`; do not create another Axios instance or call `fetch` for REST.

## TanStack Query defaults

The app uses the single `shared/api/queryClient.ts` instance. Query defaults are a 30 second stale time, five minute garbage collection, no focus refetch, and at most two retries with a 300ms exponential delay capped at one second. Only normalized network errors and HTTP 5xx errors retry. Client errors, including 400, 401, 403, 404, 409, and 422, do not retry. Mutations never retry automatically; retrying writes remains an explicit user action and booking retains its `Idempotency-Key` behavior.

Use `shared/api/queryKeys.ts` for shared domains. Keys are arrays beginning with `[domain, resource]`; parameterized keys append a stable, sorted, serializable params object. Do not put auth tokens or personal fields in keys. Root keys without params are used as prefixes when a mutation should invalidate all parameter variations.

## Mutation invalidation matrix

| Successful mutation | Invalidated keys |
| --- | --- |
| Customer parcel booking | `customer/parcels`, `customer/dashboard`, `customer/tracking/mine` |
| Dispatcher assignment | `dispatcher/queue`, `dispatcher/workload` |
| Courier proof submission | `courier/deliveries`, the submitted `courier/delivery` |
| Merchant API key issue or revoke | the current merchant API key |
| Merchant application review | dispatcher merchant applications |
| Courier profile provision or update | courier profile |
| Pricing rule update | pricing rule (the returned rule is also written to cache) |

Invalidation is success-only and scoped to the affected resource families. Existing cache updates occur only after a confirmed write; do not add optimistic updates without a rollback path. Dispatch assignment's existing queue cache update runs after success.

## Adding an endpoint

1. Add the typed operation to its owning portal API module and call `apiClient` with the existing `/api/...` path and request contract.
2. For queries, pass TanStack Query's `signal` through to the Axios config so navigation and query cancellation abort the request.
3. Reuse the domain key factory, adding stable params only when they distinguish server results.
4. For mutations, define success invalidations through `queryInvalidation.ts`; leave failures cache-neutral. Keep writes non-retrying and retain any established idempotency key handling.
5. Map errors through the shared `ApiClientError` and existing feature mapping. Do not log sensitive data or introduce a new auth flow.
