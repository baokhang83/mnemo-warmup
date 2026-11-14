#!/usr/bin/env python3
"""Summarize bench-burst.sh raw pod records and preserve per-pod CSV data."""
import csv
import json
import pathlib
import re
import statistics
import sys
from datetime import datetime


def timestamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00')) if value else None


def condition_time(pod, kind):
    for condition in pod.get('status', {}).get('conditions', []):
        if condition.get('type') == kind and condition.get('status') == 'True':
            return timestamp(condition.get('lastTransitionTime'))
    return None


def phase_times(pod):
    status = pod.get('status', {})
    created = timestamp(pod['metadata'].get('creationTimestamp'))
    scheduled = condition_time(pod, 'PodScheduled')
    initialized = condition_time(pod, 'Initialized')
    ready = condition_time(pod, 'Ready')
    init_specs = pod.get('spec', {}).get('initContainers', [])
    first_init_name = init_specs[0]['name'] if init_specs else None
    first_init = next((s for s in status.get('initContainerStatuses', []) if s.get('name') == first_init_name), {})
    init_started = timestamp(first_init.get('state', {}).get('terminated', {}).get('startedAt'))
    app = next((s for s in status.get('containerStatuses', []) if s.get('name') == 'app'), {})
    app_started = timestamp(app.get('state', {}).get('running', {}).get('startedAt'))
    def seconds(start, end):
        return (end - start).total_seconds() if start and end else None
    return {
        'created_at': created, 'scheduled_at': scheduled, 'first_init_started_at': init_started,
        'initialized_at': initialized, 'app_container_started_at': app_started, 'ready_at': ready,
        'scheduling_s': seconds(created, scheduled),
        'init_install_fetch_s': seconds(init_started, initialized),
        'initialized_to_app_container_start_s': seconds(initialized, app_started),
        'app_container_start_to_ready_s': seconds(app_started, ready),
    }


def metric(pattern, text):
    m = re.search(pattern, text, re.MULTILINE)
    return int(m.group(1)) if m else None


def sample(values):
    values = sorted(v for v in values if v is not None)
    if not values:
        return 'n/a'
    return f'{statistics.median(values):.1f} ({values[0]:.1f}–{values[-1]:.1f})'


def main(root):
    root = pathlib.Path(root)
    rows = []
    bursts = []
    for directory in sorted((root / 'raw').glob('*-*')):
        if not directory.is_dir():
            continue
        arm, round_number = directory.name.rsplit('-', 1)
        if arm not in ('none', 'cached', 'fault') or not round_number.isdigit():
            continue
        baseline = (directory / 'baseline.txt').read_text().strip()
        requested = timestamp((directory / 'scale-requested-at.txt').read_text().strip())
        pods = json.loads((directory / 'pods.json').read_text())['items']
        fresh = [pod for pod in pods if pod['metadata']['name'] != baseline]
        if len(fresh) != 9:
            raise ValueError(f'{directory}: expected nine new pods, got {len(fresh)}')
        ready_times = []
        for pod in fresh:
            name = pod['metadata']['name']
            phases = phase_times(pod)
            ready = phases['ready_at']
            created = phases['created_at']
            if ready is None or created is None:
                raise ValueError(f'{directory}: {name} has no Ready or creation time')
            ready_times.append(ready)
            def read(suffix):
                path = directory / (name + suffix)
                return path.read_text(errors='replace') if path.exists() else ''
            fetch = read('.fetch.log')
            app = read('.app.log')
            cpu = read('.cpu.stat')
            container = next((c for c in pod.get('spec', {}).get('containers', []) if c.get('name') == 'app'), {})
            probe = container.get('readinessProbe', {})
            sizes = [int(m.group(1)) for line in read('.archive-sizes.txt').splitlines()
                     if (m := re.match(r'\s*(\d+)\s+(/\S+)', line)) and line.strip().split()[-1].endswith(('.jsa', '.aot', '.bin'))]
            rows.append({
                'arm': arm, 'round': int(round_number), 'pod': name,
                **phases,
                'readiness_initial_delay_s': probe.get('initialDelaySeconds', 0),
                'readiness_period_s': probe.get('periodSeconds', 10),
                'created_to_ready_s': (ready-created).total_seconds(),
                'scale_to_ready_s': (ready-requested).total_seconds(),
                'fetch_store_lookup_download_verify_ms': metric(r'fetch: timing:.*?store-lookup-download-verify=(\d+) ms', fetch),
                'archive_transfer_hash_write_ms': metric(r'archive-transfer-hash-write=(\d+) ms', fetch),
                'fetch_cgroup_cpu_usage_usec': metric(r'cgroup-cpu-usage=(\d+) usec', fetch),
                'fetch_process_ms': metric(r'timing: fetch-process=(\d+) ms', fetch),
                'launcher_plan_ms': metric(r'timing: plan-process=(\d+) ms', app),
                'archive_used': bool(re.search(r'run: using (APP_CDS|LEYDEN_AOT) archive', app)),
                'fetch_hit': 'Archive:' in fetch,
                'uncached_fallback': 'No usable archive' in fetch or 'continuing without an archive' in fetch,
                'http_429_reported': bool(re.search(r'HTTP 429|status.?429', fetch, re.I)),
                'cpu_nr_throttled': metric(r'^nr_throttled\s+(\d+)', cpu),
                'cpu_throttled_usec': metric(r'^throttled_usec\s+(\d+)', cpu),
                'archive_bytes': metric(r'archive-bytes=(\d+)', fetch) or (max(sizes) if sizes else None),
            })
        bursts.append({'arm': arm, 'round': int(round_number), 'last_ready_s': (max(ready_times)-requested).total_seconds()})
    if not rows:
        raise ValueError('No completed burst records')
    with (root/'pods.csv').open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    with (root/'bursts.csv').open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(bursts[0])); writer.writeheader(); writer.writerows(bursts)
    print('# One-to-ten replica bursts\n')
    print('Each burst starts with one Ready pod and adds nine. Times are median (min–max); Kubernetes pod timestamps have whole-second resolution. Scale-to-Ready uses the client timestamp immediately before the scale request.\n')
    print('| Arm | Bursts | New pods | Archive used | Uncached fallback | HTTP 429 reported | Pod creation to Ready (s) | Scale to last new pod Ready (s) | Archive transfer, hash and write (ms) |')
    print('|---|---:|---:|---:|---:|---:|---:|---:|---:|')
    for arm in ('none', 'cached', 'fault'):
        r = [row for row in rows if row['arm']==arm]; b = [row for row in bursts if row['arm']==arm]
        print(f"| {arm} | {len(b)} | {len(r)} | {sum(x['archive_used'] for x in r)} | {sum(x['uncached_fallback'] for x in r)} | {sum(x['http_429_reported'] for x in r)} | {sample(x['created_to_ready_s'] for x in r)} | {sample(x['last_ready_s'] for x in b)} | {sample(x['archive_transfer_hash_write_ms'] for x in r)} |")
    print('\n## Pod phases\n')
    print('| Arm | Creation to PodScheduled (s) | First init start to Initialized (s) | Initialized to app container start (s) | App container start to Ready (s) |')
    print('|---|---:|---:|---:|---:|')
    for arm in ('none', 'cached', 'fault'):
        r = [row for row in rows if row['arm'] == arm]
        print(f"| {arm} | {sample(x['scheduling_s'] for x in r)} | {sample(x['init_install_fetch_s'] for x in r)} | {sample(x['initialized_to_app_container_start_s'] for x in r)} | {sample(x['app_container_start_to_ready_s'] for x in r)} |")
    print('\nThe init interval begins when the first init container starts, so it excludes time before that start, including scheduling and any image pull. The app container running timestamp does not mark JVM or launcher completion; that work continues inside the last interval. The app has no init containers in the no-tool arm, so its init interval is unavailable. Probe initial delay and period are recorded per pod in `pods.csv`.\n')
    print('\nThe fault arm points to a refused local connection and exercises uncached startup; it is not a slow-remote-registry simulation. `pods.csv` contains per-pod timings, archive flags, reported 429s, app-container cgroup counters sampled after readiness, fetch-container CPU usage measured during native fetch, and archive sizes when available. Transfer time includes streaming, hashing, and writing the archive, and excludes request setup and response headers. A missing timing or counter remains empty.\n')
    print('A zero 429 count means only that captured fetch logs did not report HTTP 429; it does not prove the registry applied no rate or bandwidth limits. Unready bursts are saved as failure records and do not appear in this table.')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: bench_burst_summarize.py <result-directory>')
    main(sys.argv[1])
