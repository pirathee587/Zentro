import unittest
from pathlib import Path

ROOT = Path(__file__).parent
PS = (ROOT / "seed-users.ps1").read_text(encoding="utf-8")
SH = (ROOT / "seed-users.sh").read_text(encoding="utf-8")


class SeedWorkflowHardeningTests(unittest.TestCase):
    def test_tracked_seed_scripts_have_no_fixed_test_passwords(self):
        self.assertNotRegex(PS, r"(?im)^\s*@\{.*\bPassword\s*=\s*['\"]")
        self.assertNotIn("ensure_user ", SH)

    def test_private_credential_file_and_acl_are_required(self):
        self.assertIn("ZentroSecrets", PS)
        self.assertIn("seed-users.env", PS)
        self.assertIn("keycloak-admin.env", PS)
        self.assertIn("Assert-RestrictedAcl $privateDir", PS)
        self.assertIn("Assert-RestrictedAcl $credentialPath", PS)
        self.assertIn("outside OneDrive", PS)

    def test_existing_accounts_are_not_password_reset_or_updated(self):
        self.assertIn("exists; left unchanged", PS)
        self.assertNotIn("reset-password", PS)
        self.assertNotIn("seed-credentials.local.txt", PS)

    def test_scripts_do_not_print_credentials_or_tokens(self):
        self.assertNotIn("Format-Table", PS)
        self.assertNotIn("Write-Output $password", PS)
        self.assertNotIn("cat \"$credentials_file\"", SH)
        self.assertNotIn("curl", SH)

    def test_seed_script_is_local_realm_only(self):
        self.assertIn("localhost", PS)
        self.assertIn("8180", PS)
        self.assertIn("$realm -ne 'zentro'", PS)


if __name__ == "__main__":
    unittest.main()
