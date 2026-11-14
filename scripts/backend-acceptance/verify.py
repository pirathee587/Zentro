#!/usr/bin/env python3
"""Run backend acceptance and export counts only; raw logs stay outside the repository."""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    dirty = subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True)
    command = ['mvn', '--batch-mode', '--no-transfer-progress', '-Dbuild.revision=' + sha, '-DrunE2E', 'clean', 'verify']
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    started_epoch = time.time()
    with tempfile.NamedTemporaryFile(prefix='zentro-359-', suffix='.log', delete=False) as log:
        print('Raw local log:', log.name, flush=True)
        result = subprocess.run(command, cwd=repo / 'backend', stdout=log, stderr=subprocess.STDOUT)
    suites = []
    for path in sorted((repo / 'backend').glob('**/target/surefire-reports/TEST-*.xml')):
        if path.stat().st_mtime < started_epoch:
            continue  # An early reactor failure must never recycle an older module's results.
        suite = ET.parse(path).getroot()
        # Do not export XML properties, testcase parameters, bodies or captured output.
        suites.append({'report': str(path.relative_to(repo)), 'suite': suite.attrib['name'],
                       **{key: int(suite.attrib.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}})
    evidence = {'sha': sha, 'working_tree_dirty': bool(dirty), 'started_utc': started,
                'finished_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'command': command, 'exit_code': result.returncode, 'suites': suites,
                'release_status': 'BLOCKED', 'independent_review': 'UNVERIFIED'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print('Evidence:', args.output, 'Maven exit:', result.returncode, flush=True)
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
