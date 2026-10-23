"""Small source-binding checks using disposable files only."""
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory

from build_prototype import DOCUMENT_IDS, build_template_bindings
from build_source_view import source_image_name


def rejected(root, data, source):
    try:
        build_template_bindings(root, data, source)
    except ValueError:
        return
    raise AssertionError('Invalid template bindings were accepted.')


def main():
    first_hash = hashlib.sha256(b'first visual').hexdigest()
    second_hash = hashlib.sha256(b'second visual').hexdigest()
    assert source_image_name('ntt', first_hash, 1) != source_image_name('ntt', second_hash, 1)
    assert source_image_name('ntt', first_hash, 1) != source_image_name('ntt', first_hash, 2)
    with TemporaryDirectory(prefix='template-bindings-') as directory:
        root = Path(directory)
        original, visual = root / 'template.docx', root / 'preview.pdf'
        original.write_bytes(b'original')
        visual.write_bytes(b'visual')
        entry = {'original': str(original), 'originalSha256': hashlib.sha256(b'original').hexdigest(),
                 'visualPdf': str(visual), 'visualPdfSha256': hashlib.sha256(b'visual').hexdigest()}
        receipt = {'documents': {doc_id: dict(entry) for doc_id in sorted(DOCUMENT_IDS)}}
        receipt_path = root / 'source-view-receipt.json'
        receipt_path.write_text(json.dumps(receipt), 'utf-8')
        data = {'documents': [{'id': doc_id} for doc_id in sorted(DOCUMENT_IDS)]}
        source = {'documents': {doc_id: {'pages': [{'words': []}], 'kind': 'test'}
                                for doc_id in sorted(DOCUMENT_IDS)}}
        bindings = build_template_bindings(root, data, source)
        assert {binding['id'] for binding in bindings} == DOCUMENT_IDS
        assert all(set(binding) == {'id', 'fileName', 'format', 'originalSha256',
                                    'visualSha256', 'layoutSha256'} for binding in bindings)
        assert all(binding['fileName'] == 'template.docx' and binding['format'] == 'docx'
                   for binding in bindings)
        layout = json.dumps(source['documents']['ntt'], ensure_ascii=False,
                            sort_keys=True, separators=(',', ':')).encode('utf-8')
        assert bindings[0]['layoutSha256'] == hashlib.sha256(layout).hexdigest()
        original.write_bytes(b'changed')
        rejected(root, data, source)
        original.write_bytes(b'original')
        visual.write_bytes(b'changed')
        rejected(root, data, source)
        visual.write_bytes(b'visual')
        del receipt['documents']['ntt']
        receipt_path.write_text(json.dumps(receipt), 'utf-8')
        rejected(root, data, source)
        receipt['documents']['ntt'] = dict(entry)
        receipt_path.write_text(json.dumps(receipt), 'utf-8')
        duplicate = {'documents': [{'id': 'ntt'} for _ in DOCUMENT_IDS]}
        rejected(root, duplicate, source)
        missing_source = {'documents': {doc_id: document for doc_id, document in source['documents'].items()
                                        if doc_id != 'ntt'}}
        rejected(root, data, missing_source)
        del receipt['documents']['ntt']['originalSha256']
        receipt_path.write_text(json.dumps(receipt), 'utf-8')
        rejected(root, data, source)
    print('template-bindings: source/visual identity, complete IDs, private paths and image isolation checked')


if __name__ == '__main__':
    main()
