"""33 independent authored normal roots through real ASGI/CAS; no live engine/model."""
import copy
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import time
import unittest
from unittest.mock import patch

import native_observation
import normal_observed_projection
from oer_source_registry import _serialize
import test_normal_conditional_wire as conditional


class DerivedProofBreadthTests(unittest.TestCase):
    setUp = conditional.NormalConditionalWireTests.setUp
    tearDown = conditional.NormalConditionalWireTests.tearDown
    publish = conditional.NormalConditionalWireTests.publish
    url = conditional.NormalConditionalWireTests.url
    assert_no_inference = conditional.NormalConditionalWireTests.assert_no_inference

    def independent_sources(self):
        sources, documents = [], []
        for index in range(33):
            body = copy.deepcopy(self.body)
            graph = body['candidateGraph']
            source = graph['source']
            document = 'AUTHORED-breadth-%02d' % index
            original = hashlib.sha256(('authored original only ' + document).encode()).hexdigest()
            source.update(documentId=document, filename=document + '.docx', originalSourceSha256=original,
                          parsedSourceHash=hashlib.sha256(('authored parsed ' + document).encode()).hexdigest(),
                          identitySha256=hashlib.sha256(('authored routed ' + document).encode()).hexdigest())
            graph['generationSha256'] = hashlib.sha256(('authored generation ' + document).encode()).hexdigest()
            for unit in graph['units']:
                for part in unit['parts']:
                    part['sourceSha256'] = original
                for span in unit['sourceSpans']:
                    span['sourceSha256'] = original
            sources.append(self.publish(body))
            documents.append(document)
        self.assertEqual(33, len(set(documents)))
        self.assertEqual(33, len({item['projectionSha256'] for item in sources}))
        self.assertFalse(self.registry._derived_results, 'Publication must not prewarm canonical GET proofs')
        return sources

    def pass_once(self, sources):
        actual_reads, strict_roots, ordinals = [], [], Counter()
        addresses = {item['projectionSha256'] for item in sources}
        manifests = {item['observationManifestRef']['sha256'] for item in sources}
        read, encode = self.store._read_uncached, native_observation._bytes

        def trace_read(descriptor, *, retain):
            ordinals[descriptor['sha256']] += 1
            actual_reads.append((descriptor['sha256'], retain, ordinals[descriptor['sha256']]))
            return read(descriptor, retain=retain)

        def strict(value, *args, **kwargs):
            if isinstance(value, dict) and value.get('projectionSha256') in addresses:
                strict_roots.append(value['projectionSha256'])
            return encode(value, *args, **kwargs)

        started = time.perf_counter()
        with patch.object(self.store, '_read_uncached', trace_read), \
                patch.object(native_observation, '_bytes', strict), \
                patch.object(normal_observed_projection, '_assemble', wraps=normal_observed_projection._assemble) as assemble, \
                patch.object(self.registry, '_validate_normal', wraps=self.registry._validate_normal) as validate:
            for source in sources:
                etag = '"' + hashlib.sha256(_serialize(source)).hexdigest() + '"'
                response = self.client.get(self.url(source), headers={'If-None-Match': etag})
                self.assertEqual(304, response.status_code, response.text)
                self.assertEqual(b'', response.content)
                self.assertEqual(etag, response.headers['etag'])
                self.assertEqual(source['projectionSha256'], response.headers['x-projection-sha256'])
                self.assertEqual('private, no-cache', response.headers['cache-control'])
        manifest_reads = [(key, retain, ordinal) for key, retain, ordinal in actual_reads if key in manifests]
        self.assertEqual(Counter({key: 2 for key in manifests}), Counter(key for key, _, _ in manifest_reads),
                         'Every request must still read/hash its complete actual manifest at entry and successful exit')
        self.assertEqual(33, sum(ordinal == 2 and retain is False for _, retain, ordinal in manifest_reads),
                         'Every actual manifest must be rehashed by the successful-exit owner')
        self.assert_no_inference()
        for result in self.registry._derived_results.values():
            self.assertEqual(b'', result.serialized, 'Normal proofs retain dependencies, never a mutable full graph')
            self.assertIsInstance(result.dependencies, tuple)
            self.assertIsInstance(result.file_dependencies, tuple)
        self.assertLessEqual(self.registry._derived_bytes, self.registry._derived_max_bytes)
        self.assertLessEqual(len(self.registry._derived_results), self.registry._derived_max_entries)
        return {'normalValidations': validate.call_count, 'normalReconstructions': assemble.call_count,
                'strictRootReencodes': len(strict_roots), 'freshManifestReads': len(manifest_reads),
                'freshManifestExitRehashes': sum(ordinal == 2 and retain is False for _, retain, ordinal in manifest_reads),
                'retainedEntries': len(self.registry._derived_results), 'retainedBytes': self.registry._derived_bytes,
                'elapsedSeconds': time.perf_counter() - started}

    def record(self, name, passes):
        value = {'authoredOfflineASGI': True, 'documents': 33, 'sameSequentialOrder': True,
                 'module': self.module, 'maximumEntries': self.registry._derived_max_entries,
                 'maximumRetainedPayloadBytes': self.registry._derived_max_bytes, 'passes': passes,
                 'modelCalls': 0, 'liveBusinessApiCalls': 0, 'sourceQualityAcceptance': False}
        root = os.environ.get('CONSENSE_BREADTH_EVIDENCE')
        if root:
            path = Path(root) / (name + '.json')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(value, indent=2), encoding='utf-8')

    def test_default_retains_33_actual_proofs_without_reconstructing_the_second_pass(self):
        sources = self.independent_sources()
        passes = [self.pass_once(sources), self.pass_once(sources)]
        self.record('default33', passes)
        self.assertEqual(33, passes[0]['normalReconstructions'])
        self.assertEqual(0, passes[1]['normalReconstructions'],
                         'The default entry bound must retain the complete33-root operation within the existing byte cap')
        self.assertEqual(0, passes[1]['normalValidations'])
        self.assertEqual(33, passes[1]['strictRootReencodes'], 'Every warm root keeps its strict finite JSON decode/encode')

    def test_explicit_32_entry_lru_control_reconstructs_every_second_pass_root(self):
        self.registry._derived_max_entries = 32
        sources = self.independent_sources()
        passes = [self.pass_once(sources), self.pass_once(sources)]
        self.record('entry32-control', passes)
        self.assertEqual([33, 33], [item['normalReconstructions'] for item in passes])
        self.assertEqual(64 * 1024 * 1024, self.registry._derived_max_bytes)

    def test_existing_byte_cap_still_refuses_oversized_proofs_despite_entry_headroom(self):
        self.registry._derived_max_entries = 64
        self.registry._derived_max_bytes = 1
        sources = self.independent_sources()
        passes = [self.pass_once(sources), self.pass_once(sources)]
        self.record('byte-cap-control', passes)
        self.assertEqual([33, 33], [item['normalReconstructions'] for item in passes])
        self.assertEqual([0, 0], [item['retainedEntries'] for item in passes])
        self.assertEqual([0, 0], [item['retainedBytes'] for item in passes])
