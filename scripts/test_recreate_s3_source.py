import unittest
from pathlib import Path

ROOT = Path(__file__).parent
RECREATE = (ROOT / "recreate_s3.ps1").read_text(encoding="utf-8")
SAFETY = (ROOT / "S3RecreateSafety.psm1").read_text(encoding="utf-8")


class S3RecreationSourceGuards(unittest.TestCase):
    def test_independent_snapshot_gate_precedes_first_container_mutation(self):
        verify = RECREATE.index("--candidate $Name")
        stop = RECREATE.index("'stop', '--time'")
        self.assertLess(verify, stop)
        self.assertIn("Independent original S3 snapshot verification failed; cutover refused.", RECREATE)

    def test_script_does_not_start_candidate_or_delete_original(self):
        lower = RECREATE.lower()
        self.assertIn("[switch]$confirmcutover", lower)
        self.assertIn("if (-not $confirmcutover)", lower)
        self.assertNotIn("compose", lower)
        self.assertNotIn("'start'", lower)
        self.assertNotIn("'rm'", lower)
        self.assertNotIn("'rmi'", lower)
        self.assertIn("rename', $Name, $BackupName", RECREATE)

    def test_temp_file_uses_explicit_utf8_without_bom_and_verified_acl(self):
        self.assertIn("[Text.UTF8Encoding]::new($false)", SAFETY)
        self.assertIn("$LASTEXITCODE -ne 0", SAFETY)
        self.assertGreaterEqual(SAFETY.count("$AclVerifier $Path"), 2)
        self.assertIn("Refusing stale temporary S3 env file.", SAFETY)
        self.assertIn("finally {", SAFETY)

    def test_private_secret_is_never_emitted_and_missing_value_fails_closed(self):
        self.assertIn("Test-S3RestrictedAcl -Path $path", SAFETY)
        self.assertIn("outside OneDrive", SAFETY)
        self.assertIn("Required private setting is missing", SAFETY)
        self.assertNotIn("Write-Output $secret", SAFETY)
        self.assertNotIn("Write-Host $secret", SAFETY)

    def test_missing_private_secret_gate_precedes_temp_file_and_cutover(self):
        secret = RECREATE.index("Get-S3DispatchSecret")
        temp = RECREATE.index("Invoke-S3WithTemporaryEnvFile")
        cutover = RECREATE.index("'stop', '--time'")
        self.assertLess(secret, temp)
        self.assertLess(temp, cutover)
        self.assertIn("Refusing stale temporary S3 env file.", RECREATE)


if __name__ == "__main__":
    unittest.main()
