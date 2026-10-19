#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Review supplied Lua code in cct-run's isolated filesystem; never connect to Minecraft."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--materials-root', type=pathlib.Path, required=True)
    parser.add_argument('--cct', type=pathlib.Path, required=True)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    fixture = json.loads((args.materials_root / 'tests/fixture-tests.json').read_text(encoding='utf-8'))
    source_hashes = {}
    for path in sorted((args.materials_root / 'deliver/program').glob('*.lua')):
        source_hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.name != 'startup.lua':
            assert fixture['files']['/nf-precision-test/' + path.name] == path.read_text(encoding='utf-8')

    def run(name, fixture_data, code):
        fixture_path = args.out / (name + '-fixture.json')
        fixture_path.write_text(json.dumps(fixture_data, ensure_ascii=False), encoding='utf-8')
        program = args.out / (name + '.lua')
        program.write_text(code, encoding='utf-8')
        process = subprocess.run(
            [str(args.cct.resolve()), 'run', str(program.resolve()), '--fixture', str(fixture_path.resolve()),
             '--term', '200x50', '--timeout', '60', '--out', str((args.out / 'runs').resolve())],
            text=True, capture_output=True, timeout=90, env={**os.environ, 'LC_ALL': 'C.UTF-8'},
        )
        (args.out / (name + '-summary.json')).write_text(process.stdout, encoding='utf-8')
        (args.out / (name + '-stderr.txt')).write_text(process.stderr, encoding='utf-8')
        summary = json.loads(process.stdout)
        assert process.returncode == 0 and summary['status'] == 'ok', (name, summary.get('error'))
        return summary

    result = {'scope': 'Local Linux simulator review; game evidence comes from the supplied session only.',
              'source_sha256': source_hashes, 'original_tests': {}}
    for name in ['run', 'app_test', 'display_test']:
        summary = run(name, fixture, f'dofile("/nf-precision-test/tests/{name}.lua")\n')
        text = '\n'.join(summary['screen']['lines'])
        counts = re.search(r'RESULT pass=(\d+) fail=(\d+)', text)
        assert counts is not None and int(counts.group(2)) == 0, (name, text)
        result['original_tests'][name] = {'passed_assertions': int(counts.group(1)), 'failed_assertions': int(counts.group(2)),
                                          'status': summary['status'], 'exit_code': summary['exit_code']}
    fixture['files']['/review-probes.lua'] = pathlib.Path(__file__).with_name('review-probes.lua').read_text(encoding='utf-8')
    summary = run('review', fixture, 'dofile("/review-probes.lua")\n')
    screen = ''.join(summary['screen']['lines'])
    result['probes'] = json.loads(bytes.fromhex(screen.split('REVIEW_HEX ', 1)[1]))
    result['environment'] = summary['environment']
    (args.out / 'reproduction.json').write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
