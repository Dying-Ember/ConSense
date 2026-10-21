"""Source-order list continuation proposals; no adoption or legal applicability."""
from collections import Counter
import re

from native_pdf_table_conditions import NOTE_HEADER, NOTE_ITEM, source_lines, _member


DEFINITION = re.compile(r'^\s*[A-Z][A-Z0-9.]*\s*[-\u2013\u2014]\s*[A-Za-z]')
FOLLOWING = re.compile(r'\bfollowing\s+([A-Za-z]+)\b', re.I)
CLOSURE = re.compile(r'^\s*for\s+(?:(?:any|all)\s+)?other\s+([^,]+),\s*(.+[.!])\s*$', re.I)
DIVIDER = re.compile(r'^\s*[_\-=\u2013\u2014]{3,}\s*$')
FOOTER_NUMBER = re.compile(r'(?:\bpage\s+\d+(?:\s+of\s+\d+)?|/\s*\d+)\s*$', re.I)


def _lines(source):
    return source['lines'] if 'lines' in source else source_lines(source['glyphs'])


def _signature(text):
    # Margin discovery only. Member literals and glyphs are never normalized.
    return re.sub(r'\d+', '#', ' '.join(text.split()))


def build_margin_profile(complete_section_sources):
    """Find repeated boundary lines, never an expected source page or crop."""
    headers, footers = Counter(), Counter()
    for source in complete_section_sources:
        lines = [line for line in _lines(source) if line['text'].strip()]
        scope = (source['sourceSha256'], source['sectionId'])
        if len(lines) >= 2 and DIVIDER.fullmatch(lines[1]['text']):
            headers[scope + (_signature(lines[0]['text']),)] += 1
        if lines and FOOTER_NUMBER.search(lines[-1]['text']):
            footers[scope + (_signature(lines[-1]['text']),)] += 1
    return {'headers': {key for key, count in headers.items() if count > 1},
            'footers': {key for key, count in footers.items() if count > 1}}


def _body(source, profile):
    lines = [line for line in _lines(source) if line['text'].strip()]
    ignored, header = [], None
    scope = (source['sourceSha256'], source['sectionId'])
    if len(lines) >= 2 and scope + (_signature(lines[0]['text']),) in profile['headers']:
        if DIVIDER.fullmatch(lines[1]['text']):
            header = _signature(lines[0]['text'])
            ignored.extend(lines[:2]); lines = lines[2:]
    if lines and scope + (_signature(lines[-1]['text']),) in profile['footers']:
        ignored.append(lines[-1]); lines = lines[:-1]
    return lines, ignored, header


def _words(text):
    return {word.lower().rstrip('s') for word in re.findall(r'[A-Za-z]+', text)}


def _same_column(line, left, tolerance):
    return abs(line['bbox'][0] - left) <= tolerance


def observe_continuations(previous_source, previous_logical, current_source, current_logical, margin_profile):
    """Return new current-page groups; leave both source and logical inputs intact.

    A ``continues`` edge follows source order: previous notes -> current suffix.
    Legal applicability, pixel visibility and independent source quality stay unknown.
    """
    def reject(reason):
        return {'groups': [], 'status': 'unknown_no_qualified_list_continuation',
                'rejections': [reason], 'unknown': ['cross_page_members', 'legal_applicability']}

    identity = ('sourceSha256', 'sectionId')
    page_identity = (*identity, 'originalPage', 'localPage')
    for source, logical in ((previous_source, previous_logical), (current_source, current_logical)):
        if any(key not in source or key not in logical for key in page_identity):
            return reject('source_and_logical_page_identity_binding_missing')
        if (not isinstance(source['sourceSha256'], str) or not re.fullmatch(r'[0-9a-fA-F]{64}', source['sourceSha256'])
                or not isinstance(source['sectionId'], str) or not source['sectionId'].strip()
                or any(type(source[key]) is not int or source[key] <= 0 for key in ('originalPage', 'localPage'))):
            return reject('source_page_identity_invalid')
        if any(source[key] != logical[key] for key in page_identity):
            return reject('source_and_logical_page_identity_binding_conflict')
    if any(previous_source.get(key) != current_source.get(key) for key in identity):
        return reject('different_source_or_section')
    if (current_source['localPage'] != previous_source['localPage'] + 1
            or current_source['originalPage'] != previous_source['originalPage'] + 1):
        return reject('not_adjacent_complete_source_pages')
    if any(source.get('status', 'succeeded') != 'succeeded' for source in (previous_source, current_source)):
        return reject('source_observation_failed')
    previous_body, previous_margins, previous_header = _body(previous_source, margin_profile)
    current_body, current_margins, current_header = _body(current_source, margin_profile)
    if previous_header != current_header:
        return reject('running_header_changed')
    owners = [(group, member) for group in previous_logical.get('observationGroups', {}).get('groups', [])
              for member in group['members'] if member['role'] == 'complete_adjacent_notes_context_proposal']
    if len(owners) != 1:
        return reject('unique_previous_notes_owner_not_established')
    group, owner = owners[0]
    if any(key in owner and owner[key] != previous_source[key] for key in page_identity):
        return reject('previous_member_explicit_source_identity_conflict')
    all_member_ids = [member['memberId'] for entry in previous_logical['observationGroups']['groups']
                      for member in entry['members']]
    if len(all_member_ids) != len(set(all_member_ids)) or not owner['memberId'].startswith(group['tableId'] + ':'):
        return reject('previous_member_identifier_not_unique_or_group_bound')
    source_line_index = {tuple(line['glyphIds']): line for line in _lines(previous_source)}
    if any(tuple(line['glyphIds']) not in source_line_index
           or any(line[key] != source_line_index[tuple(line['glyphIds'])][key] for key in ('text', 'bbox'))
           for line in owner['lines']):
        return reject('previous_member_lines_do_not_bind_original_source_glyphs')
    if current_logical.get('logicalTables') or current_logical.get('observationGroups', {}).get('groups'):
        return reject('new_table_or_same_page_group_on_continuation_page')
    if not previous_body or not current_body:
        return reject('empty_source_body')
    member_lines = [line for line in owner['lines'] if line['text'].strip()]
    if not member_lines or member_lines[-1]['glyphIds'] != previous_body[-1]['glyphIds']:
        return reject('previous_member_does_not_reach_last_source_body_line')
    headers = [index for index, line in enumerate(member_lines) if NOTE_HEADER.fullmatch(line['text'])]
    if len(headers) != 1:
        return reject('unique_explicit_notes_header_not_established')
    items = [(index, int(match.group(1))) for index, line in enumerate(member_lines)
             if index > headers[0] and (match := NOTE_ITEM.match(line['text']))]
    if not items or [number for _, number in items] != list(range(items[0][1], items[-1][1] + 1)):
        return reject('numbered_notes_sequence_missing_or_ambiguous')
    tail = member_lines[items[-1][0]:]
    first_definition = next((index for index, line in enumerate(tail) if DEFINITION.match(line['text'])), None)
    if first_definition is None:
        return reject('last_numbered_note_has_no_definition_list')
    intro = '\n'.join(line['text'] for line in tail[:first_definition])
    kind = FOLLOWING.search(intro)
    if not kind or not intro.rstrip().endswith(':'):
        return reject('last_numbered_note_does_not_explicitly_introduce_following_list')
    list_lines = tail[first_definition:]
    left = list_lines[0]['bbox'][0]
    tolerance = min(line['bbox'][3] - line['bbox'][1] for line in list_lines) / 2
    if tolerance <= 0:
        return reject('invalid_source_line_geometry')
    previous_definitions = 0
    for line in list_lines:
        if DEFINITION.match(line['text']):
            if not _same_column(line, left, tolerance):
                return reject('previous_definition_column_changed')
            previous_definitions += 1
        elif line['bbox'][0] <= left + tolerance or NOTE_ITEM.match(line['text']) or line['text'].rstrip().endswith(('.', ';', ':')):
            return reject('previous_definition_list_closed_or_interrupted')
    if previous_definitions < 2 or not DEFINITION.match(list_lines[-1]['text']):
        return reject('repeated_open_definition_list_not_established')
    if not DEFINITION.match(current_body[0]['text']):
        return reject('next_first_body_line_is_not_same_definition_grammar')
    selected, current_definitions, closure = [], 0, None
    for line in current_body:
        if DEFINITION.match(line['text']):
            if not _same_column(line, left, tolerance):
                return reject('continuation_definition_column_changed')
            if closure is not None:
                return reject('definition_after_explicit_closure')
            current_definitions += 1
        elif (match := CLOSURE.fullmatch(line['text'])):
            shared_scope = _words(match.group(1)) & _words(intro) - {'for', 'other', 'any', 'all', 'in', 'the', 'to', 'be'}
            if (closure is not None or not shared_scope or kind.group(1).lower().rstrip('s') not in _words(match.group(2))
                    or not _same_column(line, left, tolerance)):
                return reject('closure_scope_or_definition_kind_not_source_linked')
            closure = line
        elif (line['bbox'][0] <= left + tolerance or NOTE_ITEM.match(line['text']) or closure is not None
              or line['text'].rstrip().endswith(('.', ';', ':'))):
            return reject('continuation_list_interrupted_by_other_body')
        selected.append(line)
    # shortcut: require a source-linked closing sentence on the continuation page; widen only after complete-source negative controls.
    if closure is None or selected[-1] is not closure or not current_definitions:
        return reject('explicit_source_linked_list_closure_not_established')
    mid = group['tableId'] + ':notes-continuation:' + str(current_source['originalPage'])
    member = _member(mid, 'explicit_numbered_note_list_continuation_proposal', selected)
    member.update({key: current_source[key] for key in ('sourceSha256', 'sectionId', 'originalPage', 'localPage')})
    member['membershipStatus'] = 'joint_source_grammar_continuation_proposal_not_independently_reviewed'
    guards = {'uniquePreviousNotesMember': owner['memberId'], 'lastExplicitNoteNumber': items[-1][1],
              'literalIntro': intro, 'literalClosure': closure['text'],
              'previousDefinitionLineCount': previous_definitions, 'currentDefinitionLineCount': current_definitions,
              'definitionColumn': left, 'columnToleranceFromSourceLineHeight': tolerance,
              'previousListReachesLastBodyLine': True, 'nextListStartsFirstBodyLine': True,
              'allSuffixLinesMatchDefinitionWrapOrExplicitClosure': True,
              'margins': {'previous': previous_margins, 'current': current_margins},
              'samePageRelationsChanged': False, 'referenceAnswersRead': False}
    relation = {'relationId': group['tableId'] + ':continues:' + str(previous_source['originalPage']) + ':' + str(current_source['originalPage']),
                'type': 'continues', 'fromMemberId': owner['memberId'], 'toMemberId': mid,
                'sourceSha256': current_source['sourceSha256'], 'sectionId': current_source['sectionId'],
                'fromOriginalPage': previous_source['originalPage'], 'toOriginalPage': current_source['originalPage'],
                'fromLocalPage': previous_source['localPage'], 'toLocalPage': current_source['localPage'],
                'status': 'joint_source_grammar_continuation_proposal', 'direction': 'source_order', 'guards': guards,
                'legalApplicability': 'unknown', 'independentlySourceReviewed': False}
    return {'groups': [{'tableId': group['tableId'], 'members': [member], 'relations': [relation],
                        'wholeSourceGroupAccepted': False,
                        'unknown': ['legal_applicability', 'source_pixel_visibility', 'independent_source_review']}],
            'status': 'observed_joint_source_grammar_continuation_proposal', 'rejections': [],
            'unknown': ['legal_applicability', 'source_pixel_visibility', 'independent_source_review']}
