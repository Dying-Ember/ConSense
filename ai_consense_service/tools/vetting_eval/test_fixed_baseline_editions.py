"""Authored edition scopes only; no originals, model, index or service access."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from build_fixed_development_baseline import descriptor, sl_edition_plans
from test_competition_scope import manifest, seal


class EditionPlanTests(unittest.TestCase):
    def test_each_edition_keeps_its_original_and_technical_page_denominator(self):
        with tempfile.TemporaryDirectory() as directory:
            editions=[]
            for name,sha in [('edition-a','a'*64),('edition-b','b'*64)]:
                scope=manifest();scope['source'].update(versionId=name,sourceSha256=sha);seal(scope)
                path=Path(directory)/(name+'.json');path.write_text(json.dumps(scope))
                editions.append({'slParent':{'editionId':name,'sha256':sha},'scopeManifest':descriptor(path),'requiredPhysicalPages':list(range(1127,4301))})
            self.assertEqual(editions,sl_edition_plans({'slEditions':editions}))
            changed=copy.deepcopy(editions);changed[1]['slParent']['sha256']='c'*64
            with self.assertRaises(ValueError):sl_edition_plans({'slEditions':changed})
            changed=copy.deepcopy(editions);changed[1]['requiredPhysicalPages'].pop()
            with self.assertRaises(ValueError):sl_edition_plans({'slEditions':changed})
            with self.assertRaises(ValueError):sl_edition_plans({'slEditions':[editions[0],editions[0]]})

    def test_legacy_plan_keeps_the_same_single_source(self):
        plan={'slParent':{'sha256':'a'*64}}
        self.assertEqual([plan],sl_edition_plans(plan))


if __name__=='__main__':unittest.main()
