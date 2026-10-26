"""Authored literal-reading and product-library controls; no originals/models."""
import copy
from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from fixed_development_baseline_runtime import desc, state_path
from sl_library_server import fresh_native_pages, prepare_edition, index_request, Library, EDITIONS, SCHEMA


def fixture(root):
    original = root / 'authored.pdf'; original.write_bytes(b'authored immutable bytes, not an actual PDF')
    source = {**desc(original), 'editionId': 'authored', 'label': 'Authored source', 'pageCount': 4}
    text = ['Excluded metadata', 'Excluded SL PRE',
            'Authored heading\nABC1.M010.9 ORIGINAL CLAUSE\nExact \uf0a3 \U0001f600 body.\n',
            'Original continuation fragment.\nABC1.M020.9 SECOND CLAUSE\nOther literal.\n']
    pages = root / 'pages.jsonl'
    pages.write_text(''.join(json.dumps({'page': index, 'text': value}) + '\n'
                             for index, value in enumerate(text, 1)), encoding='utf-8')
    inventory = root / 'inventory.json'; inventory.write_text(json.dumps({'pageCount': 4}))
    scope = root / 'scope.json'; scope.write_text(json.dumps({'source': {'sourceSha256': source['sha256'], 'versionId': 'authored'},
        'includedPageSpans': [{'physicalPageStart': 3, 'physicalPageEnd': 4}],
        'includedLibraries': [{'title': 'Authored technical library', 'physicalPageStart': 3, 'physicalPageEnd': 4}]}))
    return {'source': source, 'pages': desc(pages), 'inventory': desc(inventory), 'scope': desc(scope),
            'technicalStart': 3, 'technicalEnd': 4}, text


class SlLibraryTests(unittest.TestCase):
    def test_default_state_guard_is_unchanged_and_explicit_owned_state_cannot_escape(self):
        historical = Path('C:/Users/Administrator/.codex/local-state/consense-oer/fixed-best-baseline-20261012')
        self.assertEqual((historical / 'AUTHORED/state').resolve(), state_path(historical / 'AUTHORED/state'))
        with tempfile.TemporaryDirectory() as directory:
            owned = Path(directory) / 'AUTHORED'; state = owned / 'state'
            self.assertEqual(state.resolve(), state_path(state, owned))
            for location, boundary in [(state, None), (owned, owned), (owned.parent / 'other', owned),
                                       ('relative/state', owned), (state, 'relative/owned')]:
                with self.subTest(location=location, boundary=boundary), self.assertRaises(ValueError):
                    state_path(location, boundary)

    def test_literal_suffix_and_tolerance_ids_follow_existing_sl_syntax(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = 'FWP3.1.5.030.B ORIGINAL SUFFIX\nLiteral body\nTOL.CON3.060.9 TOLERANCE\nOther body\n'
            text[3] = 'COM11.D010.9 ORIGINAL CLAUSE\nTail body\nABC1.M020.9 CONTENTS ..... 4\n'
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n' for i, value in enumerate(text, 1)))
            edition['pages'] = desc(path); rows, info = prepare_edition(edition)
            self.assertEqual(3, info['clauseCount'])
            self.assertEqual({'FWP3.1.5.030.B', 'TOL.CON3.060.9', 'COM11.D010.9'},
                             {row['clauseId'] for row in rows if 'clauseId' in row})
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

    def test_tolerance_table_leaders_split_clauses_across_a_section_page(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = ('TOL.CON3.030.9 TOLERANCES TO CONCRETE COVER - STARTER BARS\r\n'
                       'Starters in walls: ........ +5 mm\r\n'
                       'TOL.CON3.040.9 SPACING\r\n'
                       'The lesser of: ........ +50 mm or 20% of specified spacing\r\n'
                       'TOL.CON3.050.9 ANCHORAGE, LAP LENGTH AND CURTAILMENT\r\n'
                       'PD from specified length: ........ -25 mm\r\n'
                       'TOL.CON3.060.9 CUTTING AND BENDING\r\n'
                       'Bent bars continue on this page.\r\n')
            text[3] = ('APPENDIX H - SCHEDULE OF TOLERANCES\r\n'
                       'TOL.CON4 - PRECAST CONCRETE\r\n'
                       'TOL.CON4.010.9 ALL ELEMENTS\r\n'
                       '1. Length up to 2000 mm: ........ +/-6 mm\r\n'
                       'MAR.W320.10 AVOIDANCE OF POLLUTION DURING DREDGING,\r\n'
                       'TRANSPORTING AND DUMPING OF MARINE MUD ........ 17\r\n')
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n'
                                    for i, value in enumerate(text, 1)), encoding='utf-8')
            edition['pages'] = desc(path); rows, info = prepare_edition(edition)
            clauses = {row['clauseId']: row for row in rows if 'clauseId' in row}
            self.assertEqual(5, info['clauseCount'])
            self.assertEqual({'TOL.CON3.030.9', 'TOL.CON3.040.9', 'TOL.CON3.050.9',
                              'TOL.CON3.060.9', 'TOL.CON4.010.9'}, set(clauses))
            self.assertEqual([3], clauses['TOL.CON3.040.9']['originalPageNos'])
            self.assertEqual([3], clauses['TOL.CON3.050.9']['originalPageNos'])
            self.assertEqual([4], clauses['TOL.CON4.010.9']['originalPageNos'])
            self.assertNotIn('TOL.CON3.040.9', clauses['TOL.CON3.030.9']['content'])
            self.assertNotIn('TOL.CON4.010.9', clauses['TOL.CON3.060.9']['content'])
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

    def test_repeated_page_furniture_does_not_interrupt_a_continued_clause(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            header3 = ('PROFILED SHEET ROOFING AND CLADDING CLA1 > MATERIALS\r\n'
                       '________________________________________\r\n'
                       'Specification Library 2022 Edition CLA /6\r\n')
            header4 = header3.replace('CLA /6', 'CLA /7')
            text[2] = (header3 + 'CLA1.M110.9 PROFILED SHEET ROOFING AND CLADDING\r\n'
                       '1. Sheeting: source item one;\r\n')
            text[3] = (header4 + '2. Colour: source item two;\r\n'
                       '3. Accessories: source item three.\r\n'
                       'CLA1.M210.9 ACCESSORIES\r\nSeparate next clause.\r\n')
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n'
                                    for i, value in enumerate(text, 1)), encoding='utf-8')
            edition['pages'] = desc(path); rows, info = prepare_edition(edition)
            clauses = {row['clauseId']: row for row in rows if 'clauseId' in row}
            self.assertEqual(2, info['clauseCount'])
            continued = clauses['CLA1.M110.9']
            self.assertEqual([3, 4], continued['originalPageNos'])
            self.assertLess(continued['content'].index('1. Sheeting: source item one;'),
                            continued['content'].index('2. Colour: source item two;'))
            self.assertNotIn('Specification Library 2022 Edition CLA /7', continued['content'])
            self.assertNotIn('CLA1 > MATERIALS', continued['content'])
            self.assertTrue(any(part['sourceBlockId'].startswith(f'P4:cp{len(header4)}-')
                                for part in continued['parts']))
            self.assertEqual(text[3], json.loads(path.read_text(encoding='utf-8').splitlines()[3])['text'])

    def test_civil_numbering_preserves_revision_labels_and_wrapped_body_titles(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = ('BRI.D010.9 DEFINITIONS\r\n1. Schedule of bearings: the schedule stated in the Contract;\r\n'
                       'MAR.M010.10 APPLICATION OF OTHER WORKSECTIONS\r\nOriginal marine body.\r\n')
            text[3] = ('STW.M010.10 GENERAL STEELWORK\r\nSteelwork shall comply.\r\n'
                       'GIN.M010.10 SUBMISSION OF DETAILS REGARDING GEOTECHNICAL \r\n'
                       'INSTRUMENTATION\r\nOriginal submission body.\r\n'
                       'GIN.T070.10 VANE SHEAR TEST\r\nOriginal test body.\r\n')
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n' for i, value in enumerate(text, 1)))
            edition['pages'] = desc(path); rows, info = prepare_edition(edition)
            self.assertEqual(5, info['clauseCount'])
            self.assertEqual({'BRI.D010.9', 'MAR.M010.10', 'STW.M010.10', 'GIN.M010.10', 'GIN.T070.10'},
                             {row['clauseId'] for row in rows if 'clauseId' in row})
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))
            submission = next(row for row in rows if row.get('clauseId') == 'GIN.M010.10')
            self.assertIn('INSTRUMENTATION\r\nOriginal submission body.', submission['content'])
            self.assertTrue(all(not row['sourceQualityAccepted'] for row in rows))

    def test_appendix_headings_split_from_the_preceding_clause_and_keep_literal_text(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = ('COM11.T030.9 PROCEDURE\nOriginal test duty.\n'
                       'COM11.APPEND1.9CONCEPTUAL WORKFLOW FOR THE RFID SYSTEM\nAppendix diagram wording.\n')
            text[3] = ('Appendix continuation.\nCOM11.APPEND6.9SUGGESTED USER-INTERFACE LAYOUT\n'
                       'Interface wording.\nAP1.A010.B LIST OF TECHNICAL STANDARDS\n'
                       'Standard entries.\nCOM11.APPEND7.9 CONTENTS ........ 4\n')
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n'
                                    for i, value in enumerate(text, 1)), encoding='utf-8')
            edition['pages'] = desc(path); rows, info = prepare_edition(edition)
            self.assertEqual(4, info['clauseCount'])
            self.assertEqual({'COM11.T030.9', 'COM11.APPEND1.9', 'COM11.APPEND6.9', 'AP1.A010.B'},
                             {row['clauseId'] for row in rows if 'clauseId' in row})
            self.assertEqual([3, 4], next(row for row in rows if row.get('clauseId') == 'COM11.APPEND1.9')['originalPageNos'])
            self.assertEqual([3], next(row for row in rows if row.get('clauseId') == 'COM11.T030.9')['originalPageNos'])
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

    def test_page_leading_cross_reference_remains_in_the_prior_clause(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = 'COM5.M005.9 SUBMISSIONS\nProvide the test reports as specified in sub-clause (2) of\n'
            text[3] = ("COM5.M090 for CM's information. The date of the test shall be within five years.\n"
                       'COM5.M010.9 NEXT PROVISION\nNext duty.\n')
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n'
                                    for i, value in enumerate(text, 1)), encoding='utf-8')
            edition['pages'] = desc(path); rows, info = prepare_edition(edition)
            self.assertEqual(2, info['clauseCount'])
            prior = next(row for row in rows if row.get('clauseId') == 'COM5.M005.9')
            self.assertEqual([3, 4], prior['originalPageNos'])
            self.assertIn("COM5.M090 for CM's information", prior['content'])
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

    def test_standalone_section_heading_precedes_the_next_same_page_clause(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = ('COM7.D010.9 DESIGN RESPONSIBILITY\r\n'
                       'Design and manufacture the gatesets.\r\n'
                       'PERFORMANCE CRITERIA\r\n'
                       'COM7.D140.9 GATESET OPERATION\r\n'
                       'Ensure smooth operation.\r\n'
                       'COM7.D150.9 OTHER REQUIREMENT\r\n'
                       'A TABLE VALUE\r\nStill part of this clause.\r\n'
                       'COM7.D160.9 NEXT REQUIREMENT\r\nNext duty.\r\n')
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n'
                                    for i, value in enumerate(text, 1)), encoding='utf-8')
            edition['pages'] = desc(path); rows, info = prepare_edition(edition)
            clauses = {row['clauseId']: row for row in rows if 'clauseId' in row}
            self.assertEqual(5, info['clauseCount'])
            self.assertNotIn('PERFORMANCE CRITERIA', clauses['COM7.D010.9']['content'])
            self.assertTrue(clauses['COM7.D140.9']['content'].startswith(
                'PERFORMANCE CRITERIA\r\nCOM7.D140.9 GATESET OPERATION'))
            self.assertIn('A TABLE VALUE\r\nStill part of this clause.', clauses['COM7.D150.9']['content'])
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

    def test_printed_section_change_ends_prior_clause_at_page_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = ('GATESETS COM7 > DESIGN\r\nSpecification Library 2022 Edition COM /116\r\n'
                       'PERFORMANCE CRITERIA\r\nCOM7.D140.9 GATESET OPERATION\r\n'
                       'Ensure the operation of gateset is smooth.\r\n')
            text[3] = ('GATESETS COM7 > MATERIALS\r\nSpecification Library 2022 Edition COM /117\r\n'
                       'MATERIALS\r\nGALVANISED MILD STEEL GATESETS\r\n'
                       'COM7.M010.9 GALVANIZED MILD STEEL GATESETS\r\nSubmit one sample.\r\n')
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n'
                                    for i, value in enumerate(text, 1)), encoding='utf-8')
            edition['pages'] = desc(path); rows, _ = prepare_edition(edition)
            clauses = {row['clauseId']: row for row in rows if 'clauseId' in row}
            self.assertEqual([3], clauses['COM7.D140.9']['originalPageNos'])
            self.assertNotIn('MATERIALS', clauses['COM7.D140.9']['content'])
            self.assertEqual([4], clauses['COM7.M010.9']['originalPageNos'])
            self.assertTrue(any(row['observedNodeKind'] == 'context' and 'MATERIALS' in row['content']
                                and row['originalPageNos'] == [4] for row in rows))
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

            text[3] = ('GATESETS COM7 > DESIGN\r\nSpecification Library 2022 Edition COM /117\r\n'
                       'The same operation continues.\r\nCOM7.D150.9 NEXT REQUIREMENT\r\nNext duty.\r\n')
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n'
                                    for i, value in enumerate(text, 1)), encoding='utf-8')
            edition['pages'] = desc(path); rows, _ = prepare_edition(edition)
            prior = next(row for row in rows if row.get('clauseId') == 'COM7.D140.9')
            self.assertEqual([3, 4], prior['originalPageNos'])
            self.assertIn('The same operation continues.', prior['content'])
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

    def test_civil_and_existing_contents_entries_remain_text_without_clause_labels(self):
        entries = ['BRI.D010.10 DEFINITIONS ........ 4\r\n',
                   'MAR.W320.10 AVOIDANCE OF POLLUTION DURING DREDGING, \r\n'
                   'TRANSPORTING AND DUMPING OF MARINE MUD ..............................17\r\n',
                   'COM11.D010.9 ORIGINAL WRAPPED CONTENTS\nCONTINUATION ........ 5\n',
                   'TABLE OF CONTENTS\nBRI.D010.10 DEFINITIONS\nCOM7.1 OTHER CONTENTS\n',
                   'MARINE WORKS MAR > TABLE OF CONTENTS\r\nMAR.M010.10 APPLICATION OF OTHER WORKSECTIONS\r\n']
        for entry in entries:
            with self.subTest(entry=entry), tempfile.TemporaryDirectory() as directory:
                edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
                text[2] = entry; text[3] = 'BRI.D010.10 DEFINITIONS\nOriginal body without a contents leader.\n'
                path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n' for i, value in enumerate(text, 1)))
                edition['pages'] = desc(path); rows, info = prepare_edition(edition)
                self.assertEqual(1, info['clauseCount'])
                clause = next(row for row in rows if 'clauseId' in row)
                self.assertEqual('BRI.D010.10', clause['clauseId']); self.assertEqual([4], clause['originalPageNos'])
                self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

    def test_contents_page_stays_separate_from_preceding_clause_and_next_heading_leader(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = 'COM7.1 REAL CLAUSE\nCOM7.2 CONTENTS ..... 4\n'
            text[3] = 'MARINE WORKS MAR > TABLE OF CONTENTS\nMAR.M010.10 CONTENTS WITHOUT LEADERS\n'
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n' for i, value in enumerate(text, 1)))
            edition['pages'] = desc(path); rows, info = prepare_edition(edition)
            self.assertEqual(1, info['clauseCount'])
            clause = next(row for row in rows if 'clauseId' in row)
            self.assertEqual('COM7.1', clause['clauseId']); self.assertEqual([3], clause['originalPageNos'])
            self.assertTrue(any(row['observedNodeKind'] == 'context' and row['originalPageNos'] == [4] for row in rows))
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))

    def test_runtime_observer_retains_index_progress_and_logs_only_bounded_retrieval_fields(self):
        library = object.__new__(Library); library.lock = threading.RLock(); library.backend = None
        backend = SimpleNamespace(); library.backend_factory = lambda manifest: backend
        library.plan = {'models': {}, 'workspace': 'AUTHORED'}; library.code = {}; updates = []
        library.update = lambda **values: updates.append(values)
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertIs(backend, library.runtime())
            backend._window_observer('window-index-upsert', {'encodedWindowsCumulative': 3, 'begin': 0, 'windowIds': ['private-id']})
            backend._window_observer('retrieval-phase', {'phase': 'metadata', 'status': 'completed',
                'querySha256': 'a' * 64, 'selectedParents': 5, 'elapsedSeconds': .1, 'durationSeconds': .1,
                'stageOrdinal': 4, 'windowBindings': ['private-binding'], 'query': 'private-query'})
            backend._window_observer('other-stage', {'query': 'private-other-query'})
        self.assertEqual(3, updates[0]['encodedWindows']); self.assertEqual('Persisted embedding windows: 1', updates[0]['message'])
        event = json.loads(output.getvalue()); self.assertEqual('retrieval-phase', event['event'])
        self.assertEqual(5, event['selectedParents']); self.assertEqual(4, event['stageOrdinal'])
        self.assertNotIn('private', output.getvalue()); self.assertNotIn('windowBindings', event)

    def test_retrieve_logs_hashed_query_and_terminal_success_or_failure_without_text(self):
        for failed in (False, True):
            with self.subTest(failed=failed):
                library = object.__new__(Library); library.work = threading.Lock(); library.artifacts = {'ready': True}
                library.plan = {'projectId': 'AUTHORED', 'editions': [{'source': {'sha256': 'a' * 64}}], 'baseSources': {}}
                def retrieve(request):
                    if failed: raise ValueError('private failure text')
                    return {'hits': []}
                backend = SimpleNamespace(window_runtime=lambda: SimpleNamespace(retrieve=retrieve),
                                          RetrieveRequest=lambda **values: SimpleNamespace(**values))
                library.runtime = lambda: backend; query = 'private query text'; output = io.StringIO()
                with redirect_stdout(output):
                    if failed:
                        with self.assertRaises(ValueError): library.retrieve({'projectId': 'AUTHORED', 'query': query, 'sourceSha256': ['a' * 64]})
                    else: self.assertEqual({'hits': []}, library.retrieve({'projectId': 'AUTHORED', 'query': query, 'sourceSha256': ['a' * 64]}))
                events = [json.loads(line) for line in output.getvalue().splitlines()]
                self.assertEqual(['started', 'failed' if failed else 'completed'], [event['status'] for event in events])
                self.assertTrue(all(event['event'] == 'sl-library-retrieve' and event['selectedSources'] == 1
                                    and event['querySha256'] == hashlib.sha256(query.encode()).hexdigest() for event in events))
                self.assertGreaterEqual(events[-1]['elapsedSeconds'], 0); self.assertNotIn('private', output.getvalue())

    def test_complete_library_can_exceed_public_parent_cap_with_all_chunk_validation_retained(self):
        from pydantic import BaseModel, Field, ValidationError
        class Chunk(BaseModel):
            id: str
            content: str = Field(min_length=1)
        class Request(BaseModel):
            projectId: str
            chunks: list[Chunk] = Field(max_length=50000)
            rebuild: bool = False
        backend = SimpleNamespace(Chunk=Chunk, IndexRequest=Request)
        rows = [{'id': str(index), 'content': 'AUTHORED finite library chunk'} for index in range(50001)]
        request = index_request(backend, 'AUTHORED', rows)
        self.assertEqual(50001, len(request.chunks)); self.assertFalse(request.rebuild)
        with self.assertRaises(ValidationError): Request(projectId='AUTHORED', chunks=rows)
        rows[-1]['content'] = ''
        with self.assertRaises(ValidationError): index_request(backend, 'AUTHORED', rows)
        with self.assertRaises(ValueError): index_request(backend, 'AUTHORED', [{}] * 100001)

    def test_product_publication_is_shared_fixed_baseline_and_drops_only_competition_catalog_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); editions = []
            counts = (3052, 3198, 3174, 3207, 3209, 3200)
            for index, (name, count) in enumerate(zip(EDITIONS, counts)):
                folder = root / name; folder.mkdir(); edition, _ = fixture(folder)
                path = folder / 'authored.pdf'; path.write_bytes(('AUTHORED source ' + str(index)).encode())
                edition['source'].update(desc(path), editionId=name)
                edition.update(technicalStart=1, technicalEnd=count); editions.append(edition)
            bases, base_rows = {}, []
            for family in ('GCC', 'GCT', 'SL'):
                path = root / (family + '.pdf'); path.write_bytes(('AUTHORED base ' + family).encode())
                if family != 'SL': bases[family.lower() + 'Source'] = desc(path)
                base_rows.append({'id': 'AUTHORED-' + family, 'content': family,
                                  'sourceQuality': {'family': family}})
            base = root / 'base.jsonl'; base.write_text(''.join(json.dumps(row) + '\n' for row in base_rows))
            output = root / '.local/sl-official-product-AUTHORED/runtime-output'
            plan = {'schema': SCHEMA, 'editions': editions, 'output': str(output), 'workspace': str(root),
                    'state': str(output / 'state'), 'projectId': 'AUTHORED-no-model', 'models': {'publications': {}},
                    'baseSources': bases, 'baseCorpus': desc(base)}
            metadata_path = root / 'metadata.json'; indexed = []
            class Runtime:
                def index(self, request):
                    indexed.extend(request.chunks)
                    metadata_path.write_text(json.dumps({'signature': {'AUTHORED': True}, 'collection': 'AUTHORED',
                                                         'vectorIntegrity': {'AUTHORED': True}}))
                    return {'signature': 'a' * 64, 'vectorPoints': len(request.chunks)}
            backend = SimpleNamespace(window_runtime=lambda: Runtime(), metadata_path=lambda project: metadata_path,
                                      IndexRequest=lambda **kwargs: SimpleNamespace(**kwargs))
            for key, value in [('state', str(root / 'outside-state')), ('output', 'relative/runtime-output'),
                               ('output', str(root / 'unrelated/runtime-output'))]:
                invalid = copy.deepcopy(plan); invalid[key] = value
                with self.subTest(field=key, value=value), self.assertRaises(ValueError):
                    Library(invalid, {'sha256': 'b' * 64}, lambda manifest: backend)
            library = Library(plan, {'path': 'AUTHORED', 'sha256': 'b' * 64, 'bytes': 0},
                              lambda manifest: backend, port=18182)
            def fresh(edition, path, callback):
                Path(path).write_text('AUTHORED saved page placeholder')
                return {'freshOriginalRead': True, 'seconds': 1}
            def prepare(edition):
                return [{'id': edition['source']['editionId'], 'sourceQuality': {'family': 'SL'}}], {
                    'parsedPages': edition['technicalEnd'], 'clauseCount': 1, 'missingPages': [], 'parentCount': 1}
            with patch('sl_library_server.fresh_native_pages', side_effect=fresh), \
                    patch('sl_library_server.prepare_edition', side_effect=prepare):
                library.build()
            status = library.status(); self.assertEqual('completed', status['job']['status'])
            self.assertTrue(status['retrievalAvailable']); self.assertEqual(8, len(indexed))
            self.assertEqual('http://127.0.0.1:18182', status['artifacts']['baseUrl'])
            self.assertNotIn('AUTHORED-SL', [row['id'] for row in indexed])
            manifest = json.loads(Path(status['artifacts']['manifestPath']).read_bytes())
            self.assertEqual('consense.frozen-fixed-development-baseline.v1', manifest['schema'])
            self.assertEqual(list(EDITIONS), [source['editionId'] for source in manifest['originalSources']['slSources']])
            self.assertEqual(19040, manifest['coverage']['SLAvailablePagePositions'])
            self.assertEqual(8, manifest['coverage']['totalParents'])
            self.assertFalse(manifest['finalParsingAccepted']); self.assertFalse(manifest['businessAccepted'])
            self.assertTrue(all(row['indexedChunks'] == 1 for row in manifest['coverage']['versions']))
            with patch('threading.Thread') as thread:
                library.start(); thread.assert_not_called()

    def test_literal_clause_parents_preserve_all_technical_text_and_exclude_pre(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); before = copy.deepcopy(edition)
            rows, info = prepare_edition(edition)
            self.assertEqual((2, 2, 2), (info['technicalPages'], info['parsedPages'], info['clauseCount']))
            self.assertEqual(''.join(text[2:]), ''.join(part['text'] for row in rows for part in row['parts']))
            self.assertNotIn('Excluded', ''.join(row['content'] for row in rows))
            self.assertEqual({'ABC1.M010.9', 'ABC1.M020.9'}, {row['clauseId'] for row in rows if 'clauseId' in row})
            self.assertTrue(all(not row['sourceQualityAccepted'] and not row['semanticScopeVerified'] for row in rows))
            self.assertEqual({3, 4}, {part['pageNo'] for row in rows for part in row['parts']})
            self.assertEqual(before, edition)

    def test_blank_page_is_a_gap_and_contents_leaders_do_not_invent_clause_parents(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, text = fixture(Path(directory)); path = Path(edition['pages']['path'])
            text[2] = 'ABC1.M010.9 CONTENTS ........ 3\n'; text[3] = ''
            path.write_text(''.join(json.dumps({'page': i, 'text': value}) + '\n' for i, value in enumerate(text, 1)))
            edition['pages'] = desc(path)
            rows, info = prepare_edition(edition)
            self.assertEqual([4], info['missingPages']); self.assertEqual(1, info['parsedPages'])
            self.assertEqual(0, info['clauseCount']); self.assertTrue(rows)
            self.assertTrue(all('clauseId' not in row for row in rows))

    def test_changed_original_or_missing_page_refuses_without_relabeling(self):
        with tempfile.TemporaryDirectory() as directory:
            edition, _ = fixture(Path(directory)); Path(edition['source']['path']).write_bytes(b'changed')
            with self.assertRaises(ValueError): prepare_edition(edition)
        with tempfile.TemporaryDirectory() as directory:
            edition, _ = fixture(Path(directory)); path = Path(edition['pages']['path'])
            path.write_text(json.dumps({'page': 3, 'text': 'Unbound text'}) + '\n'); edition['pages'] = desc(path)
            with self.assertRaises(ValueError): prepare_edition(edition)

    def test_fresh_reader_opens_complete_original_and_attempts_all_technical_pages(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); edition, _ = fixture(root); visited = []; closed = []
            class TextPage:
                def get_text_range(self): return 'Fresh original literal'
                def close(self): closed.append('text')
            class Page:
                def __init__(self, number): self.number = number
                def get_textpage(self):
                    if self.number == 4: raise RuntimeError('AUTHORED page failure')
                    return TextPage()
                def close(self): closed.append(self.number)
            class Document:
                def __len__(self): return 4
                def __getitem__(self, index): visited.append(index + 1); return Page(index + 1)
                def close(self): closed.append('document')
            inputs = []
            result = fresh_native_pages(edition, root / 'fresh.jsonl', pdf_factory=lambda value: inputs.append(value) or Document())
            self.assertEqual([Path(edition['source']['path']).read_bytes()], inputs)
            self.assertEqual([3, 4], visited); self.assertEqual(2, result['technicalPagesAttempted'])
            self.assertEqual(4, result['failedPages'][0]['physicalPage'])
            self.assertIn('document', closed); self.assertIn(4, closed)
            pages = [json.loads(line) for line in (root / 'fresh.jsonl').read_text().splitlines()]
            self.assertEqual(['', '', 'Fresh original literal', ''], [page['text'] for page in pages])
            self.assertEqual('native_read_failed', pages[-1]['status'])
            self.assertTrue(all(page['sourceSha256'] == edition['source']['sha256'] for page in pages))

    def test_search_filters_exact_editions_before_ranking(self):
        library = object.__new__(Library)
        library.plan = {'projectId': 'authored', 'editions': [{'source': {'editionId': name, 'sha256': str(index) * 64}}
                        for index, name in enumerate(EDITIONS)]}
        captured = []
        library.retrieve = lambda value: captured.append(value) or {'hits': [], 'indexSignature': 'authored'}
        result = library.search({'query': 'Original clause', 'editionIds': [EDITIONS[2]], 'limit': 3})
        self.assertEqual(['2' * 64], captured[0]['sourceSha256']); self.assertEqual(3, captured[0]['limit'])
        self.assertEqual([EDITIONS[2]], result['editionIds'])
        for ids in ([], ['competition-batch02'], [EDITIONS[0], EDITIONS[0]]):
            with self.subTest(ids=ids), self.assertRaises(ValueError): library.search({'query': 'x', 'editionIds': ids})


if __name__ == '__main__': unittest.main()
