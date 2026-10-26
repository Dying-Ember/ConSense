"""Authored merged-grid topology controls, not source-quality acceptance."""
import copy
import unittest

from native_pdf_table_logic import recover_page, recover_table


def merged_grid():
    xs, ys = [0., 100., 200., 300., 400.], [0., 20., 40., 60., 80.]
    declarations = [(0, 0, 1, 1, 'Kind'), (0, 1, 1, 1, 'Method'),
                    (0, 2, 1, 2, 'Shared \uf0a3 \U0001f600'),
                    (1, 0, 2, 1, 'Alpha'), (1, 1, 1, 1, 'First'),
                    (1, 2, 1, 1, 'Low'), (1, 3, 1, 1, 'High'),
                    (2, 1, 1, 1, 'Second'), (2, 2, 2, 2, 'Common\ncondition'),
                    (3, 0, 1, 1, ''), (3, 1, 1, 1, 'None')]
    glyphs, cells, raw = [], [], [[None] * 4 for _ in range(4)]
    slots = {(r, c): {'row': r, 'col': c, 'bbox': None,
                      'nativeCellPresent': False, 'text': None} for r in range(4) for c in range(4)}
    for row, col, rs, cs, text in declarations:
        box = [xs[col], ys[row], xs[col + cs], ys[row + rs]]
        raw_box = [box[0] + 17., box[1] + 29., box[2] + 17., box[3] + 29.]
        ids = []
        for index, char in enumerate(text):
            gid = len(glyphs); ids.append(gid)
            glyphs.append({'glyphId': gid, 'text': char,
                           'bbox': [box[0] + index + 1, box[1] + 2, box[0] + index + 2, box[1] + 10]})
        cells.append({'row': row, 'col': col, 'rowSpan': rs, 'colSpan': cs, 'text': text,
                      'bbox': box, 'rawBbox': raw_box, 'providerRow': row, 'providerCol': col,
                      'glyphIds': ids, 'glyphOverlapIds': ids[:], 'spanKnown': True})
        raw[row][col] = text
        slots[row, col].update(bbox=raw_box[:], nativeCellPresent=True, text=text)
    return {'tableId': 'authored-merged-grid', 'rowCount': 4, 'colCount': 4,
            'gridXEdges': xs, 'gridYEdges': ys, 'bbox': [0., 0., 400., 80.],
            'origins': cells, 'rawGrid': raw, 'rawSlots': list(slots.values())}, {'glyphs': glyphs}


class MergedPhysicalGridTests(unittest.TestCase):
    def test_complete_tiled_grid_preserves_each_original_once_with_its_spans_and_literal(self):
        table, source = merged_grid(); before = copy.deepcopy((table, source))
        result = recover_table(table, source); grid = result['logicalTable']
        self.assertIsNotNone(grid)
        self.assertEqual((4, 4), (grid['rowCount'], grid['colCount']))
        self.assertEqual(11, len(grid['origins']))
        for original, cell in zip(table['origins'], grid['origins']):
            for key in original:
                self.assertEqual(original[key], cell[key], key)
            self.assertEqual(1, len(cell['physicalCellIds']))
        self.assertEqual('none_original_physical_cell_literals', grid['textTransform'])
        self.assertEqual([], grid['excludedPhysicalCellIds'])
        self.assertEqual('', grid['origins'][-2]['text'])
        self.assertEqual('unknown_source_blank', grid['origins'][-2]['valueState'])
        self.assertEqual([], result['issues'])
        self.assertEqual([], result['coverage']['missing'])
        self.assertEqual([], result['coverage']['duplicate'])
        self.assertFalse(grid['completeTableGroupAccepted'])
        self.assertEqual(before, (table, source))

    def test_merged_grid_does_not_invent_headers_or_cross_page_membership(self):
        table, source = merged_grid(); result = recover_page([table], source)
        self.assertEqual(1, len(result['logicalTables']))
        self.assertEqual([], result['tables'][0]['headerRows'])
        self.assertIsNone(result['tables'][0]['numberingColumnProposal'])
        self.assertEqual([[0], [1], [2], [3]], result['logicalTables'][0]['physicalRows'])
        self.assertFalse(result['completeSourceGroupAccepted'])
        self.assertFalse(result['observationGroups']['groups'][0]['wholeSourceGroupAccepted'])
        self.assertIn('cross_page_members', result['observationGroups']['groups'][0]['unknown'])

    def test_invalid_topology_geometry_and_raw_binding_never_publish_a_grid(self):
        mutations = {
            'hole_without_glyph_loss': lambda t, s: t['origins'].pop(-2),
            'overlap': lambda t, s: t['origins'][0].update(colSpan=2, bbox=[0., 0., 200., 20.]),
            'uncertain_span': lambda t, s: t['origins'][2].update(spanKnown=False),
            'zero_row_span': lambda t, s: t['origins'][2].update(rowSpan=0),
            'negative_col_span': lambda t, s: t['origins'][2].update(colSpan=-1),
            'boolean_span': lambda t, s: t['origins'][2].update(colSpan=True),
            'fractional_span': lambda t, s: t['origins'][2].update(rowSpan=1.0),
            'outside_extent': lambda t, s: t['origins'][2].update(colSpan=3),
            'negative_origin': lambda t, s: t['origins'][2].update(col=-1),
            'boolean_origin': lambda t, s: t['origins'][0].update(row=False),
            'duplicate_origin': lambda t, s: t['origins'].append(copy.deepcopy(t['origins'][-2])),
            'nonfinite_edge': lambda t, s: t['gridXEdges'].__setitem__(1, float('inf')),
            'nonfinite_y_edge': lambda t, s: t['gridYEdges'].__setitem__(1, float('nan')),
            'nonincreasing_edge': lambda t, s: t['gridYEdges'].__setitem__(1, 0.),
            'boolean_edge': lambda t, s: t['gridXEdges'].__setitem__(0, False),
            'wrong_table_box': lambda t, s: t['bbox'].__setitem__(2, 401.),
            'nonfinite_cell_box': lambda t, s: t['origins'][2]['bbox'].__setitem__(2, float('inf')),
            'wrong_span_endpoint': lambda t, s: t['origins'][2]['bbox'].__setitem__(2, 399.),
            'missing_raw_slots': lambda t, s: t.pop('rawSlots'),
            'missing_raw_slot': lambda t, s: t['rawSlots'].pop(),
            'duplicate_raw_slot': lambda t, s: t['rawSlots'].__setitem__(-1, copy.deepcopy(t['rawSlots'][0])),
            'boolean_raw_position': lambda t, s: t['rawSlots'][0].update(row=False),
            'raw_position_outside': lambda t, s: t['rawSlots'][0].update(row=4),
            'covered_raw_text': lambda t, s: t['rawGrid'][0].__setitem__(3, ''),
            'covered_slot_text': lambda t, s: t['rawSlots'][3].update(text=''),
            'covered_slot_present': lambda t, s: t['rawSlots'][3].update(nativeCellPresent=True),
            'covered_slot_box': lambda t, s: t['rawSlots'][3].update(bbox=[0., 0., 1., 1.]),
            'missing_explicit_covered_box': lambda t, s: t['rawSlots'][3].pop('bbox'),
            'null_origin_literal': lambda t, s: t['rawGrid'][3].__setitem__(0, None),
            'changed_origin_literal': lambda t, s: t['rawGrid'][0].__setitem__(2, 'replacement'),
            'changed_slot_literal': lambda t, s: t['rawSlots'][2].update(text='replacement'),
            'origin_slot_absent': lambda t, s: t['rawSlots'][2].update(nativeCellPresent=False),
            'origin_slot_box_disagrees': lambda t, s: t['rawSlots'][2]['bbox'].__setitem__(2, 416.),
            'nonfinite_raw_box': lambda t, s: t['origins'][2]['rawBbox'].__setitem__(2, float('inf')),
            'missing_provider_row': lambda t, s: t['origins'][2].pop('providerRow'),
            'wrong_provider_column': lambda t, s: t['origins'][2].update(providerCol=3),
            'boolean_provider_row': lambda t, s: t['origins'][2].update(providerRow=False),
            'extraction_error': lambda t, s: t.update(extractionError={'type': 'Failure'}),
        }
        for name, mutate in mutations.items():
            with self.subTest(case=name):
                table, source = merged_grid(); mutate(table, source)
                self.assertIsNone(recover_table(table, source)['logicalTable'])

    def test_source_glyph_disagreement_missing_crossing_and_duplicate_gates_stay_closed(self):
        mutations = {
            'text_disagreement': lambda t, s: t['origins'][2].update(text='replacement'),
            'missing_glyph': lambda t, s: t['origins'][2]['glyphIds'].pop(),
            'duplicate_glyph': lambda t, s: t['origins'][0]['glyphIds'].append(t['origins'][2]['glyphIds'][0]),
            'crossing_glyph': lambda t, s: t['origins'][0]['glyphOverlapIds'].append(t['origins'][2]['glyphIds'][0]),
        }
        for name, mutate in mutations.items():
            with self.subTest(case=name):
                table, source = merged_grid(); mutate(table, source)
                self.assertIsNone(recover_table(table, source)['logicalTable'])


if __name__ == '__main__': unittest.main()
