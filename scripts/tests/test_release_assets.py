# SPDX-License-Identifier: MPL-2.0
import importlib.util,pathlib,tempfile,unittest,json,subprocess
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('release_assets',pathlib.Path(__file__).resolve().parents[1]/'release_assets.py')
release=importlib.util.module_from_spec(spec);spec.loader.exec_module(release)

class ReleaseTest(unittest.TestCase):
    def test_mismatched_existing_asset_is_rejected_before_any_upload(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp);new=root/'new.jar';old=root/'old.jar';new.write_bytes(b'new');old.write_bytes(b'expected')
            calls=[]
            def gh(*args,**kwargs):
                calls.append(args)
                if args[1]=='view':return subprocess.CompletedProcess(args,0,json.dumps({'assets':[{'name':'old.jar'}],'isDraft':False}),'')
                if args[1]=='download':
                    pathlib.Path(args[-1],'old.jar').write_bytes(b'different')
                    return subprocess.CompletedProcess(args,0,'','')
                raise AssertionError('mutation before verification')
            with patch.object(release,'gh',gh):
                with self.assertRaises(RuntimeError):release.publish('v1',[new,old],None)
            self.assertFalse(any(call[1] in ('upload','create','edit') for call in calls))
    def test_matching_assets_are_skipped_and_checksum_manifest_is_stable(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp);a=root/'a.jar';a.write_bytes(b'a');manifest=root/'SHA256SUMS'
            release.prepare([a],manifest);first=manifest.read_text();release.prepare([a],manifest);self.assertEqual(first,manifest.read_text())
            calls=[]
            def gh(*args,**kwargs):
                calls.append(args)
                if args[1]=='view':return subprocess.CompletedProcess(args,0,json.dumps({'assets':[{'name':'a.jar'}],'isDraft':False}),'')
                if args[1]=='download':pathlib.Path(args[-1],'a.jar').write_bytes(b'a')
                return subprocess.CompletedProcess(args,0,'','')
            with patch.object(release,'gh',gh):release.publish('v1',[a],None)
            self.assertFalse(any(call[1]=='upload' for call in calls))
if __name__=='__main__':unittest.main()
