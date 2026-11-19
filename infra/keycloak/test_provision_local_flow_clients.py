import contextlib
import io
import unittest
from unittest.mock import patch
import provision_local_flow_clients as local

class LocalProvisioningTests(unittest.TestCase):
    def invoke(self, args):
        with patch('sys.argv', ['provision_local_flow_clients.py', *args]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            local.main()

    def test_plan_has_no_mutation(self):
        with patch.object(local.provisioning, 'main') as apply:
            self.invoke([])
            apply.assert_not_called()

    def test_remote_target_is_rejected(self):
        with patch.object(local.provisioning, 'BASE', 'https://example.com'), patch.object(local.provisioning, 'main') as apply:
            with self.assertRaises(SystemExit): self.invoke(['--apply'])
            apply.assert_not_called()

    def test_missing_secret_prevents_any_mutation(self):
        with patch.dict(local.provisioning.os.environ, {}, clear=True), patch.object(local.provisioning, 'main') as apply:
            with self.assertRaises(SystemExit): self.invoke(['--apply'])
            apply.assert_not_called()

    def test_apply_scopes_manifest_to_two_clients(self):
        original = local.provisioning.MANIFEST
        secrets = {s['secretEnv']: 'synthetic-test-only-' * 3 for s in original['clients']}
        try:
            with patch.dict(local.provisioning.os.environ, secrets), patch.object(local.provisioning, 'ADMIN_PASSWORD', 'synthetic-test-only'), patch.object(local.provisioning, 'main') as apply:
                self.invoke(['--apply'])
                apply.assert_called_once()
                self.assertEqual({s['clientId'] for s in local.provisioning.MANIFEST['clients']}, local.CLIENTS)
        finally:
            local.provisioning.MANIFEST = original

if __name__ == '__main__': unittest.main()
