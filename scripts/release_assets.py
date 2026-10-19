#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Release only the tested files. Existing assets must be byte-identical; never clobber a public asset."""
import argparse, hashlib, json, pathlib, subprocess, tempfile

def sha(path):
    with open(path,'rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()
def gh(*args,check=True):return subprocess.run(['gh',*map(str,args)],check=check,text=True,capture_output=True)
def prepare(paths,output):
    if len({p.name for p in paths})!=len(paths):raise ValueError('duplicate asset name')
    for path in paths:
        if not path.is_file():raise ValueError(f'missing asset: {path}')
    output.write_text(''.join(f'{sha(p)}  {p.name}\n' for p in sorted(paths,key=lambda p:p.name)),encoding='ascii')

def publish(tag,paths,notes):
    existing=gh('release','view',tag,'--json','assets,isDraft',check=False)
    missing=bool(existing.returncode)
    if missing and 'release not found' not in existing.stderr.lower() and '404' not in existing.stderr:
        raise RuntimeError(existing.stderr)
    assets=set() if missing else {a['name'] for a in json.loads(existing.stdout)['assets']}
    # Check every existing byte before adding any new asset to an existing release.
    with tempfile.TemporaryDirectory(prefix='cct-release-') as temp:
        for path in paths:
            if path.name in assets:
                gh('release','download',tag,'--pattern',path.name,'--dir',temp)
                if sha(path)!=sha(pathlib.Path(temp)/path.name):raise RuntimeError(f'existing asset differs: {path.name}; refusing to replace it')
    if missing:
        options=['--notes-file',notes] if notes else ['--generate-notes']
        gh('release','create',tag,'--draft','--verify-tag','--title',f'cct-run {tag.removeprefix("v")}',*options)
    for path in paths:
        if path.name not in assets:gh('release','upload',tag,path)
    gh('release','edit',tag,'--draft=false')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('tag');parser.add_argument('assets',nargs='+',type=pathlib.Path);parser.add_argument('--notes');parser.add_argument('--verification',type=pathlib.Path);parser.add_argument('--prepare-only',action='store_true');args=parser.parse_args()
    expected={f'cct-run-{args.tag.removeprefix("v")}.zip',f'cct-live-1.20.1-{args.tag.removeprefix("v")}.jar',f'cct-live-1.21.1-{args.tag.removeprefix("v")}.jar'}
    if {p.name for p in args.assets}!=expected:parser.error('expected exactly the CLI zip and the two versioned mod jars')
    if args.verification:
        verification=json.loads(args.verification.read_text())
        if verification['dirty']:parser.error('verification was generated from a dirty tree')
        if verification['commit']!=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip():parser.error('verification commit differs')
        if set(verification['modules'])!={'engine','cli','mod-forge','mod-neoforge'}:parser.error('missing verification module')
        if not all(m.get('tests',0)>0 and m.get('verified') and not any(m.get(k,0) for k in ('failures','errors','skipped')) for m in verification['modules'].values()):parser.error('incomplete verification')
        args.assets.append(args.verification)
    output=args.assets[0].parent/'SHA256SUMS';prepare(args.assets,output)
    if not args.prepare_only:publish(args.tag,args.assets+[output],args.notes)
