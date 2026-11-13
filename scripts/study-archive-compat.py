#!/usr/bin/env python3
"""Measure JVM archive reuse across flag and cgroup changes in Linux containers.

Builds the extracted Spring Boot example, trains once on each JDK, then launches the
same files in fresh containers. The result is not a general proof for other JDKs or
applications. Run from the repository root; Docker and Python 3 are required.
"""
import argparse
import csv
import datetime
import json
import pathlib
import platform
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
CLASS = 'io.github.baokhang83.mnemo.example.DemoApplication source:'
IMAGES = {'21': 'eclipse-temurin:21-jdk', '25': 'eclipse-temurin:25-jdk'}
APP_IMAGE = 'mnemo-archive-compat-study:local'
BASE = ['-Xmx256m', '-XX:SoftMaxHeapSize=192m', '-XX:+UseG1GC']
GROUPS = [
    ('fixed', '1g', BASE, [
        ('baseline', '1g', BASE),
        ('xmx-512m', '1g', ['-Xmx512m', '-XX:SoftMaxHeapSize=192m', '-XX:+UseG1GC']),
        ('xms-64m', '1g', ['-Xms64m', '-Xmx256m', '-XX:SoftMaxHeapSize=192m', '-XX:+UseG1GC']),
        ('softmax-128m', '1g', ['-Xmx256m', '-XX:SoftMaxHeapSize=128m', '-XX:+UseG1GC']),
        ('serial-gc', '1g', BASE[:-1] + ['-XX:+UseSerialGC']),
        ('parallel-gc', '1g', BASE[:-1] + ['-XX:+UseParallelGC']),
        ('zgc', '1g', BASE[:-1] + ['-XX:+UseZGC']),
        ('compressed-oops-off', '1g', BASE + ['-XX:-UseCompressedOops']),
        ('compressed-class-pointers-off', '1g', BASE + ['-XX:-UseCompressedClassPointers']),
        ('cgroup-512m', '512m', BASE),
        ('cgroup-2g', '2g', BASE),
    ]),
    ('heap-layout', '1g', ['-Xms32m', '-Xmx256m', '-XX:SoftMaxHeapSize=192m', '-XX:+UseG1GC'], [
        ('baseline', '1g', ['-Xms32m', '-Xmx256m', '-XX:SoftMaxHeapSize=192m', '-XX:+UseG1GC']),
        ('xmx-40g', '1g', ['-Xms32m', '-Xmx40g', '-XX:SoftMaxHeapSize=192m', '-XX:+UseG1GC']),
    ]),
    ('large-heap', '1g', ['-Xmx512m', '-XX:SoftMaxHeapSize=192m', '-XX:+UseG1GC'], [
        ('baseline', '1g', ['-Xmx512m', '-XX:SoftMaxHeapSize=192m', '-XX:+UseG1GC']),
        ('xmx-256m', '1g', BASE),
    ]),
    ('small-softmax', '1g', ['-Xmx256m', '-XX:SoftMaxHeapSize=128m', '-XX:+UseG1GC'], [
        ('baseline', '1g', ['-Xmx256m', '-XX:SoftMaxHeapSize=128m', '-XX:+UseG1GC']),
        ('softmax-192m', '1g', BASE),
    ]),
    ('serial', '1g', BASE[:-1] + ['-XX:+UseSerialGC'], [
        ('baseline', '1g', BASE[:-1] + ['-XX:+UseSerialGC']),
        ('g1-gc', '1g', BASE),
    ]),
    ('zgc', '1g', BASE[:-1] + ['-XX:+UseZGC'], [
        ('baseline', '1g', BASE[:-1] + ['-XX:+UseZGC']),
        ('softmax-128m', '1g', ['-Xmx256m', '-XX:SoftMaxHeapSize=128m', '-XX:+UseZGC']),
        ('g1-gc', '1g', BASE),
    ]),
    ('uncompressed-oops', '1g', BASE + ['-XX:-UseCompressedOops'], [
        ('baseline', '1g', BASE + ['-XX:-UseCompressedOops']),
        ('compressed-oops-default', '1g', BASE),
    ]),
    ('uncompressed-class-pointers', '1g', BASE + ['-XX:-UseCompressedClassPointers'], [
        ('baseline', '1g', BASE + ['-XX:-UseCompressedClassPointers']),
        ('class-pointers-default', '1g', BASE),
    ]),
    ('automatic-heap', '1g', ['-XX:+UseG1GC'], [
        ('baseline', '1g', ['-XX:+UseG1GC']),
        ('cgroup-512m', '512m', ['-XX:+UseG1GC']),
        ('cgroup-2g', '2g', ['-XX:+UseG1GC']),
        ('max-ram-percentage-35', '1g', ['-XX:+UseG1GC', '-XX:MaxRAMPercentage=35']),
    ]),
]



def call(args, timeout=300, check=True):
    proc = subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout)
    if check and proc.returncode:
        raise RuntimeError(f'command failed ({proc.returncode}): {args!r}\n{proc.stdout[-5000:]}')
    return proc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=pathlib.Path)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if any(out.iterdir()):
        raise SystemExit(f'output directory must be empty: {out}')
    print('Building extracted example image', flush=True)
    call(['docker', 'build', '-q', '--build-arg', 'JDK=21', '-t', APP_IMAGE, 'examples/spring-boot'])
    environment = {'images': {}, 'groups': GROUPS,
                   'date_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   'host': platform.platform(), 'architecture': platform.machine(),
                   'git_commit': call(['git', 'rev-parse', 'HEAD']).stdout.strip(),
                   'git_dirty': bool(call(['git', 'status', '--porcelain']).stdout.strip()),
                   'note': 'Linux container runs; fixed extracted application path; one launch per scenario.'}
    for version, image in IMAGES.items():
        # Running java also pulls the image if it is not already present.
        call(['docker', 'run', '--rm', image, 'java', '-version'])
        environment['images'][version] = json.loads(call(['docker', 'image', 'inspect', image]).stdout)[0]['Id']
    environment['example_image'] = json.loads(call(['docker', 'image', 'inspect', APP_IMAGE]).stdout)[0]['Id']
    environment['docker_version'] = call(['docker', 'version', '--format', '{{.Server.Version}}']).stdout.strip()
    (out / 'environment.json').write_text(json.dumps(environment, indent=2) + '\n')
    results = []
    with tempfile.TemporaryDirectory(prefix='mnemo-archive-compat-') as temp:
        mount = f'{temp}:/work'
        call(['docker', 'run', '--rm', '-v', mount, '--entrypoint', 'sh', APP_IMAGE,
              '-c', 'mkdir -p /work/app && cp -Rp /app/. /work/app/'])
        for version, image in IMAGES.items():
            version_info = call(['docker', 'run', '--rm', image, 'java', '-version']).stdout.strip()
            (out / f'jdk{version}-version.txt').write_text(version_info + '\n')
            launch = ['-Dspring.context.exit=onRefresh', '-jar', '/work/app/app.jar']
            control = call(['docker', 'run', '--rm', '--memory=1g', '-v', mount, image, 'java',
                            '-Xlog:class+load=info', *launch], check=False)
            control_lines = [line for line in control.stdout.splitlines() if CLASS in line]
            (out / f'jdk{version}-uncached-control.log').write_text('\n'.join(control_lines) + '\n')
            if control.returncode or not any('source: file:' in line for line in control_lines):
                raise RuntimeError(f'JDK {version} uncached control did not load the app class from its JAR')
            for group, train_memory, train_flags, scenarios in GROUPS:
                archive = f'/work/archive-{version}-{group}' + ('.jsa' if version == '21' else '.aot')
                flag = ('-XX:ArchiveClassesAtExit=' if version == '21' else '-XX:AOTCacheOutput=') + archive
                runtime_flag = ('-XX:SharedArchiveFile=' if version == '21' else '-XX:AOTCache=') + archive
                prefix = ['docker', 'run', '--rm', f'--memory={train_memory}', '-v', mount, image, 'java']
                print(f'Training JDK {version}: {group}', flush=True)
                trained = call(prefix + train_flags + [flag] + launch, check=False)
                (out / f'jdk{version}-{group}-train.log').write_text(trained.stdout)
                if trained.returncode or not pathlib.Path(temp, pathlib.Path(archive).name).is_file():
                    if group == 'fixed':
                        raise RuntimeError(f'JDK {version} baseline training failed; see {out}/jdk{version}-{group}-train.log')
                    for name, memory, flags in scenarios:
                        results.append({'jdk': version, 'training': group, 'train_memory': train_memory,
                                        'train_flags': ' '.join(train_flags), 'scenario': name, 'memory': memory,
                                        'flags': ' '.join(flags), 'exit': trained.returncode,
                                        'archive': 'training-failed', 'class_source': ''})
                    continue
                for name, memory, flags in scenarios:
                    print(f'JDK {version}: {group}/{name}', flush=True)
                    cmd = ['docker', 'run', '--rm', f'--memory={memory}', '-v', mount, image, 'java']
                    cmd += flags + [runtime_flag, '-XshowSettings:vm', '-Xlog:class+load=info,cds=info', *launch]
                    run = call(cmd, check=False)
                    lines = run.stdout.splitlines()
                    app_lines = [line for line in lines if CLASS in line]
                    used = any('shared objects file' in line for line in app_lines)
                    outcome = ('failed' if run.returncode else 'unknown' if not app_lines else 'used' if used else 'not-used')
                    if name == 'baseline' and outcome != 'used':
                        raise RuntimeError(f'JDK {version} {group} baseline did not use its archive: {app_lines}')
                    evidence = [line for line in lines if '[class,load]' not in line or CLASS in line]
                    (out / f'jdk{version}-{group}-{name}.log').write_text('\n'.join(evidence) + '\n')
                    results.append({'jdk': version, 'training': group, 'train_memory': train_memory,
                                    'train_flags': ' '.join(train_flags), 'scenario': name, 'memory': memory,
                                    'flags': ' '.join(flags), 'exit': run.returncode,
                                    'archive': outcome, 'class_source': ' | '.join(app_lines)})
    with (out / 'results.csv').open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=results[0].keys(), lineterminator='\n')
        writer.writeheader()
        writer.writerows(results)
    print(f'Results: {out / "results.csv"}')


if __name__ == '__main__':
    main()
