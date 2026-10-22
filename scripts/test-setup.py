#!/usr/bin/env python3
"""Check setup with fake tools; never start Docker, install Java, or touch real credentials."""

import json
import os
from pathlib import Path
import shlex
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="sylph-setup-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.project = self.directory / "project with spaces"
        (self.project / "scripts").mkdir(parents=True)
        (self.project / "auth").mkdir()
        shutil.copy2(ROOT / "init.sh", self.project / "init.sh")
        shutil.copy2(ROOT / "scripts/setup-auth.sh", self.project / "scripts/setup-auth.sh")
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        for name in ("bash", "dirname", "od", "tr", "chmod", "grep"):
            system_tool = Path("/usr/bin") / name
            (self.bin / name).symlink_to(system_tool if system_tool.exists() else shutil.which(name))
        self.jdk = self.directory / "jdk"
        (self.jdk / "bin").mkdir(parents=True)
        self.write_tool(self.jdk / "bin/java", '''
if [[ "${FAKE_JDK_READY:-1}" == 0 && ! -f "$FAKE_JDK_INSTALLED" ]]; then exit 1; fi
if [[ "$1" == "-XshowSettings:properties" ]]; then
  echo "    java.home = $FAKE_JDK" >&2
else
  echo 'openjdk version "21.0.1"' >&2
fi
''')
        self.write_tool(self.jdk / "bin/javac", "echo 'javac 21.0.1'")
        self.log = self.directory / "docker.log"
        self.write_tool(self.bin / "docker", '''
printf '%q ' "$@" >> "$FAKE_DOCKER_LOG"
printf '\\n' >> "$FAKE_DOCKER_LOG"
if [[ "$1 ${2:-}" == 'compose version' ]]; then exit "${FAKE_COMPOSE_STATUS:-0}"; fi
if [[ "$1 ${2:-} ${3:-}" == 'compose up --help' ]]; then
  if [[ "${FAKE_OLD_COMPOSE:-0}" == 0 ]]; then echo '--wait --wait-timeout'; fi
  exit 0
fi
if [[ "$1" == info ]]; then exit "${FAKE_DOCKER_STATUS:-0}"; fi
if [[ "$1" == compose ]]; then exit "${FAKE_UP_STATUS:-0}"; fi
exit 2
''')
        self.env = dict(os.environ, PATH=str(self.bin), JAVA_HOME=str(self.jdk),
                        PROTO_HOME=str(self.directory / "proto"), FAKE_JDK=str(self.jdk),
                        FAKE_JDK_INSTALLED=str(self.directory / "jdk-installed"),
                        FAKE_DOCKER_LOG=str(self.log))
        for key in ("FAKE_JDK_READY", "FAKE_COMPOSE_STATUS", "FAKE_DOCKER_STATUS", "FAKE_UP_STATUS", "FAKE_OLD_COMPOSE"):
            self.env.pop(key, None)
        self.credentials = self.project / "auth/.env"

    @staticmethod
    def write_tool(path, body):
        path.write_text("#!/usr/bin/env bash\nset -eu\n" + body + "\n")
        path.chmod(0o755)

    def run_init(self, *arguments, sourced=False):
        if sourced:
            command = [str(self.bin / "bash"), "-ec", 'source "$1"', "setup-test", str(self.project / "init.sh")]
        else:
            command = [str(self.project / "init.sh"), *arguments]
        return subprocess.run(command, cwd=self.directory, env=self.env, capture_output=True, text=True, timeout=10)

    def test_direct_setup_creates_private_credentials_and_waits_for_keycloak(self):
        result = self.run_init()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("JDK 21 is already installed", result.stdout)
        self.assertIn("Setup completed successfully", result.stdout)
        self.assertIn("--wait --wait-timeout 180 keycloak", self.log.read_text())
        self.assertIn(str(self.credentials), shlex.split(self.log.read_text().splitlines()[-1]))
        contents = self.credentials.read_text()
        self.assertIn("KC_BOOTSTRAP_ADMIN_USERNAME=sylph-admin\n", contents)
        password = next(line.split("=", 1)[1] for line in contents.splitlines()
                        if line.startswith("KC_BOOTSTRAP_ADMIN_PASSWORD="))
        self.assertRegex(password, r"^[0-9a-f]{64}$")
        self.assertNotIn(password, result.stdout + result.stderr)
        self.assertEqual(0o600, stat.S_IMODE(self.credentials.stat().st_mode))

    def test_env_template_gets_generated_admin_password_without_changing_oauth_values(self):
        self.credentials.write_text("GOOGLE_CLIENT_ID=fixture-client\nGOOGLE_CLIENT_SECRET='fixture-$secret'\n")
        original = self.credentials.read_text()
        result = self.run_init()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.credentials.read_text().startswith(original))
        self.assertIn("KC_BOOTSTRAP_ADMIN_PASSWORD=", self.credentials.read_text())
        self.assertNotIn("fixture-$secret", result.stdout + result.stderr)

    def test_repeat_setup_preserves_existing_credentials(self):
        self.credentials.write_text("KC_BOOTSTRAP_ADMIN_PASSWORD=existing-test-credential\n")
        original = self.credentials.read_bytes()
        for _ in range(2):
            result = self.run_init()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(original, self.credentials.read_bytes())
            self.assertNotIn("existing-test-credential", result.stdout + result.stderr)

    def test_sourced_setup_is_quiet_and_never_starts_docker(self):
        result = self.run_init(sourced=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout + result.stderr)
        self.assertFalse(self.log.exists())
        self.assertFalse(self.credentials.exists())

    def test_java_only_skips_keycloak_even_without_docker(self):
        (self.bin / "docker").unlink()
        result = self.run_init("--java-only")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Keycloak setup skipped", result.stdout)
        self.assertFalse(self.credentials.exists())

    def test_missing_docker_and_compose_fail_with_instructions(self):
        for missing in ("compose", "docker"):
            with self.subTest(missing=missing):
                if missing == "compose":
                    self.env["FAKE_COMPOSE_STATUS"] = "1"
                else:
                    (self.bin / "docker").unlink()
                result = self.run_init()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("requires Docker", result.stderr)
                self.assertIn("--java-only", result.stderr)
                self.assertNotIn("Setup completed successfully", result.stdout)
                self.assertFalse(self.credentials.exists())

    def test_unavailable_daemon_and_failed_start_are_not_reported_as_success(self):
        for setting in ("FAKE_DOCKER_STATUS", "FAKE_UP_STATUS"):
            with self.subTest(setting=setting):
                self.env[setting] = "1"
                result = self.run_init()
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("Setup completed successfully", result.stdout)
                self.env.pop(setting)

    def test_outdated_compose_fails_before_creating_credentials(self):
        self.env["FAKE_OLD_COMPOSE"] = "1"
        result = self.run_init()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Update Docker Compose", result.stderr)
        self.assertFalse(self.credentials.exists())

    def test_existing_credential_symlink_is_rejected(self):
        target = self.directory / "untouched"
        target.write_text("unchanged")
        self.credentials.symlink_to(target)
        result = self.run_init()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("regular auth/.env", result.stderr)
        self.assertEqual("unchanged", target.read_text())

    def test_proto_java_provisioning_still_runs_before_keycloak(self):
        self.env.update(JAVA_HOME="", FAKE_JDK_READY="0")
        (self.bin / "java").symlink_to(self.jdk / "bin/java")
        self.write_tool(self.bin / "proto", ': > "$FAKE_JDK_INSTALLED"')
        result = self.run_init()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("JDK 21 setup completed successfully", result.stdout)
        self.assertTrue(self.log.exists())

    def test_missing_java_returns_failure_when_sourced(self):
        self.env["JAVA_HOME"] = ""
        result = self.run_init(sourced=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No working JDK 21 or proto found", result.stderr)
        self.assertFalse(self.log.exists())

    def test_unknown_option_does_not_start_setup(self):
        result = self.run_init("--unknown")
        self.assertEqual(2, result.returncode)
        self.assertIn("Usage:", result.stderr)
        self.assertFalse(self.log.exists())


class ProviderEnvironmentTest(unittest.TestCase):
    def run_provider(self, provider, values):
        env = dict(os.environ)
        for key in ("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "GITHUB_CLIENT_ID", "GITHUB_CLIENT_SECRET"):
            env.pop(key, None)
        env.update(values)
        return subprocess.run(["bash", "-ec", 'source "$1"; sylph_configure_provider "$2"; '
                               'name="SYLPH_${2}_ENABLED"; printf "%s" "${!name}"',
                               "provider-test", str(ROOT / "auth/start-keycloak.sh"), provider],
                              env=env, text=True, capture_output=True, timeout=5)

    def test_empty_pairs_disable_providers(self):
        for provider in ("GOOGLE", "GITHUB"):
            with self.subTest(provider=provider):
                result = self.run_provider(provider, {})
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("false", result.stdout)

    def test_complete_pairs_enable_without_printing_secrets(self):
        for provider in ("GOOGLE", "GITHUB"):
            with self.subTest(provider=provider):
                result = self.run_provider(provider, {provider + "_CLIENT_ID": "fixture-client",
                                                     provider + "_CLIENT_SECRET": 'fixture-$"\\secret'})
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("true", result.stdout)
                self.assertEqual("", result.stderr)

    def test_partial_pairs_fail_without_printing_values(self):
        for provider in ("GOOGLE", "GITHUB"):
            for field in ("CLIENT_ID", "CLIENT_SECRET"):
                with self.subTest(provider=provider, field=field):
                    result = self.run_provider(provider, {provider + "_" + field: "do-not-print-this"})
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("Set both", result.stderr)
                    self.assertNotIn("do-not-print-this", result.stdout + result.stderr)

    def test_json_values_preserve_quotes_backslashes_and_literal_dollar_signs(self):
        value = 'fixture-${LITERAL}"quote\\path'
        env = dict(os.environ, GOOGLE_CLIENT_ID=value, GOOGLE_CLIENT_SECRET=value)
        script = '''source "$1"
sylph_configure_provider GOOGLE
printf '{"id":"%s","secret":"%s"}' "$SYLPH_GOOGLE_CLIENT_ID_JSON" "$SYLPH_GOOGLE_CLIENT_SECRET_JSON"
'''
        result = subprocess.run(["bash", "-ec", script, "provider-test", str(ROOT / "auth/start-keycloak.sh")],
                                env=env, text=True, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"id": value, "secret": value}, json.loads(result.stdout))
        self.assertNotIn("${", result.stdout)

    def test_control_characters_fail_without_printing_values(self):
        for value in ("fixture\nsecret", "fixture\tsecret", "fixture\x7fsecret"):
            with self.subTest(control=repr(value)):
                result = self.run_provider("GOOGLE", {"GOOGLE_CLIENT_ID": "fixture-client",
                                                      "GOOGLE_CLIENT_SECRET": value})
                self.assertNotEqual(0, result.returncode)
                self.assertIn("control characters", result.stderr)
                self.assertNotIn("fixture", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
