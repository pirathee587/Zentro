"""Reproduce the source/spec evidence matrix; candidates are not coverage claims."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/testing/backend-contract-matrix.md'
SERVICES = {'identity': 'identity-partner-service', 'parcel': 'parcel-pricing-service',
            'dispatch': 'dispatch-courier-service', 'tracking': 'tracking-reporting-service',
            'notifications': 'notification-integration-service'}


def cell(value):
    return str(value).replace('|', '\\|').replace('\n', ' ')


def generate():
    rows = ['# Backend operation contract evidence — Issue #351', '',
            'Base: `9275ea8ccb3170097757e332525125264769d21f`. Generated with '
            '`python docs/testing/build_issue351_inventory.py`. Status is SOURCE INSPECTION; '
            'a candidate suite is not proof that every response branch has an executable assertion. '
            'See issue-351-implementation.md for executed commands and results. '
            'Framework management/error endpoints are excluded from the business-operation count.', '',
            '| Service | Method / operation | Authentication / roles / ownership evidence | Success | Validation | Authorization | Missing resource | Candidate tests | Status / missing evidence |',
            '|---|---|---|---|---|---|---|---|---|']
    counts = {}
    for short, service in SERVICES.items():
        spec_path = ROOT / f'docs/api/openapi/{short}.openapi.json'
        spec = json.loads(spec_path.read_text(encoding='utf-8'))
        tests = list((ROOT / f'backend/{service}/src/test/java').rglob('*.java'))
        controllers = list((ROOT / f'backend/{service}/src/main/java').rglob('*Controller.java'))
        counts[short] = 0
        for path, methods in spec['paths'].items():
            for method, op in methods.items():
                if method not in {'get', 'post', 'put', 'patch', 'delete', 'options', 'head'}:
                    continue
                counts[short] += 1
                candidates = []
                policy = op.get('description', '')
                for source in controllers:
                    text = source.read_text(encoding='utf-8')
                    base = re.search(r'@RequestMapping\(([^)]*)\)', text)
                    if not base:
                        continue
                    prefixes = re.findall(r'"(/[^"\n]*)"', base[1])
                    for mapping in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\s*\(([^)]*)\))?', text):
                        suffixes = re.findall(r'"(/[^"\n]*)"', mapping[2] or '') or ['']
                        if mapping[1].lower() == method and path in [p+s for p in prefixes for s in suffixes]:
                            candidates += [t for t in tests if source.stem in t.stem]
                            annotations = re.findall(r'@PreAuthorize\("([^"\n]*)"\)', text)
                            policy += ' Source: ' + str(source.relative_to(ROOT)) + '; ' + '; '.join(dict.fromkeys(annotations))
                # Exact literal path references supply additional candidates, still requiring manual branch review.
                candidates += [t for t in tests if '"'+path+'"' in t.read_text(encoding='utf-8')]
                links = ', '.join(f'[{p.stem}](../../{p.relative_to(ROOT).as_posix()})' for p in sorted(set(candidates))) or 'No directly matched suite'
                responses = op.get('responses', {})
                statuses = lambda codes: ', '.join(c for c in responses if c in codes) or 'Not specified'
                auth = 'Bearer' if op.get('security', spec.get('security')) else 'Public per spec; inspect service rules'
                rows.append('| ' + ' | '.join(map(cell, [short, f'{method.upper()} `{path}` ({op.get("operationId", "")})',
                    auth + '; ' + policy, statuses({'200','201','202','204'}), statuses({'400','409','422'}),
                    statuses({'401','403'}), statuses({'404'}), links,
                    'PARTIAL: candidate assertion mapping; full success/error/ownership branch audit and live provider evidence pending'])) + ' |')
    rows += ['', 'Current REST count: **' + str(sum(counts.values())) + '** (' + ', '.join(f'{k}={v}' for k,v in counts.items()) + '). Historical PR #344 count of 57 is not the current count.', '',
             'Responses above are documented status contracts, not newly verified runtime results. A missing 404 declaration is not authority to change service semantics. Ownership and role decisions remain in service controllers/security and docs/security/permission-matrix.md.', '',
             '## GraphQL', '', '| Owner | Query | Authentication / ownership | Existing suite | Gap |', '|---|---|---|---|---|']
    for name, rule in [('publicTracking','Public projection; no PII'), ('delivery','Authenticated owner/assigned courier or DISPATCHER'),
                       ('myDeliveries','Authenticated subject scope'), ('deliveries','DISPATCHER')]:
        rows.append(f'| S4 | `{name}` | {rule} | TrackingGraphQlSecurityTest, TrackingGraphQlReadOnlyTest, TrackingGraphQlQueryLimitsTest, TrackingGraphQlBatchingTest | Live gateway/provider evidence pending; GraphQL error semantics are not REST status semantics |')
    rows += ['', 'Schema: `backend/tracking-reporting-service/src/main/resources/graphql/schema.graphqls`; four read-only queries, no mutation/subscription. gRPC and event operations are enumerated in [the cross-service inventory](../architecture/cross-service-contract-inventory.md).', '']
    return '\n'.join(rows)


if __name__ == '__main__':
    OUT.write_text(generate(), encoding='utf-8')
    print('Wrote', OUT.relative_to(ROOT))
