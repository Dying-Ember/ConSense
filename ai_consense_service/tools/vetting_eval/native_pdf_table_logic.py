"""Source-preserving logical proposals; geometry/text agreement is not quality.

No proposal is an adoption. In particular, an empty returned grid cell cannot
prove a source blank, and a complete grid cannot prove complete source members.
"""
from __future__ import annotations

import copy
import re


SCHEMA = 'consense.native-pdf-table-candidate.v1'
ITEM = re.compile(r'^\s*((?:\d+(?:\.\d+)*[.)]?)|(?:\([A-Za-z0-9]+\)))\s*$')
CONDITION = re.compile(r'^\s*(?:notes?\s*[:.]|\*|abbreviations?\s*[:.]|where\b|provided\b)', re.I)


def _literal(value):
    return value if isinstance(value, str) else ''


def _overlaps(a, b):
    return min(a[2], b[2]) > max(a[0], b[0]) and min(a[3], b[3]) > max(a[1], b[1])


def _words(value):
    # Diagnostic only; original strings and glyphs remain untouched.
    return ''.join(_literal(value).split())


def recover_table(table, observation):
    """Retain every physical cell and make reversible numbered-row proposals."""
    glyphs = {g['glyphId']: g for g in observation['glyphs']}
    cells, owned, issues = [], {}, []
    for index, origin in enumerate(table['origins']):
        cell = copy.deepcopy(origin)
        cell['physicalCellId'] = table['tableId'] + ':cell:' + str(index)
        ids = cell.get('glyphIds', [])
        if any(gid not in glyphs for gid in ids):
            raise ValueError('Cell points outside complete source glyph observation')
        for gid in ids:
            owned.setdefault(gid, []).append(cell['physicalCellId'])
        literal = ''.join(_literal(glyphs[gid]['text']) for gid in ids)
        text = _literal(cell.get('text'))
        overlaps = cell.get('glyphOverlapIds', [])
        cell.update(sourceGlyphText=literal,
                    textAgreement='whitespace_diagnostic_only' if _words(literal) == _words(text) else 'disagreement',
                    valueState='observed_literal' if text.strip() else 'unknown_source_blank',
                    mergeStatus='provider_geometry_proposal' if cell.get('spanKnown') else 'unknown',
                    sourceMembershipStatus='geometric_candidate_not_source_reviewed')
        if text.strip() and not ids or _words(literal) != _words(text):
            issues.append({'kind': 'cell_text_source_disagreement', 'physicalCellId': cell['physicalCellId']})
        if set(overlaps) - set(ids):
            issues.append({'kind': 'glyph_crosses_cell_boundary', 'physicalCellId': cell['physicalCellId'],
                           'glyphIds': sorted(set(overlaps) - set(ids))})
        cells.append(cell)
    box = table.get('bbox')
    inside = {gid for gid, glyph in glyphs.items() if box is not None and _overlaps(box, glyph['bbox'])}
    missing = sorted(inside - set(owned))
    duplicate = sorted(gid for gid, owners in owned.items() if len(owners) != 1)
    rows = {}
    for cell in cells:
        rows.setdefault(cell['row'], []).append(cell)
    for row in rows.values():
        row.sort(key=lambda cell: cell['col'])
    # A column is a numbering proposal only if it has multiple explicit item
    # labels; no fixed column number, target value, page or bbox is accepted.
    columns = {}
    for row_id, row in rows.items():
        for cell in row:
            match = ITEM.fullmatch(_literal(cell.get('text')))
            if match:
                columns.setdefault(cell['col'], []).append((row_id, match.group(1)))
    numbered = [col for col, labels in columns.items() if len(labels) >= 2]
    numbering_col = numbered[0] if len(numbered) == 1 else None
    logical, active, first_item = [], None, None
    for row_id, row in sorted(rows.items()):
        label_cell = next((cell for cell in row if cell['col'] == numbering_col), None)
        label = ITEM.fullmatch(_literal(label_cell.get('text'))) if label_cell else None
        if label:
            first_item = row_id if first_item is None else first_item
            active = {'logicalRowId': table['tableId'] + ':logical:' + str(row_id),
                      'itemLiteral': label.group(1), 'physicalRows': [row_id],
                      'role': 'numbered_row_proposal', 'membershipStatus': 'unknown_requires_source_review'}
            logical.append(active)
        elif (active is not None and label_cell is not None and not _literal(label_cell.get('text')).strip()
              and row_id == active['physicalRows'][-1] + 1 and any(_literal(c.get('text')).strip() for c in row)):
            active['physicalRows'].append(row_id)
        else:
            active = None
            logical.append({'logicalRowId': table['tableId'] + ':logical:' + str(row_id),
                            'itemLiteral': None, 'physicalRows': [row_id], 'role': 'unresolved_row',
                            'membershipStatus': 'unknown_requires_source_review'})
    header_rows = [row for row in sorted(rows) if first_item is not None and row < first_item]
    for row in logical:
        if all(r in header_rows for r in row['physicalRows']):
            row['role'] = 'header_proposal'
    return {'schema': SCHEMA, 'tableId': table['tableId'], 'cells': cells, 'logicalRows': logical,
            'headerRows': header_rows, 'numberingColumnProposal': numbering_col,
            'coverage': {'required': sorted(inside), 'present': sorted(set(owned) & inside),
                         'missing': missing, 'duplicate': duplicate,
                         'outsideTableGlyphIds': sorted(set(glyphs) - inside),
                         'wholeSourceMembership': 'unknown', 'wholeTableAccepted': False},
            'issues': issues, 'publicationStatus': 'unpublished_candidate',
            'conditions': {'required': 'unknown', 'present': [], 'missing': [], 'unknown': ['source_boundary', 'notes_owner', 'parent_clause', 'cross_page_members']},
            'providerTablePreserved': True, 'candidateFusion': False}


def recover_page(tables, observation):
    proposals = [recover_table(table, observation) for table in tables]
    # Preserve entire outside-source lines as possible conditions, never append
    # them to the nearest table or manufacture a condition from a keyword.
    outside = set(g['glyphId'] for g in observation['glyphs'])
    for proposal in proposals:
        outside -= set(proposal['coverage']['required'])
    lines = []
    for glyph in observation['glyphs']:
        if glyph['glyphId'] not in outside:
            continue
        box = glyph['bbox']
        current = next((line for line in lines if abs(line['top'] - box[1]) <= max(1, (box[3] - box[1]) / 4)), None)
        if current is None:
            current = {'top': box[1], 'glyphs': []}; lines.append(current)
        current['glyphs'].append(glyph)
    candidates = []
    for line in sorted(lines, key=lambda line: line['top']):
        ordered = sorted(line['glyphs'], key=lambda glyph: glyph['bbox'][0])
        literal = ''.join(_literal(glyph['text']) for glyph in ordered)
        if CONDITION.search(literal):
            candidates.append({'text': literal, 'glyphIds': [g['glyphId'] for g in ordered],
                               'role': 'condition_line_proposal', 'owner': 'unknown',
                               'lineAssembly': 'geometry_proposal_original_glyphs_retained'})
    return {'schema': SCHEMA, 'tables': proposals, 'conditionCandidates': candidates,
            'unassignedSourceGlyphIds': sorted(outside), 'completeSourceGroupAccepted': False,
            'quality': 'unknown_not_independently_source_scored'}
