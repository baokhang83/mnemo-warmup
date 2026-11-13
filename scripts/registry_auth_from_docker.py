#!/usr/bin/env python3
"""Write one registry's Docker login to a new mode-0600 file for the benchmark.

Docker credential helpers are not readable by the in-cluster fetcher. This tool
exports only the requested login into a temporary Docker-config JSON file.
"""
import argparse
import base64
import json
import os
import pathlib
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--registry', required=True, choices=['ghcr.io', 'registry-1.docker.io'])
    parser.add_argument('--out', required=True, help='new file outside the repository; remove after the run')
    args = parser.parse_args()
    config_dir = pathlib.Path(os.environ.get('DOCKER_CONFIG', pathlib.Path.home()/'.docker'))
    config = json.loads((config_dir/'config.json').read_text())
    aliases = ([args.registry, 'https://index.docker.io/v1/', 'index.docker.io', 'docker.io']
               if args.registry == 'registry-1.docker.io' else [args.registry, 'https://ghcr.io'])
    auths = config.get('auths', {})
    helpers = config.get('credHelpers', {})
    entry = next((auths[k] for k in aliases if isinstance(auths.get(k), dict) and auths[k].get('auth')), None)
    if entry is None:
        helper = next((helpers[k] for k in aliases if k in helpers), config.get('credsStore'))
        if not helper:
            raise SystemExit('No matching inline credentials or Docker credential helper. Run docker login first.')
        login = None
        for alias in aliases:
            result = subprocess.run(['docker-credential-' + helper, 'get'], input=alias+'\n',
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            if result.returncode == 0:
                login = json.loads(result.stdout)
                break
        if not login or not login.get('Username') or not login.get('Secret'):
            raise SystemExit('No matching Docker login. Run docker login for the requested registry.')
        encoded = base64.b64encode((login['Username'] + ':' + login['Secret']).encode()).decode()
        entry = {'auth': encoded}
    data = json.dumps({'auths': {args.registry: {'auth': entry['auth']}}})
    fd = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as out:
        out.write(data)
    print('Wrote a temporary registry config with mode 0600:', args.out)


if __name__ == '__main__':
    main()
