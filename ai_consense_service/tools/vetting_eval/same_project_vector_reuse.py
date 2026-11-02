"""Reuse verified predecessor vectors only for exact windows of the same project."""
import hashlib
import json
import os
import re
from pathlib import Path
from index_integrity import vector_sha256
from token_windows import digest, fail

GPU_FILES = {
    'vetting_eval/server.py': '1fec2b2f61069245505a4aca4564839ac48d3a8b872b025a7243d285bd7acc51',
    'vetting_eval/window_runtime.py': '53575013d344c8589074539fd06bacc3971f58e8c2a938ccfc3e686c97303cb1',
    'vetting_eval/token_windows.py': 'c7d7d6b90d652effd3bc8f1ce8b7fbdfa522c7914e09cb93f9627ead5667c2d6',
    'offline_delivery/model_session_worker.py': '8da6117c28a878f53b5cd6c64640ab1b5a7db7e4d2f884e5acc1e4b748cb281a',
}
def gpu_code_matches(root=Path('/runtime/reuse-gpu-tools'), config=Path('/runtime/config/gpu.json')):
    try:
        # Set only after the launcher verifies the live GPU container and its tools mount.
        attested = os.environ.get('CONSENSE_REUSE_ATTESTED_GPU_CONTAINER_ID', '')
        config_value = json.loads(config.read_bytes())
        return (re.fullmatch('[a-f0-9]{64}', attested) is not None
                and isinstance(config_value, dict) and config_value.get('containerId') == attested
                and all(hashlib.sha256((root / name).read_bytes()).hexdigest() == expected
                        for name, expected in GPU_FILES.items()))
    except (OSError, ValueError, TypeError):
        return False


class _Capture:
    def __init__(self, database, expected_collection):
        self.database = database
        self.expected_collection = expected_collection
        self.hashes = {}

    def __getattr__(self, name):
        return getattr(self.database, name)

    def scroll(self, collection, **kwargs):
        fail(collection == self.expected_collection, 'REUSE_COLLECTION', 'Only the predecessor collection may be read')
        points, offset = self.database.scroll(collection, **kwargs)
        for point in points:
            key = str(point.id)
            fail(key not in self.hashes, 'REUSE_DUPLICATE_POINT', 'Predecessor points must be unique')
            self.hashes[key] = vector_sha256(point.vector, len(point.vector))
        return points, offset


class SameProjectVectorReuse:
    def __init__(self, reader, runtime, database, target_project, target_module):
        receipt = reader.receipt
        old_key = reader.metadata['projectId']
        marker = '::canonical::' + target_module + '::'
        fail(target_module == 'advisory' and receipt.get('module') == target_module
             and marker in target_project and marker in old_key
             and target_project.split(marker, 1)[0] == old_key.split(marker, 1)[0]
             and receipt.get('h2Project') == target_project.split(marker, 1)[0]
             and target_project != old_key,
             'REUSE_PROJECT_MISMATCH', 'Predecessor must be the same project and module')
        capture = _Capture(database, reader.metadata['collection'])
        metadata = reader.read(old_key, runtime, lambda: capture)
        self.database = database
        self.collection = metadata['collection']
        self.signature = digest(metadata['signature'])
        self.dimension = metadata['vectorIntegrity']['dimension']
        self.old = {str(row['id']): row for row in metadata['windows']}
        self.hashes = capture.hashes
        fail(len(self.old) == len(metadata['windows']) == len(self.hashes),
             'REUSE_SCOPE_MISMATCH', 'Verified predecessor window set must be complete')

    def vectors(self, rows):
        fail(gpu_code_matches(), 'REUSE_GPU_IMPLEMENTATION_CHANGED',
             'GPU implementation changed during reuse; abandon this new index')
        matches = [(i, row) for i, row in enumerate(rows) if self.old.get(str(row['id'])) == row]
        if not matches:
            return {}
        points = self.database.retrieve(self.collection, ids=[row['id'] for _, row in matches],
                                        with_payload=True, with_vectors=True)
        by_id = {str(point.id): point for point in points}
        fail(len(by_id) == len(matches), 'REUSE_POINT_MISSING', 'Every matching predecessor vector is required')
        result = {}
        for index, row in matches:
            point = by_id[str(row['id'])]
            fail(point.payload == {**row, 'indexSignature': self.signature}
                 and vector_sha256(point.vector, self.dimension) == self.hashes[str(row['id'])],
                 'REUSE_POINT_CHANGED', 'Predecessor payload and verified vector must be unchanged')
            result[index] = point.vector
        return result
