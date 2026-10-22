#!/usr/bin/env python3
"""Exercise real local Keycloak with a disposable user; never print credentials/tokens."""

import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.error
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
BASE = "http://localhost:8080"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(path, data=None, token=None, method=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if isinstance(data, dict):
        data = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    elif data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        return urllib.request.build_opener(NoRedirect).open(req, timeout=15)
    except urllib.error.HTTPError as error:
        if path == "/realms/master/protocol/openid-connect/token":
            raise RuntimeError("Local admin login failed. Update auth/.env with the current console credentials; "
                               "changing bootstrap settings does not reset an existing admin password.") from None
        raise RuntimeError(f"Local Keycloak request failed: HTTP {error.code}") from None


def main():
    settings = {}
    env_file = ROOT / "auth/.env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                key, separator, value = line.partition("=")
                if separator:
                    settings[key.strip()] = value.strip().strip("\"'")
    settings.update(os.environ)
    username = settings.get("KC_BOOTSTRAP_ADMIN_USERNAME", "sylph-admin")
    password = settings.get("KC_BOOTSTRAP_ADMIN_PASSWORD")
    if not password:
        raise RuntimeError("Set local Keycloak admin credentials in auth/.env or the environment.")
    body = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli",
                                  "username": username, "password": password}).encode()
    with request("/realms/master/protocol/openid-connect/token", body) as response:
        token = json.load(response)["access_token"]
    test_username = "sylph-test-" + secrets.token_hex(8)
    test_password = secrets.token_urlsafe(32)
    user = {"username": test_username, "enabled": True, "firstName": "Sylph", "lastName": "Test",
            "email": test_username + "@example.test", "emailVerified": True,
            "credentials": [{"type": "password", "value": test_password, "temporary": False}]}
    user_path = None
    try:
        with request("/admin/realms/sylph/users", user, token) as response:
            location = urllib.parse.urlsplit(response.headers["Location"])
            user_path = location.path
            if not user_path.startswith("/admin/realms/sylph/users/"):
                raise RuntimeError("Unexpected disposable-user response.")
        env = dict(os.environ, SYLPH_AUTH_ISSUER=BASE + "/realms/sylph",
                   SYLPH_AUTH_CLIENT_ID="sylph-desktop", SYLPH_AUTH_CALLBACK_PORT="43117",
                   SYLPH_AUTH_TEST_USERNAME=test_username, SYLPH_AUTH_TEST_PASSWORD=test_password,
                   SYLPH_AUTH_TEST_NAME="Sylph Test")
        # init.sh keeps the project's existing JDK detection/provisioning behavior.
        result = subprocess.run(["bash", "-ec", 'source ./init.sh\nexec ./gradlew :app:authServerIntegrationTest'],
                                cwd=ROOT, env=env, check=False)
        if result.returncode:
            raise RuntimeError("Keycloak integration check failed; inspect the Gradle test report.")
        print("Real Keycloak login, verified identity, and browser logout passed.")
    finally:
        if user_path is not None:
            with request(user_path, token=token, method="DELETE"):
                pass
            print("Disposable test user removed.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, urllib.error.URLError, OSError) as error:
        raise SystemExit(str(error)) from None
