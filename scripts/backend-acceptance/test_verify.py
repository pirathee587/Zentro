"""Evidence-integrity regressions: stale and missing results cannot satisfy acceptance."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import os
import subprocess
import verify


class EvidenceIntegrityTest(unittest.TestCase):
    def execute(self, report_names, maven_exit=0, stale=False, skipped=0):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            output = repo / 'evidence.json'
            def reports(*args, **kwargs):
                for name in report_names:
                    path = repo / 'backend/module/target/surefire-reports' / ('TEST-' + name + '.xml')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('<testsuite name="com.zentro.e2e.' + name + '" tests="1" failures="0" errors="0" skipped="' + str(skipped) + '"><properties><property name="secret" value="must-not-export"/></properties><system-out>must-not-export</system-out></testsuite>')
                    if stale:
                        os.utime(path, (1, 1))
                return subprocess.CompletedProcess([], maven_exit)
            with patch.object(verify, '__file__', str(repo / 'scripts/backend-acceptance/verify.py')), \
                 patch.object(verify.subprocess, 'check_output', side_effect=['a' * 40 + '\n', '']), \
                 patch.object(verify.subprocess, 'run', side_effect=reports), \
                 patch('sys.argv', ['verify.py', '--output', str(output)]):
                code = verify.main()
            text = output.read_text()
            self.assertNotIn('must-not-export', text)
            return code, json.loads(text)

    def test_green_maven_without_domain_scenarios_is_blocked(self):
        code, evidence = self.execute(['BackendRoleAcceptanceIT'])
        self.assertEqual(code, 2)
        self.assertEqual(evidence['required_suites_missing'], ['CourierLifecycleIT', 'DispatcherAssignmentIT'])

    def test_old_reports_never_satisfy_current_run(self):
        code, evidence = self.execute(['BackendRoleAcceptanceIT', 'CourierLifecycleIT', 'DispatcherAssignmentIT'], stale=True)
        self.assertEqual(code, 2)
        self.assertEqual(evidence['suites'], [])

    def test_maven_failure_is_preserved(self):
        code, evidence = self.execute([], maven_exit=7)
        self.assertEqual(code, 7)
        self.assertEqual(evidence['exit_code'], 7)

    def test_skipped_required_scenarios_are_not_success(self):
        code, evidence = self.execute(['BackendRoleAcceptanceIT', 'CourierLifecycleIT', 'DispatcherAssignmentIT'], skipped=1)
        self.assertEqual(code, 2)
        self.assertEqual(evidence['automated_gate_status'], 'BLOCKED')

    def test_automated_success_does_not_grant_release_approval(self):
        code, evidence = self.execute(['BackendRoleAcceptanceIT', 'CourierLifecycleIT', 'DispatcherAssignmentIT'])
        self.assertEqual(code, 0)
        self.assertEqual(evidence['automated_gate_status'], 'DONE')
        self.assertEqual(evidence['release_status'], 'BLOCKED')
        self.assertEqual(evidence['independent_review'], 'UNVERIFIED')


if __name__ == '__main__':
    unittest.main()
