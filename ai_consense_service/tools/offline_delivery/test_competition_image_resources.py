import gzip
import hashlib
import json
from pathlib import Path
import tarfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
IMAGES = ROOT / 'competition-assets' / 'images'


def digest(data):
    return hashlib.sha256(data).hexdigest()


class CompetitionImageResourcesTest(unittest.TestCase):
    def test_delivered_archives_have_consistent_identity_and_heap_defaults(self):
        expected = dict(line.split()[::-1] for line in (IMAGES / 'SHA256SUMS').read_text().splitlines())
        self.assertEqual(set(expected), {name + '.tar' for name in ('app', 'h2', 'source', 'gpu', 'qdrant')})
        for name, file_hash in expected.items():
            with self.subTest(image=name):
                archive = IMAGES / name
                self.assertEqual(digest(archive.read_bytes()), file_hash)
                with tarfile.open(archive) as tar:
                    read = lambda path: tar.extractfile(path).read()
                    index = json.loads(read('index.json'))
                    descriptor = index['manifests'][0]
                    manifest_bytes = read('blobs/sha256/' + descriptor['digest'].split(':')[1])
                    self.assertEqual(digest(manifest_bytes), descriptor['digest'].split(':')[1])
                    self.assertEqual(len(manifest_bytes), descriptor['size'])
                    manifest = json.loads(manifest_bytes)
                    config_descriptor = manifest['config']
                    config_path = 'blobs/sha256/' + config_descriptor['digest'].split(':')[1]
                    config_bytes = read(config_path)
                    self.assertEqual(digest(config_bytes), config_descriptor['digest'].split(':')[1])
                    self.assertEqual(len(config_bytes), config_descriptor['size'])
                    config = json.loads(config_bytes)
                    docker = json.loads(read('manifest.json'))[0]
                    self.assertEqual(docker['Config'], config_path)
                    self.assertEqual(config['config']['User'], 'consense:consense')
                    layers = manifest['layers']
                    self.assertEqual(len(layers), len(config['rootfs']['diff_ids']))
                    for layer, diff_id in zip(layers, config['rootfs']['diff_ids']):
                        layer_bytes = read('blobs/sha256/' + layer['digest'].split(':')[1])
                        self.assertEqual(digest(layer_bytes), layer['digest'].split(':')[1])
                        self.assertEqual(len(layer_bytes), layer['size'])
                        self.assertEqual('sha256:' + digest(gzip.decompress(layer_bytes)), diff_id)
                    if name == 'app.tar':
                        self.assertIn('JAVA_TOOL_OPTIONS=-Xmx32g', config['config']['Env'])
                    if name == 'h2.tar':
                        self.assertEqual(config['config']['Cmd'][0], '-Xmx32g')


if __name__ == '__main__':
    unittest.main()
