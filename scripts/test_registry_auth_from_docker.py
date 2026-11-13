"""Tests for exporting a single registry login without exposing it in command output."""
import base64
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('registry_auth_from_docker.py')


class DockerAuthExportTest(unittest.TestCase):
    def test_inline_docker_hub_login_is_normalized_and_private(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            secret = base64.b64encode(b'fake-user:fake-secret').decode()
            (root/'config.json').write_text(json.dumps({'auths': {'https://index.docker.io/v1/': {'auth': secret}}}))
            output = root/'out.json'
            env = dict(os.environ, DOCKER_CONFIG=tmp)
            run = subprocess.run([sys.executable, str(SCRIPT), '--registry', 'registry-1.docker.io', '--out', str(output)],
                                 env=env, text=True, capture_output=True, check=True)
            self.assertNotIn(secret, run.stdout + run.stderr)
            self.assertEqual(json.loads(output.read_text())['auths']['registry-1.docker.io']['auth'], secret)
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
            second = subprocess.run([sys.executable, str(SCRIPT), '--registry', 'registry-1.docker.io', '--out', str(output)],
                                    env=env, text=True, capture_output=True)
            self.assertNotEqual(second.returncode, 0)

    def test_helper_login_is_exported_only_for_requested_registry(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root/'config.json').write_text(json.dumps({'credsStore': 'fake'}))
            helper = root/'docker-credential-fake'
            helper.write_text('#!/bin/sh\nread host\n[ "$host" = "ghcr.io" ] || exit 1\nprintf \'%s\\n\' \'{"Username":"fake-user","Secret":"fake-secret"}\'\n')
            helper.chmod(0o700)
            output = root/'out.json'
            env = dict(os.environ, DOCKER_CONFIG=tmp, PATH=tmp + os.pathsep + os.environ.get('PATH', ''))
            run = subprocess.run([sys.executable, str(SCRIPT), '--registry', 'ghcr.io', '--out', str(output)],
                                 env=env, text=True, capture_output=True, check=True)
            self.assertNotIn('fake-secret', run.stdout + run.stderr)
            config = json.loads(output.read_text())['auths']
            self.assertEqual(list(config), ['ghcr.io'])
            self.assertEqual(base64.b64decode(config['ghcr.io']['auth']), b'fake-user:fake-secret')


if __name__ == '__main__':
    unittest.main()
