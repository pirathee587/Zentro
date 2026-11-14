#!/usr/bin/env python3
"""Run backend acceptance and export counts only; raw logs stay outside the repository."""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import tempfile
import time
import re
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    dirty = subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True)
    runtime_command = ['git', 'status', '--porcelain', '--', 'backend', 'contracts', 'scripts',
                       'infra', 'docs/api', '.github', 'docker-compose.yml', '.env.example', '.env.common.example']
    runtime_before = subprocess.check_output(runtime_command, cwd=repo, text=True)
    command = ['mvn', '--batch-mode', '--no-transfer-progress', '-Dbuild.revision=' + sha, '-DrunE2E', 'clean', 'verify']
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    started_epoch = time.time()
    with tempfile.NamedTemporaryFile(prefix='zentro-359-', suffix='.log', delete=False) as log:
        print('Raw local log:', log.name, flush=True)
        result = subprocess.run(command, cwd=repo / 'backend', stdout=log, stderr=subprocess.STDOUT)
    ending_sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    runtime_after = subprocess.check_output(runtime_command, cwd=repo, text=True)
    input_drift = ending_sha != sha or bool(runtime_before) or bool(runtime_after)
    suites = []
    for path in sorted((repo / 'backend').glob('**/target/surefire-reports/TEST-*.xml')):
        if path.stat().st_mtime < started_epoch:
            continue  # An early reactor failure must never recycle an older module's results.
        suite = ET.parse(path).getroot()
        # Do not export XML properties, testcase parameters, bodies or captured output.
        cases = []
        for index, case in enumerate(suite.findall('testcase'), start=1):
            # Export Java method identifiers only, never parameter values or assertion messages.
            method = re.fullmatch(r'([A-Za-z_$][A-Za-z0-9_$]*)(?:\(.*\)(?:\[\d+\])?)?', case.get('name', ''))
            status = next((kind for kind in ('error', 'failure', 'skipped') if case.find(kind) is not None), 'passed')
            cases.append({'index': index, 'method': method[1] if method else 'see-local-report', 'outcome': status})
        suites.append({'report': str(path.relative_to(repo)), 'suite': suite.attrib['name'], 'cases': cases,
                       **{key: int(suite.attrib.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}})
    required = ('BackendRoleAcceptanceIT', 'CustomerBookingIT', 'CourierLifecycleIT', 'DispatcherAssignmentIT')
    missing = [name for name in required if not any(row['suite'].endswith('.' + name) and row['tests'] > 0 for row in suites)]
    incomplete = any(row['failures'] or row['errors'] or row['skipped'] for row in suites)
    gate_exit = result.returncode or (2 if missing or incomplete or input_drift else 0)
    evidence = {'sha': sha, 'working_tree_dirty': bool(dirty), 'started_utc': started,
                'finished_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'command': command, 'exit_code': result.returncode, 'acceptance_exit_code': gate_exit,
                'ending_sha': ending_sha, 'runtime_inputs_dirty': bool(runtime_before) or bool(runtime_after),
                'input_integrity_status': 'BLOCKED' if input_drift else 'DONE',
                'required_suites_missing': missing, 'suites': suites,
                'automated_gate_status': 'BLOCKED' if gate_exit else 'DONE',
                'release_status': 'BLOCKED', 'independent_review': 'UNVERIFIED'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print('Evidence:', args.output, 'Maven exit:', result.returncode, 'Acceptance exit:', gate_exit, flush=True)
    return gate_exit


if __name__ == '__main__':
    raise SystemExit(main())
