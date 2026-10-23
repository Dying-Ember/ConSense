"""Build the disposable review page with fixed local original-page assets."""
import argparse
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
DOCUMENT_IDS = {'ntt', 'sct', 'scc-word', 'scc-pdf'}


def build_template_bindings(data_root, data, source):
    receipt = json.loads((data_root / 'source-view-receipt.json').read_text('utf-8'))
    ids = [document['id'] for document in data['documents']]
    if (len(ids) != len(DOCUMENT_IDS) or set(ids) != DOCUMENT_IDS or
            set(source['documents']) != DOCUMENT_IDS or
            set(receipt.get('documents', {})) != DOCUMENT_IDS):
        raise ValueError('Template bindings require all four unique source documents.')
    bindings = []
    for doc_id in ids:
        entry = receipt['documents'][doc_id]
        paths = {}
        for name, hash_name in [('original', 'originalSha256'), ('visualPdf', 'visualPdfSha256')]:
            path = Path(entry[name])
            if hashlib.sha256(path.read_bytes()).hexdigest() != entry.get(hash_name):
                raise ValueError(f'{doc_id}: {name} SHA256 does not match the source receipt.')
            paths[name] = path
        layout = json.dumps(source['documents'][doc_id], ensure_ascii=False,
                            sort_keys=True, separators=(',', ':')).encode('utf-8')
        bindings.append({'id': doc_id, 'fileName': paths['original'].name,
                         'format': paths['original'].suffix[1:].lower(),
                         'originalSha256': entry['originalSha256'],
                         'visualSha256': entry['visualPdfSha256'],
                         'layoutSha256': hashlib.sha256(layout).hexdigest()})
    return bindings


def build(data_root, output):
    data = json.loads((data_root / 'data.json').read_text('utf-8'))
    words = json.loads((data_root / 'word-business-copy.json').read_text('utf-8'))
    pdf = json.loads((data_root / 'pdf-business-copy.json').read_text('utf-8'))
    wording = {**words, **pdf}
    assert len(data['variables']) == 190
    assert len({v['id'] for v in data['variables']}) == 190
    assert len(data['documents']) == 4
    assert set(wording) == {v['id'] for v in data['variables']}
    for item in data['variables']:
        text = wording[item['id']]
        assert text['summaryZh'].strip() and text['summaryEn'].strip()
        item['guidance'] = {'zh': text['summaryZh'], 'en': text['summaryEn']}
        item['relations'] = [{'titleZh': '现有关系摘要', 'titleEn': 'Existing relationship summary',
                             'descriptionZh': '', 'descriptionEn': '',
                             'branches': text.get('conditions', [])}]
        if not item['relations'][0]['branches']:
            item['relations'] = []
    # Private provenance paths remain in the local data receipt, not the page.
    data.pop('metadata', None)
    data['reviewSourceKey'] = hashlib.sha256((data_root / 'data.json').read_bytes() + (data_root / 'source-view.json').read_bytes()).hexdigest()
    source = json.loads((data_root / 'source-view.json').read_text('utf-8'))
    data['templateBindings'] = build_template_bindings(data_root, data, source)
    payload = json.dumps(data, ensure_ascii=False, separators=(',', ':')).replace('<', '\\u003c')
    assert set(source['documents']) == {d['id'] for d in data['documents']}
    assert isinstance(source['anchors'], dict)
    for document in source['documents'].values():
        assert document['pages']
        for page in document['pages']:
            assert page['width'] > 0 and page['height'] > 0
            assert page['image'].startswith('/assets/')
            assert isinstance(page['words'], list)
    source_payload = json.dumps(source, ensure_ascii=False, separators=(',', ':')).replace('<', '\\u003c')
    shell = (HERE / 'prototype-shell.html').read_text('utf-8')
    logic = (HERE / 'prototype.logic.js').read_text('utf-8')
    assert shell.count('<!-- DATA_SCRIPT -->') == shell.count('<!-- SOURCE_SCRIPT -->') == shell.count('<!-- LOGIC_SCRIPT -->') == 1
    html = shell.replace('<!-- DATA_SCRIPT -->', '<script id="prototype-data" type="application/json">' + payload + '</script>')
    html = html.replace('<!-- SOURCE_SCRIPT -->', '<script id="source-data" type="application/json">' + source_payload + '</script>')
    html = html.replace('<!-- LOGIC_SCRIPT -->', '<script>\n' + logic + '\n</script>')
    modules = '\n'.join((HERE / name).read_text('utf-8') for name in ['review-fields.js', 'review-pack.js', 'template-graph.js', 'graph-overview.js', 'graph-business-view.js'])
    html = html.replace('<!-- REVIEW_MODULES -->', '<script>\n' + modules + '\n</script>')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(html, encoding='utf-8')
    print(json.dumps({'html': str(output), 'definitions': len(data['variables']),
                      'source_notes': len(data['sourceUnits']), 'business_copy': len(wording),
                      'original_pages': sum(len(d['pages']) for d in source['documents'].values())}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--data-root', type=Path, default=HERE)
    parser.add_argument('--output', type=Path, default=HERE / 'index.html')
    args = parser.parse_args()
    build(args.data_root, args.output)
