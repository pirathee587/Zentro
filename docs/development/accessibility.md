# Portal accessibility and responsive review (#164)

Target: **WCAG 2.2 AA**. This is source review, DOM/keyboard simulation and token mathematics,
not a conformance certification. Real browser rendering, screen readers and devices were **not run**.
The PR remains Draft with `Refs #164` pending manual verification and independent review.

Base: `develop` at `564c9eed7580b14e556a6786aaab02e77355179f`.
No open PRs were returned during inspection. #156/PR #293, #154/PR #311 and #56/PR #108
were merged; #124's frontend alignment follow-up #300 was closed. Business validation is outside this PR.
API calls, route definitions, auth, query keys, business decisions and contracts are unchanged.
Backend/gateway and infrastructure files were not changed or tested.

## Findings and evidence

Severity describes the observed source/DOM problem; visual rendering was not inferred from jsdom.

| ID | Portal / flow | Classification / severity | WCAG | Result and evidence |
|---|---|---|---|---|
| A01 | Customer dark support / assigned-courier panel | PROVEN GAP / major | 1.4.3 | Fixed: dark `onDark` text on dark panel was 1.141:1; panel-specific text is 16.393:1. `customer/layout/customer.css:13`, `SupportPage.tsx:208`, `components/TrackView.tsx:643`. |
| A02 | Shared focus / customer fields | PROVEN GAP / major | 1.4.11, 2.4.7 | Fixed token models: gold focus on cream 1.770:1 and input borders 1.315/1.509:1. Existing palette tokens provide two-tone focus; input borders use readable muted tokens. `styles/accessibility.css:2`. Forced-colors appearance is NOT run. |
| A03 | Customer text on gradient | PROVEN GAP / major | 1.4.3 | Fixed `--mute` from #6f6f6f to #686868: gradient endpoint ratios 4.174/4.333 → 4.629/4.805. Brand gold/backgrounds unchanged. `customer.css:22,58`. |
| A04 | Dispatcher user edit panel | PROVEN GAP / major | 2.1.1, 2.4.3 | Fixed missing modal keyboard lifecycle: focus enters, wraps, Escape closes and focus returns. Existing assignment/pricing dialogs already implement their lifecycle. `UsersPage.tsx:74,102`, shared `useDialogFocus.ts:4`. |
| A05 | All portal shells | PROVEN GAP / major | 2.4.1, 2.4.2, 3.1.1 | Fixed absent skip links/document language/mobile viewport; static route titles and main focus added. `index.html:2`, `shared/ui/RouteAccessibility.tsx:15`. No identifiers or account names enter titles. |
| A06 | Customer Send Parcel | PROVEN GAP / major | 1.3.1, 3.3.1, 2.4.3 | Fixed ordered step list/current step, first invalid field focus, step focus, autocomplete and required select semantics. `SendParcelPage.tsx:50,302,338`. |
| A07 | Customer History mobile | PROVEN GAP / major | 1.4.10, 1.3.1 | Removed source-level Route/Date hiding; retained all columns in a named, keyboard-focusable horizontal scroll region. `HistoryPage.tsx:281`, `customer.css:2083`. Actual overflow/reflow NOT run. |
| A08 | Customer profile / shared Field | PROVEN GAP / major | 4.1.2, 3.3.1 | Profile is a native button disclosure with Escape/blur behavior instead of an incomplete menu pattern. Field composes external descriptions with its error ID and prevents caller attributes erasing invalid state. `Header.tsx:104`, `Field.tsx:30`. |
| A09 | Merchant one-time API key | PROVEN GAP / major | 4.1.3, 2.4.3 | Removed secret from a live region; focus moves to the instruction heading. Only fixed copy-status text is announced. `ApiCredentialsPage.tsx:12,29`. |
| A10 | Public tracking form | PROVEN GAP / major | 3.3.1, 4.1.2, 1.3.1 | Invalid state/error association/focus and tracking main landmark added. Existing submission/normalization and routes retained. `TrackingPages.tsx:45`, `PublicLayout.tsx`. |
| A11 | Customer empty/error headings | PROVEN GAP / minor | 1.3.1 | Fixed h1→h3 jump in History empty/error state; axe heading-order 1 → 0 per affected snapshot. Dashboard loading/error retains h1. `components/EmptyState.tsx:24`, `CustomerDashboard.tsx:218`. |
| A12 | Merchant order table / dashboard states | PROVEN GAP / minor | 1.3.1, 4.1.3 | Named table, scoped headings, focusable scroll region and loading/error h1; shared empty announcements retained. `merchant/pages/DashboardPage.tsx:40,74`. |
| A13 | Courier assignments/proof | OK / source and DOM only | 3.3.1, 4.1.2 | Existing proof labels/error associations and first-error focus verified with keyboard simulation (`ProofOfDeliveryForm.tsx:33`). Shell gains shared skip/title/focus behavior; API and session logic untouched. |
| A14 | Themes / real responsive rendering | RISK / major | 1.4.3, 1.4.10, 2.4.11 | Source fixes and token models are covered, but real light/dark cascades, zoom, clipping, fixed elements, touch size and safe areas require manual checks. Full cross-portal theme unification is a follow-up. |
| A15 | Styleguide generic Modal | PROVEN GAP / minor, outside critical flows | 2.1.1, 2.4.3 | Follow-up: used only in Styleguide; lacks a complete dismissal/focus contract. Not replaced or newly adopted by a portal. |
| A16 | Existing dependency advisories | RISK / high/critical advisories | Outside WCAG | Production audit: 4 (2 moderate, 2 high); full audit: 20 (12 moderate, 5 high, 3 critical). Neither approved package appears in the advisory report. Remediation is a separate dependency task. |

All frontend paths above are under `frontend/zentro-web/src/` unless noted. No keyboard trap
or proven 375px flow failure was claimed: real layout was not available. The user-edit defect
allowed focus to escape the declared modal; it was not an inescapable trap.

## Critical flow inventory

“Source/regression” is inspection plus existing component coverage, not a keyboard/device pass.
“Axe” refers only to the exact snapshots in the next section. Every row still needs manual checks.

| Portal / flow | Existing route | Key components | Review status |
|---|---|---|---|
| Public sign-in landings | /login, /staff/login, /merchant/login | AuthPages, PublicLayout | Source/regression; shared shell fix; real sign-in NOT run |
| Customer dashboard | /customer | CustomerDashboard | Source/regression; loading/error headings |
| Customer details → review → confirmation/reference | /customer/send | SendParcelPage, Field, ordered stepper | Keyboard simulation, axe default, contrast models |
| Customer tracking | /customer/track, /customer/track/:trackingRef | TrackView, Timeline, courier panel | Source/regression; panel contrast and timeline current state |
| Customer History/filter/search | /customer/history | HistoryPage, chips/table | Keyboard simulation; axe default/loading/empty/error |
| Customer receipts/print | /customer/receipts | ReceiptsPage | Source/regression; print ordering/layout NOT run |
| Customer support/chat/contact/FAQ | /customer/support | SupportPage, native details/summary, Field | Chat Enter simulation, axe default, panel models; contact error focus |
| Public tracking search/result | /track, /track/:trackingNumber | TrackingPages, StatusTimeline | Axe search/default and result/default/loading/empty/error |
| Merchant dashboard/orders/delivery activity | /merchant/dashboard | DashboardPage | Axe default/loading/empty/error; no separate delivery-log route exists |
| Merchant one-time key/reveal/copy | /merchant/dashboard/api-credentials | ApiCredentialsPage | Keyboard reveal/copy, axe default/loading/empty/error/secret |
| Merchant webhooks | /merchant/dashboard/webhooks | WebhooksPage | Source/regression; existing unavailable placeholder; no registration or webhook-secret flow exists |
| Courier assignment list | /courier | TodayDeliveriesPage | Axe default/loading/empty/error; API returns all assignments, not a today window |
| Courier detail/proof/unavailable | /courier/deliveries/:deliveryId | DeliveryDetailPage, ProofOfDeliveryForm | Axe default/loading/error; keyboard proof validation/submission |
| Courier profile | /courier/profile | CourierProfilePage | Source/regression; profile/API behavior unchanged |
| Dispatcher overview/queue/assignment | /dispatcher, /dispatcher/queue | DispatchQueueCard, DispatchQueuePage, AssignmentDialog | Queue axe default/loading/empty/error/dialog; keyboard trap/Escape/confirm |
| Dispatcher workload | /dispatcher/workload | WorkloadPage | Source/regression; labelled search/availability controls already exist |
| Dispatcher zones/pricing/quote | /dispatcher/zones-pricing | ZoneList, PricingRuleForm, QuotePreview | Source/regression; existing confirmation focus pattern retained |
| Dispatcher user editing | /dispatcher/users | UsersPage, UserEditPanel | New focus lifecycle and keyboard simulation; existing regression |
| Dispatcher merchant approvals | /dispatcher/merchant-approvals | MerchantApprovalsPage | Source/regression; no decision/API changes |
| Dispatcher couriers / integration attempts | /dispatcher/couriers, /dispatcher/integration-attempts | CouriersPage, IntegrationAttemptsPage | Source/regression; no business changes |
| Dispatcher reports | /dispatcher/reports | ReportsPage | Existing placeholder; source inspection only |

## Contrast measurements

Ratios are computed from actual CSS tokens using WCAG sRGB luminance and alpha compositing,
without rounding before assertions. Translucent light cards are composited over both gradient
endpoints; dark cards are opaque. These are modelled token values, not sampled browser pixels.
The two panel tokens were introduced only to close a proven contrast failure; button inversion
tokens keep their existing meaning. No brand gold was changed.

| Token pair / surface | Light before → after | Dark before → after | Required |
|---|---:|---:|---:|
| body/bg1 | 13.693 → 13.693 | 15.176 → 15.176 | 4.5:1 |
| muted/bg1 | 4.174 → 4.629 | 6.993 → 6.993 | 4.5:1 |
| body/bg2 | 14.213 → 14.213 | 12.902 → 12.902 | 4.5:1 |
| muted/bg2 | 4.333 → 4.805 | 5.945 → 5.945 | 4.5:1 |
| body/chip | 16.483 → 16.483 | 10.686 → 10.686 | 4.5:1 |
| muted/chip | 5.025 → 5.572 | 4.924 → 4.924 | 4.5:1 |
| body/card-over-bg1 | 15.385 → 15.385 | 12.902 → 12.902 | 4.5:1 |
| muted/card-over-bg1 | 4.69 → 5.201 | 5.945 → 5.945 | 4.5:1 |
| body/card-over-bg2 | 15.586 → 15.586 | 12.902 → 12.902 | 4.5:1 |
| muted/card-over-bg2 | 4.751 → 5.269 | 5.945 → 5.945 | 4.5:1 |
| delivered-chip | 12.947 → 12.947 | 13.329 → 13.329 | 4.5:1 |
| accent-chip | 11.502 → 11.502 | 8.623 → 8.623 | 4.5:1 |
| failed-chip | 8.213 → 8.213 | 8.213 → 8.213 | 4.5:1 |
| panel-body | 12.947 → 12.947 | 1.141 → 16.393 | 4.5:1 |
| panel-muted | 2.818 → 5.94 | 7.521 → 7.521 | 4.5:1 |
| chat-placeholder | 4.155 → 7.149 | 4.155 → 7.149 | 4.5:1 |
| input-boundary/chip | 1.315 → 5.572 | 1.509 → 4.924 | 3:1 |
| focus-indicator/light-surface | 1.77 → 17.463 | — | 3:1 |
| focus-indicator/dark-surface | 9.556 → 17.928 | — | 3:1 |

The full numeric source of this table is [accessibility-contrast.json](accessibility-contrast.json);
`accessibilityContrast.test.ts` reads the current token files and fails below the documented
thresholds. Panel/body/gradient/chip and meaningful text/icon pairs use 4.5:1; focus and boundaries
use 3:1. Large text therefore also clears its 3:1 requirement for tested pairs. Placeholder text
on ordinary customer inputs now uses the tested muted/chip pair; the dark chat placeholder has
its own row. Browser-default placeholder color before the fix was not measured or invented.
Disabled inputs use reduced opacity and are exempt from WCAG text/non-text contrast thresholds;
their rendered appearance is NOT run. Decorative card/divider borders are not treated as input
boundaries. Other hardcoded, translucent, hover and inherited surfaces require rendered review.

## Patterns and visible changes

- Shared shell: native skip link, a main focus target, fixed route-specific document titles; language
  and viewport metadata. Five layouts use the helper; no guard or route definition changed.
- Field: programmatic label, native required state, retained external descriptions, error association
  and alert. Three production customer modules use Field (SendParcelPage, SupportPage, TrackView).
- Nav pills: links retain aria-current=page. Profile uses a disclosure button, Tab and Escape rather
  than a partially implemented ARIA menu.
- Chips/stepper/timeline: status text remains visible; filters use aria-pressed; booking is an ordered
  list with aria-current=step; current timeline item carries the same state. No custom tab widget
  requiring arrow keys was introduced.
- Dialogs: existing assignment/pricing lifecycle retained; shared focus behavior repairs user editing
  without changing save/close decisions. The generic styleguide Modal remains a follow-up.
- Toasts/empty states: polite status announcements; no focus theft. Close targets have a 24px minimum.
  Shared EmptyState is used in two production modules (public tracking and merchant application);
  the customer-specific component is used by History, Dashboard, Receipts and TrackView.
- Tables/chat: required History columns remain in the table at mobile widths; named table scroll
  regions and the chat log can receive keyboard focus. Native FAQ details/summary stays native.
- Secrets: instruction heading focus, static readable code, keyboard copy, separate fixed copy-status
  message. The secret is not put into a live region or document title.
- Visible changes: slightly darker neutral light muted text; readable dark-panel text; stronger input
  boundaries; two-tone focus and focused skip link; History mobile columns scroll instead of hiding;
  nav wrapping/scroll containment; a larger toast-dismiss target. Reduced motion and forced-color
  declarations were added, but their real rendering is NOT run.

## Automated evidence

Approved dev-only dependencies are pinned exactly: `@testing-library/user-event 14.6.1` (MIT)
for keyboard/pointer simulation and `axe-core 4.11.0` (MPL-2.0) for DOM audits.
No runtime dependency, wrapper or ESLint plugin was added. Lockfile changes add only these two
packages; `npm ci --no-audit --no-fund` actually succeeded (423 packages installed).

The small local `shared/testing/accessibility.ts` helper runs axe directly. **color-contrast is
disabled in every snapshot**, since jsdom does not render color/layout. The selected tags are
wcag2a, wcag2aa, wcag21a, wcag21aa, wcag22aa and best-practice. Zero axe violations do not imply
WCAG conformance. Document-level checks outside the supplied rendered container may be inapplicable.

| Page | States audited in each theme | Before violations (light / dark) | After (light / dark) |
|---|---|---|---|
| Customer Send Parcel | default | 0 / 0 | 0 / 0 |
| Customer History | default, loading, empty, error | 0,0,1,1 / 0,0,1,1 | 0,0,0,0 / 0,0,0,0 |
| Customer Support | default | 0 / 0 | 0 / 0 |
| Merchant dashboard | default, loading, empty, error | 0,0,0,0 / 0,0,0,0 | 0,0,0,0 / 0,0,0,0 |
| Merchant credentials | default, loading, empty, error, secret | 0,0,0,0,0 / 0,0,0,0,0 | 0,0,0,0,0 / 0,0,0,0,0 |
| Courier list | default, loading, empty, error | 0,0,0,0 / 0,0,0,0 | 0,0,0,0 / 0,0,0,0 |
| Courier detail | default, loading, error | 0,0,0 / 0,0,0 | 0,0,0 / 0,0,0 |
| Dispatcher queue | default, loading, empty, error, dialog | 0,0,0,0,0 / 0,0,0,0,0 | 0,0,0,0,0 / 0,0,0,0,0 |
| Public tracking search | default | 0 / 0 | 0 / 0 |
| Public tracking result | default, loading, empty, error | 0,0,0,0 / 0,0,0,0 | 0,0,0,0 / 0,0,0,0 |

64 before and 64 after snapshots; four heading-order violations before, zero after.
Per-page/state/theme disabled, incomplete and **inapplicable rule IDs** are recorded verbatim in
[before](accessibility-axe-before.json) and [after](accessibility-axe-after.json). These files include
the precise rule coverage rather than claiming every axe rule ran. Fixture data is synthetic;
business responses are mocked. Themes are explicitly selected and asserted in the DOM.
Pages not in the table were not axe-audited.

Keyboard simulation covers booking completion/invalid fields, History chips, chat Enter, proof
validation/submission, assignment focus/Escape/confirm, user edit focus/Escape, secret reveal/copy,
profile Escape, nav current state, simulated system/manual theme behavior, toast live/focus,
route focus and Field descriptions. Native select selection uses user-event selectOptions;
actual browser arrow-key behavior remains manual. user-event 14 does not model summary tabbing:
FAQ native structure/click behavior is covered; its keyboard pass is **NOT run**.

Actual final frontend output:
```text
npm test
 Test Files  52 passed (52)
      Tests  506 passed (506)
npm run lint
 6 problems (0 errors, 6 warnings)
npm run type-check
 tsc -b --pretty false [exit 0]
npm run build
 built in 3.82s
test=0 lint=0 type=0 build=0
```

The six warnings remain in pre-existing code: StatusChip (1), Toast (1),
MerchantApprovalsPage (2), TrackingPages useCallback (1), AuthProvider (1). New warnings: zero.
Vite retains its >500kB chunk warning. Initial regression failures were the old menuitem/dialog
name assertions and a palette-literal rule; semantic assertions and token reuse were corrected,
then the full suite passed. No existing tests were removed.

`npm audit --omit=dev` returned exit 1: 4 vulnerabilities (2 moderate, 2 high).
`npm audit` returned exit 1: 20 vulnerabilities (12 moderate, 5 high, 3 critical).
Neither newly approved package is listed in an advisory. The reports identify unchanged
follow-redirects, react-router/react-router-dom and zod at runtime, plus the existing Vitest/Vite,
Handlebars/boundaries and minimatch/tooling dependency chains. No audit fix was applied.
The **real complete output** is [accessibility-npm-audit.txt](accessibility-npm-audit.txt).
Installed package metadata confirmed the versions/licenses above. Final bundle-string and
production-dependency inspection is recorded in [accessibility-bundle-check.txt](accessibility-bundle-check.txt);
absence of the strings is a static check, supported by dev-only declarations and no production imports.

Actual environment:
```text
node -v (Docker): v22.23.4
npm -v (Docker): 10.9.9
uname -sr: Linux 7.0.0-42-generic
```
Host Node/npm were previously absent; Docker execution remained authorized. No browser tool,
Storybook or Playwright setup was available. axe/Lighthouse in a real browser: **NOT run**.
Real screen reader, devices, zoom/reflow rendering, forced colors and backend: **NOT run**.

## Reproduction commands

From the repository root, use the approved Docker runtime:
```sh
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm ci --no-audit --no-fund
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm test
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm run lint
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm run type-check
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm run build
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm audit --omit=dev
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm audit
```
Axe/contrast tests run with the normal suite. Optional `A11Y_REPORT_PATH` and
`CONTRAST_REPORT_PATH` environment variables write after reports. Historical before evidence
was captured before implementation with `A11Y_BASELINE=1`; that flag is not a passing-check mode
and is not used in normal verification. It must not replace assertions for an after run.

## MANUAL VERIFICATION CHECKLIST — all pending

Execution owner: @Deadsecnote1; independent reviewer: @kirusigan03.
Each row needs the real tester, date, environment and outcome. No name/date below claims execution.

| Check | Status | Who ran it | When | Browser/device and result/evidence |
|---|---|---|---|---|
| Keyboard-only pass for every inventory flow, including invalid/error/empty states | NOT run | — | — | — |
| FAQ Enter/Space, native select arrows, profile disclosure and dialog wrap/Escape/return | NOT run | — | — | — |
| 320, 375, 768, 1024 and 1280px: no page overflow; tables/code scroll locally | NOT run | — | — | — |
| 200% zoom and 400% reflow/320 CSS px; focus not clipped or covered | NOT run | — | — | — |
| History Route/Date reachable, nav usable, secret/proof/chat not clipped | NOT run | — | — | — |
| NVDA or VoiceOver: Send Parcel, Track, proof, dialogs and one-time secret | NOT run | — | — | — |
| Light/dark body, muted text, panels, chips, placeholders, links and focus in browser | NOT run | — | — | — |
| Windows high-contrast/forced-colors essential boundaries and focus | NOT run | — | — | — |
| Reduced-motion and OS/manual theme toggle, including navigation between portals | NOT run | — | — | — |
| Real mobile device, touch targets, safe areas, portrait/landscape and on-screen keyboard | NOT run | — | — | — |
| Receipt print order/readability | NOT run | — | — | — |
| Browser axe and Lighthouse accessibility, scores/violations captured per key page | NOT run | — | — | — |

## Prepared follow-ups

| Follow-up issue text | Owner / next action |
|---|---|
| Complete real browser/device/assistive-technology review of #164. Reproduce using the inventory and manual matrix; capture failures and actual Lighthouse/axe evidence. | @Deadsecnote1; schedule manual execution, with @kirusigan03 independent review. |
| Scope global customer theme variables before extending consistent dark themes across all portals. Customer CSS currently supplies root variables alongside the shared palette. Reproduce by navigating portals after changing the customer theme; compare actual cascades and contrast. A theme-system change requires its own review. | @pirathee587 (token/customer CSS git history); agree theme ownership and inspect real rendered surfaces before a separate design-system PR. |
| Complete the Styleguide-only generic Modal lifecycle before adopting it in a critical portal. It currently exposes aria-modal without a dismissal/focus contract. Reproduce on /styleguide; define close/trigger/focus behavior without changing business commands. | to be assigned (git author Kirusigan; maintainer assignment not verified); shared-component owner defines the component contract and tests. |
| Review existing dependency advisories separately. Runtime audit reports 4 and full audit 20; breaking router/zod/tool upgrades were not mixed into #164. | @pirathee587 (package maintenance history); triage actual advisory reachability and prepare independently validated upgrades. |
| Clarify the currently nonfunctional header notifications control. It has an accessible name but no implemented notification action. | @pirathee587 (customer layout history); agree unavailable-state wording or implementation in the owning task; no backend capability invented here. |

| Completion evidence | Value |
|---|---|
| Target standard | WCAG 2.2 AA; manual acceptance pending |
| API/routes/auth/contracts/backend | unchanged |
| Merged commit SHA | to be recorded after merge |
