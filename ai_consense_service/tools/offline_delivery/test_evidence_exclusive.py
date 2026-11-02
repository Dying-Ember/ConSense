import json, os, unittest
from unittest.mock import patch
import test_dispatch_binding as fixtures

class ExclusiveEvidenceTest(unittest.TestCase):
    def test_existing_each_evidence_path_blocks_before_upstream_and_preserves_it(self):
        for suffix in ('.bin','.json','.failure.json','.wire','.overflow','.body-fragment','.cleanup.json'):
            with self.subTest(suffix=suffix):
                fixture=fixtures.Contract('test_capability'); fixture.setUp()
                try:
                    ident='c'*32; target=fixture.dir/('provider-'+ident+suffix); target.write_bytes(b'prior-evidence')
                    self.assertEqual(fixture.req([ident])[0],503)
                    self.assertEqual(target.read_bytes(),b'prior-evidence'); self.assertEqual(fixtures.Upstream.calls,[])
                    reservation=json.loads((fixture.dir/('provider-'+ident+'.reservation')).read_bytes())
                    self.assertEqual(reservation['status'],'claim_failed')
                    for name in reservation['reservedEmptyFiles']:
                        self.assertEqual((fixture.dir/name).read_bytes(),b'')
                finally: fixture.tearDown()

    def test_claim_race_does_not_overwrite_or_dispatch(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='d'*32; target=fixture.dir/('provider-'+ident+'.json'); original=os.open; injected=[]
            def race(path,flags,*args,**kwargs):
                if str(path)==str(target) and not injected:
                    target.write_bytes(b'racing-evidence'); injected.append(True)
                return original(path,flags,*args,**kwargs)
            with patch('fixed_gpu_worker.os.open',side_effect=race): self.assertEqual(fixture.req([ident])[0],503)
            self.assertTrue(injected); self.assertEqual(target.read_bytes(),b'racing-evidence'); self.assertEqual(fixtures.Upstream.calls,[])
            reservation=json.loads((fixture.dir/('provider-'+ident+'.reservation')).read_bytes())
            self.assertEqual(reservation['status'],'claim_failed'); self.assertEqual(reservation['reservedEmptyFiles'],['provider-'+ident+'.bin'])
        finally: fixture.tearDown()

    def test_mid_claim_io_error_retains_recorded_empty_placeholders(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='2'*32; target=fixture.dir/('provider-'+ident+'.wire'); original=os.open; claims=[]
            def opened(path,flags,*args,**kwargs):
                if str(path)==str(target): raise OSError('injected claim failure')
                fd=original(path,flags,*args,**kwargs)
                if 'provider-'+ident in str(path): claims.append(fd)
                return fd
            with patch('fixed_gpu_worker.os.open',side_effect=opened): self.assertEqual(fixture.req([ident])[0],503)
            self.assertEqual(fixtures.Upstream.calls,[])
            marker=json.loads((fixture.dir/('provider-'+ident+'.reservation')).read_bytes())
            self.assertEqual((marker['status'],marker['errorType']),('claim_failed','OSError'))
            self.assertEqual(len(marker['reservedEmptyFiles']),3)
            for name in marker['reservedEmptyFiles']: self.assertEqual((fixture.dir/name).read_bytes(),b'')
            for fd in claims:
                with self.assertRaises(OSError): os.fstat(fd)
        finally: fixture.tearDown()

    def test_reservation_fdopen_failure_leaves_unknown_empty_placeholders_and_closes_duplicates(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='e'*32; original_dup=os.dup; original_fdopen=os.fdopen; original_open=os.open; duplicates=[]; claims=[]; fired=[]
            def dup(fd):
                result=original_dup(fd); duplicates.append(result); return result
            def fdopen(fd,*args,**kwargs):
                if not fired: fired.append(True); raise OSError('injected fdopen failure')
                return original_fdopen(fd,*args,**kwargs)
            def opened(path,flags,*args,**kwargs):
                fd=original_open(path,flags,*args,**kwargs)
                if 'provider-'+ident in str(path): claims.append(fd)
                return fd
            with patch('fixed_gpu_worker.os.dup',side_effect=dup),patch('fixed_gpu_worker.os.fdopen',side_effect=fdopen),patch('fixed_gpu_worker.os.open',side_effect=opened):
                self.assertEqual(fixture.req([ident])[0],503)
            self.assertEqual(fixtures.Upstream.calls,[]); self.assertTrue(fired)
            self.assertEqual((fixture.dir/('provider-'+ident+'.reservation')).read_bytes(),b'')
            for fd in duplicates:
                with self.assertRaises(OSError): os.fstat(fd)
            for fd in claims:
                with self.assertRaises(OSError): os.fstat(fd)
        finally: fixture.tearDown()

    def test_identity_mismatch_after_claim_rejects_before_provider_request(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='1'*32; target=fixture.dir/('provider-'+ident+'.bin'); other=fixture.root/'other'; other.write_bytes(b'other')
            original=os.stat; fired=[]
            def stat(path,*args,**kwargs):
                if str(path)==str(target):
                    fired.append(True); return original(other)
                return original(path,*args,**kwargs)
            with patch('fixed_gpu_worker.os.stat',side_effect=stat): self.assertEqual(fixture.req([ident])[0],503)
            self.assertTrue(fired); self.assertEqual(fixtures.Upstream.calls,[]); self.assertEqual(other.read_bytes(),b'other')
            marker=json.loads((fixture.dir/('provider-'+ident+'.reservation')).read_bytes()); self.assertEqual(marker['status'],'reserved')
        finally: fixture.tearDown()

    def test_reserved_pre_dispatch_failure_records_safe_phase_without_upstream_request(self):
        cases = (
            ('4', '_listener_owned', ValueError('TOKEN private error'), 'listener_ownership', 'ValueError'),
            ('5', 'ProviderConnection', ConnectionRefusedError('TOKEN private error'), 'connection', 'ConnectionRefusedError'),
            ('6', '_connection_owned', TimeoutError('TOKEN private error'), 'connection_ownership', 'TimeoutError'),
        )
        for digit, target, error, phase, error_type in cases:
            with self.subTest(phase=phase):
                fixture=fixtures.Contract('test_capability'); fixture.setUp()
                try:
                    ident=digit*32
                    patched = patch('fixed_gpu_worker.ProviderConnection', side_effect=error) if target == 'ProviderConnection' else patch.object(fixture.worker,target,side_effect=error)
                    with patched:
                        self.assertEqual(fixture.req([ident])[0],503)
                    self.assertEqual(fixtures.Upstream.calls,[])
                    raw=(fixture.dir/('provider-'+ident+'.failure.json')).read_bytes()
                    failure=json.loads(raw)
                    self.assertEqual((failure['phase'],failure['errorType'],failure['providerOutcome']),
                                     (phase,error_type,'not_dispatched'))
                    self.assertEqual((failure['operationId'],failure['bindingSha256'],failure['dispatchId']),
                                     (fixture.op['operationId'],fixture.op['bindingSha256'],ident))
                    self.assertNotIn(b'TOKEN',raw)
                    self.assertNotIn(b'private error',raw)
                    self.assertFalse(fixture.worker.sessions[fixture.op['operationId']].get('providerOutcomeUnknown',False))
                finally: fixture.tearDown()

    def test_send_failure_without_response_retains_unknown_outcome(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='7'*32
            class Connection:
                class FakeSocket:
                    def shutdown(self, *args): pass
                    def close(self): pass
                def __init__(self,*args,**kwargs): self.sock=self.FakeSocket()
                def connect(self): pass
                def request(self,*args,**kwargs): raise OSError('TOKEN private error')
                def close(self): pass
            with patch('fixed_gpu_worker.ProviderConnection',Connection):
                self.assertEqual(fixture.req([ident])[0],503)
            raw=(fixture.dir/('provider-'+ident+'.failure.json')).read_bytes(); failure=json.loads(raw)
            self.assertEqual((failure['phase'],failure['errorType'],failure['providerOutcome']),
                             ('dispatch','OSError','unknown'))
            self.assertNotIn(b'TOKEN',raw)
            self.assertTrue(fixture.worker.sessions[fixture.op['operationId']]['providerOutcomeUnknown'])
        finally: fixture.tearDown()

    def test_pre_dispatch_cleanup_error_keeps_safe_failure_and_unknown_cleanup(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='8'*32
            class Connection:
                sock=None
                def __init__(self,*args,**kwargs): pass
                def connect(self): raise TimeoutError('TOKEN connect secret')
                def close(self): raise OSError('TOKEN close secret')
            with patch('fixed_gpu_worker.ProviderConnection',Connection):
                self.assertEqual(fixture.req([ident])[0],503)
            raw=(fixture.dir/('provider-'+ident+'.json')).read_bytes(); failure=json.loads(raw)
            self.assertEqual((failure['phase'],failure['errorType'],failure['providerOutcome']),
                             ('connection','TimeoutError','not_dispatched'))
            self.assertEqual(failure['cleanupErrorTypes'],['OSError'])
            self.assertNotIn(b'TOKEN',raw)
            self.assertEqual((fixture.dir/('provider-'+ident+'.cleanup.json')).read_bytes(),b'')
            self.assertEqual(fixtures.Upstream.calls,[])
        finally: fixture.tearDown()

    def test_success_reservation_names_all_placeholders_and_binds_body(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='f'*32; self.assertEqual(fixture.req([ident])[0],200)
            marker=json.loads((fixture.dir/('provider-'+ident+'.reservation')).read_bytes())
            self.assertEqual(marker['emptyFileMeaning'],'reserved_placeholder_until_receipt_identifies_content')
            self.assertEqual(marker['status'],'reserved'); self.assertEqual(len(marker['reservedEmptyFiles']),7)
            info=json.loads((fixture.dir/('provider-'+ident+'.json')).read_bytes()); self.assertTrue(info['complete'])
            for name in marker['reservedEmptyFiles']:
                if name.endswith(('.wire','.overflow','.body-fragment','.failure.json','.cleanup.json')):
                    self.assertEqual((fixture.dir/name).read_bytes(),b'')
        finally: fixture.tearDown()

    def test_short_success_receipt_never_rewrites_partial_and_failure_is_separate(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='3'*32; receipt=fixture.dir/('provider-'+ident+'.json'); original_open=os.open; original_fdopen=os.fdopen; keys=set()
            class Short:
                def __init__(self,stream): self.stream=stream
                def __enter__(self): return self
                def __exit__(self,*args): return self.stream.__exit__(*args)
                def write(self,data): return self.stream.write(data[:5])
                def __getattr__(self,name): return getattr(self.stream,name)
            def opened(path,flags,*args,**kwargs):
                fd=original_open(path,flags,*args,**kwargs)
                if str(path)==str(receipt):
                    st=os.fstat(fd); keys.add((st.st_dev,st.st_ino))
                return fd
            def fdopen(fd,*args,**kwargs):
                stream=original_fdopen(fd,*args,**kwargs); st=os.fstat(fd); key=(st.st_dev,st.st_ino)
                if key in keys: keys.remove(key); return Short(stream)
                return stream
            with patch('fixed_gpu_worker.os.open',side_effect=opened), patch('fixed_gpu_worker.os.fdopen',side_effect=fdopen):
                self.assertEqual(fixture.req([ident])[0],503)
            self.assertEqual(len(receipt.read_bytes()),5)
            failure=json.loads((fixture.dir/('provider-'+ident+'.failure.json')).read_bytes())
            self.assertEqual(failure['errorType'],'OSError'); self.assertEqual(failure['providerOutcome'],'unknown')
            self.assertTrue(failure['complete']); self.assertEqual(len(fixtures.Upstream.calls),1)
        finally: fixture.tearDown()
