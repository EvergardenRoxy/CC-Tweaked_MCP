#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Produce one machine-readable result from actual JUnit XML; missing modules stay explicitly unverified."""
import argparse, datetime, json, pathlib, subprocess, xml.etree.ElementTree as ET

def generate(root):
    modules = {}
    for module, folder in {'engine':'engine','cli':'cli','mod-forge':'mod/forge','mod-neoforge':'mod/neoforge'}.items():
        files = sorted((root/folder/'build/test-results/test').glob('TEST-*.xml'))
        if not files:
            modules[module] = {'verified': False}
            continue
        result = {key: 0 for key in ('tests','failures','errors','skipped')}
        for file in files:
            suite = ET.parse(file).getroot()
            for key in result: result[key] += int(suite.get(key,0))
        result['verified'] = True
        result['reports'] = [str(file.relative_to(root)) for file in files]
        result['report_mtime_utc'] = datetime.datetime.fromtimestamp(min(f.stat().st_mtime for f in files),datetime.timezone.utc).isoformat()
        modules[module] = result
    return {'schema':'cct-run/verification@1','generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
        'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip()),
        'modules':modules,
        'executions':sum(m.get('tests',0) for m in modules.values()),
        'note':'Counts are executions, including each mod variant. Report times identify cached results; unlisted platforms and game checks are unverified.'}

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='build/reports/verification.json');args=parser.parse_args()
    root=pathlib.Path(__file__).resolve().parent.parent
    output=pathlib.Path(args.output);output=output if output.is_absolute() else root/output
    output.parent.mkdir(parents=True,exist_ok=True)
    data=generate(root);output.write_text(json.dumps(data,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(f'{data["executions"]} test executions; summary: {output}')
