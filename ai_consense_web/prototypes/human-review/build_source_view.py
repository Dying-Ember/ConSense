"""Build private page images and native word coordinates for the review prototype."""
import argparse
import hashlib
import json
import re
import unicodedata
from collections import Counter
from pathlib import Path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_image_name(doc_id, visual_sha256, page):
    return f'source-{doc_id}-{visual_sha256}-{page:03d}.png'


def normalized(text):
    return ''.join(c for c in unicodedata.normalize('NFKD', text).casefold() if c.isalnum())


def locate(quote, pages, hint, hint_position=None):
    """Only label a complete normalized native-text match as exact."""
    needle = normalized(quote)
    if not needle:
        return {'status': 'unresolved', 'ranges': [], 'pageHint': hint}
    text, positions = [], []
    for page_number, page in enumerate(pages, 1):
        for word_index, word in enumerate(page['words']):
            part = normalized(word['text'])
            text.append(part)
            positions.extend([(page_number, word_index)] * len(part))
    stream = ''.join(text)
    starts = []
    offset = stream.find(needle)
    while offset >= 0:
        starts.append(offset)
        offset = stream.find(needle, offset + 1)
    if hint:
        starts = [i for i in starts if positions[i][0] == hint]
        if len(starts) > 1 and hint_position:
            def distance(i):
                page_number, word_index = positions[i]
                word = pages[page_number - 1]['words'][word_index]
                return (word['x'] - hint_position['x']) ** 2 + (word['y'] - hint_position['y']) ** 2
            ranked = sorted(starts, key=distance)
            if distance(ranked[0]) < distance(ranked[1]):
                starts = ranked[:1]
    if len(starts) != 1:
        return {'status': 'ambiguous' if starts else 'unresolved', 'ranges': [], 'pageHint': hint}
    matched = positions[starts[0]:starts[0] + len(needle)]
    ranges = []
    for page_number, word_index in matched:
        if ranges and ranges[-1]['page'] == page_number:
            ranges[-1]['end'] = word_index + 1
        else:
            ranges.append({'page': page_number, 'start': word_index, 'end': word_index + 1})
    return {'status': 'exact', 'ranges': ranges, 'pageHint': ranges[0]['page']}


def build(workspace, data_root):
    import pdfplumber

    sample = [{'words': [{'text': 'Alpha', 'x': 5, 'y': 10}, {'text': 'Beta', 'x': 15, 'y': 10}]}]
    assert locate('Alpha Beta', sample, 1)['ranges'] == [{'page': 1, 'start': 0, 'end': 2}]
    assert locate('Alpha Beta', sample, 2)['status'] == 'unresolved'
    assert locate('Gamma', sample, 1)['status'] == 'unresolved'
    assert locate('Alpha', sample + sample, None)['status'] == 'ambiguous'
    assert locate('Beta Alpha', sample + sample, 1)['ranges'] == [
        {'page': 1, 'start': 1, 'end': 2}, {'page': 2, 'start': 0, 'end': 1}]
    preview_root = workspace / '.local/state/renderer-acceptance-20261008'
    acceptance = json.loads((preview_root / 'pdf-api-acceptance.json').read_text('utf-8'))
    source_map = json.loads((workspace / '.local/drafting-guidance-stability-20261015/source-map.json').read_text('utf-8'))
    source_pdf = workspace / '.local/scc-pdf-variable-extraction-20261015/source-manifest.json'
    pdf_source = json.loads(source_pdf.read_text('utf-8'))
    data = json.loads((data_root / 'data.json').read_text('utf-8'))
    assets = data_root / 'assets'
    assets.mkdir(parents=True, exist_ok=True)
    specs = []
    for key, doc_id in [('NTT', 'ntt'), ('SCT', 'sct'), ('SCC', 'scc-word')]:
        record = next(x for x in acceptance['checks'] if x['fileKey'] == key)
        original = Path(source_map[key]['source_path'])
        preview = preview_root / f'{key}-source.pdf'
        assert digest(original) == source_map[key]['source_sha256'] == record['source']['sha256']
        assert digest(preview) == record['sourcePreview']['sha256']
        bindings = json.loads((preview_root / f'{key}-source-bindings.json').read_text('utf-8'))
        assert bindings['sourceSha256'] == record['source']['sha256']
        assert bindings['pdfSha256'] == record['sourcePreview']['sha256']
        paragraph_positions = {x['sourceParagraphOrdinal']: x.get('geometry', {})
                               for x in bindings['bindings']}
        specs.append((doc_id, 'word-preview', preview, paragraph_positions, original, record['source']['sha256']))
    pdf_original = Path(pdf_source['sourcePath'])
    assert digest(pdf_original) == pdf_source['sha256']
    specs.append(('scc-pdf', 'pdf-original', pdf_original, {}, pdf_original, pdf_source['sha256']))
    view = {'documents': {}, 'anchors': {}}
    manifest, receipt = {}, {'documents': {}, 'anchorCounts': {}}
    for doc_id, kind, pdf_path, paragraph_positions, original, source_sha in specs:
        pages = []
        visual_sha = digest(pdf_path)
        with pdfplumber.open(pdf_path) as pdf:
            for number, page in enumerate(pdf.pages, 1):
                image_path = assets / source_image_name(doc_id, visual_sha, number)
                if not image_path.exists():
                    page.to_image(resolution=110).save(image_path)
                image_url = f'/assets/{image_path.name}'
                manifest[image_url] = str(image_path.resolve())
                native_words = page.extract_words(use_text_flow=True, keep_blank_chars=False)
                words = [{'text': w['text'], 'x': round(w['x0'], 3), 'y': round(w['top'], 3),
                          'w': round(w['x1'] - w['x0'], 3), 'h': round(w['bottom'] - w['top'], 3)}
                         for w in native_words]
                assert all(w['w'] >= 0 and w['h'] >= 0 for w in words)
                pages.append({'width': page.width, 'height': page.height, 'image': image_url, 'words': words})
                if number % 15 == 0:
                    print(f'{doc_id}: {number}/{len(pdf.pages)} pages', flush=True)
        view['documents'][doc_id] = {'kind': kind, 'pages': pages}
        for unit in (x for x in data['sourceUnits'] if x['docId'] == doc_id):
            numbers = re.findall(r'\d+', unit['locationEn'].split('Paragraphs ')[-1]) if kind == 'word-preview' else []
            hint_position = next((paragraph_positions[int(p)] for p in numbers
                                  if paragraph_positions.get(int(p), {}).get('pageNumber')), None)
            hint = hint_position['pageNumber'] if hint_position else None
            if kind == 'pdf-original':
                page_match = re.search(r'pages?\s+(\d+)', unit['locationEn'], re.I)
                hint = int(page_match.group(1)) if page_match else None
            anchor = locate(unit['quote'], pages, hint, hint_position)
            anchor['docId'] = doc_id
            view['anchors'][unit['id']] = anchor
        counts = Counter(a['status'] for a in view['anchors'].values() if a['docId'] == doc_id)
        receipt['anchorCounts'][doc_id] = dict(counts)
        receipt['documents'][doc_id] = {'kind': kind, 'pages': len(pages), 'words': sum(len(p['words']) for p in pages),
                                        'original': str(original), 'originalSha256': source_sha,
                                        'visualPdf': str(pdf_path), 'visualPdfSha256': visual_sha}
        print(f'{doc_id}: {len(pages)} pages; anchors {dict(counts)}', flush=True)
    assert set(view['documents']) == {'ntt', 'sct', 'scc-word', 'scc-pdf'}
    assert set(view['anchors']) == {x['id'] for x in data['sourceUnits']}
    for anchor in view['anchors'].values():
        for r in anchor['ranges']:
            assert 0 <= r['start'] < r['end'] <= len(view['documents'][anchor['docId']]['pages'][r['page'] - 1]['words'])
    receipt['limitations'] = ['Word pages are source-bound historical visual previews, not native Word pagination certification.',
                              'Only full normalized native-text matches are labelled exact; unresolved notes remain navigable by existing source location.',
                              'No OCR, model calls or production parsing. Original files remain unchanged.']
    for name, value in [('source-view.json', view), ('asset-manifest.json', manifest), ('source-view-receipt.json', receipt)]:
        (data_root / name).write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')), 'utf-8')
    print(json.dumps({'pages': len(manifest), 'anchorCounts': receipt['anchorCounts']}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--data-root', type=Path, required=True)
    args = parser.parse_args()
    build(args.workspace.resolve(), args.data_root.resolve())
