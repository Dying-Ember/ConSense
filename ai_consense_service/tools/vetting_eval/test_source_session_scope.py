from contextvars import ContextVar
from types import SimpleNamespace
import unittest

from source_session_scope import source_declaration_sha256, source_scope_sha256
from token_windows import WindowError


class SourceSessionScopeTest(unittest.TestCase):
    def setUp(self):
        self.frame=ContextVar('fixture-frame',default=None)
        self.cas=ContextVar('fixture-cas',default=None)
        self.registry=SimpleNamespace(_validation_frame=self.frame,
            artifact_store=SimpleNamespace(_read_session=self.cas))
        self.source='a'*64;self.page='b'*64
        self.inputs=('project','vetting',[self.source],{self.source:self.page},[{'id':'one','text':'fixture'}],'1'*64)
        self.frame.set({'locations':{'/root':('/root',5,'c'*64)},'normalLocations':{'/root':False},
                        'softwareIdentity':{'runtime.py':'d'*64}})
        self.cas.set({('/cas/file','e'*64,7):({'sha256':'e'*64,'byteCount':7},None)})

    def test_deterministic_identity_binds_source_page_corpus_and_software(self):
        original=source_scope_sha256(self.registry,*self.inputs)
        self.assertEqual(original,source_scope_sha256(self.registry,*self.inputs))
        for replacement in (('project2',*self.inputs[1:]),
                            (self.inputs[0],self.inputs[1],self.inputs[2],{},*self.inputs[4:]),
                            (*self.inputs[:4],[{'id':'two','text':'fixture'}],self.inputs[5]),
                            (*self.inputs[:5],'2'*64)):
            self.assertNotEqual(original,source_scope_sha256(self.registry,*replacement))
        self.frame.get()['softwareIdentity']['runtime.py']='f'*64
        self.assertNotEqual(original,source_scope_sha256(self.registry,*self.inputs))

    def test_frame_dependency_growth_changes_identity(self):
        original=source_scope_sha256(self.registry,*self.inputs)
        declaration=source_declaration_sha256(*self.inputs)
        self.cas.get()[('/cas/new','f'*64,1)]=({},None)
        self.assertNotEqual(original,source_scope_sha256(self.registry,*self.inputs))
        self.assertEqual(declaration,source_declaration_sha256(*self.inputs))
        self.cas.get().pop(('/cas/new','f'*64,1))
        self.frame.get()['locations']['/new']=('/new',1,'f'*64)
        self.frame.get()['normalLocations']['/new']=True
        self.assertNotEqual(original,source_scope_sha256(self.registry,*self.inputs))

    def test_absent_frame_or_ambiguous_scope_fails_closed(self):
        self.frame.set(None)
        with self.assertRaises(WindowError):source_scope_sha256(self.registry,*self.inputs)
        self.frame.set({'locations':{'/root':('/root',5,'c'*64)},'normalLocations':{'/root':False},
                        'softwareIdentity':{'runtime.py':'d'*64}})
        for projections,pins in (([self.source,self.source],{}),([self.source],{'z'*64:self.page})):
            with self.subTest(projections=projections),self.assertRaises(WindowError):
                source_scope_sha256(self.registry,'project','vetting',projections,pins,[],'1'*64)


if __name__=='__main__':unittest.main()
