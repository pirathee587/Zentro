"""Summarize actual JaCoCo XML counters, retaining every package and zero-denominator gaps."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = ['libs/zentro-security-starter', 'api-gateway', 'identity-partner-service',
           'parcel-pricing-service', 'dispatch-courier-service', 'tracking-reporting-service',
           'notification-integration-service']


def counters(node):
    c = node.find("counter[@type='LINE']")
    return (int(c.get('covered')), int(c.get('missed'))) if c is not None else (0, 0)


def ratio(pair):
    covered, missed = pair
    return f'{covered}/{covered+missed} ({100*covered/(covered+missed):.2f}%)' if covered+missed else 'N/A (no measured lines)'


def layer(packages, segment):
    selected = [counters(p) for p in packages if segment in p.get('name').split('/')]
    return tuple(sum(v[i] for v in selected) for i in (0, 1))


def generate():
    lines = ['# Backend coverage evidence — Issue #351', '',
             'Actual JaCoCo LINE counters from local XML reports. Base `9275ea8ccb3170097757e332525125264769d21f` plus Issue #351 changes. '
             'Docker-dependent scenarios are skipped; S3 additionally has two Docker environment errors and its report is partial. '
             'S2 was rerun with jacoco.append=false because its initial exec file predated this task. '
             'These measurements do not establish full integration coverage or accepted exceptions. '
             'The documented target remains at least 70% for service and security layers (README.md). No enforcement gate is added here.', '',
             'Reproduce summary: `python docs/testing/build_issue351_coverage.py` after the commands in issue-351-implementation.md. '
             'Layer selection is explicit: package path segment `service` or `security`; packages such as config, reporting and grpc are retained separately below. '
             'The shared starter uses com/zentro/security including its configuration and observability packages, so inspect its package rows rather than interpreting the aggregate as JWT-only coverage. '
             'Generated protobuf classes are included in module totals; no exclusions were introduced.', '',
             '| Module | All lines | Service layer | Security layer | Report |', '|---|---|---|---|---|']
    package_rows = []
    for module in MODULES:
        file = ROOT / 'backend' / module / 'target/site/jacoco/jacoco.xml'
        if not file.exists():
            lines.append(f'| {module} | NOT MEASURED | NOT MEASURED | NOT MEASURED | {file.relative_to(ROOT)} absent |')
            continue
        doc = ET.parse(file).getroot()
        packages = doc.findall('package')
        lines.append(f'| {module} | {ratio(counters(doc))} | {ratio(layer(packages,"service"))} | {ratio(layer(packages,"security"))} | `backend/{module}/target/site/jacoco/index.html` |')
        for p in packages:
            covered, missed = counters(p)
            package_rows.append(f'| {module} | `{p.get("name")}` | {ratio((covered,missed))} | {missed} | ' +
                                ('Owner-authored failure/authorization/business boundary tests recommended' if missed else 'No uncovered lines in this run; branch completeness not implied') + ' |')
    lines += ['', '## Prioritized owner-authored test recommendations', '',
              '- S1 (#350): exercise missing/inactive identities and gRPC service error mapping; distinguish generated protobuf boilerplate from business-layer gaps without excluding it here.',
              '- S2 (#353): add meaningful pricing/status transition and idempotent-outbox failure cases for missed service lines; run PostgreSQL boundary tests before interpreting the fresh 82.81% service result as acceptance.',
              '- S3 (#354): rerun AssignmentPostgresIntegrationTest and AssignmentToCourierScenarioTest with Docker; verify lock/concurrency and actual producer payloads rather than treating mocked eligibility as provider acceptance.',
              '- S4 (#357/#358): run event projection/recount and pagination SQL cases; add missed service/exception branches and separately classify reporting packages for the gate.',
              '- S5 (#356): exercise provider outage/retry recovery and consumer/database failure paths with Kafka/PostgreSQL; retain the published retry/error contracts.',
              '- Shared/gateway (#351): investigate the uncovered configuration/guard paths and upstream sanitizer branches; current executed SSE tests use a stub upstream, so live token-expiry/reconnect remains separate.', '',
              '## Package evidence', '', '| Module | Package | Covered / total | Uncovered lines | Recommendation |',
              '|---|---|---|---|---|', *package_rows, '',
              '## Handoff requirements for #352 — Niro0526', '',
              'This is a local handoff document; no message or issue mutation was sent. Add consistent JaCoCo prepare-agent/report/check execution for all five services, gateway and starter; publish XML/HTML with candidate SHA and test/skipped counts. Define service/security layer package membership explicitly (including security-related config and reporting service packages), then enforce the existing 70% line target separately for each applicable layer. Preserve Surefire/Failsafe argLine instrumentation, merge unit/integration data intentionally, use a fresh per-candidate exec file, and fail when expected reports/counters are absent. Do not silently average a weak service with a strong one.', '',
              'Domain owners #350/#353/#354/#356/#357/#358 must supply meaningful missing business tests. New instrumentation/build gates belong to #352; POMs and workflows were not edited. Record an explicitly approved exception with owner/rationale if a layer cannot meet target; no such exception is claimed here. A passing verify is not proof of 70% enforcement.', '']
    return '\n'.join(lines)


if __name__ == '__main__':
    (ROOT / 'docs/testing/backend-coverage-matrix.md').write_text(generate(), encoding='utf-8')
    print('Wrote docs/testing/backend-coverage-matrix.md')
