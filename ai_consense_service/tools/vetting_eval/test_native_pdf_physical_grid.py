"""Closed original grid compatibility; no numbered-column or header inference."""
import copy
import sys
from pathlib import Path
import unittest

TOOLS=Path(__file__).resolve().parents[4]/'ai_consense_service/tools/vetting_eval'
sys.path.append(str(TOOLS))
from native_pdf_table_logic import recover_table, recover_page


def plain_grid():
    text=[['Category','Term'],['a. First item\non two lines','Ten years'],['b. Other item','Five years']]
    xs,ys=[0.,100.,200.],[0.,20.,40.,60.]
    glyphs,cells=[],[]
    for row,values in enumerate(text):
        for col,value in enumerate(values):
            ids=[]
            for index,char in enumerate(value):
                gid=len(glyphs);ids.append(gid)
                glyphs.append({'glyphId':gid,'text':char,'bbox':[xs[col]+1+index,ys[row]+2,xs[col]+2+index,ys[row]+10]})
            cells.append({'row':row,'col':col,'rowSpan':1,'colSpan':1,'text':value,
                          'bbox':[xs[col],ys[row],xs[col+1],ys[row+1]],'glyphIds':ids,
                          'glyphOverlapIds':ids[:],'spanKnown':True})
    table={'tableId':'unrelated-closed-grid','origins':cells,'bbox':[0.,0.,200.,60.],
           'rowCount':3,'colCount':2,'gridXEdges':xs,'gridYEdges':ys,'rawGrid':copy.deepcopy(text)}
    return table,{'glyphs':glyphs}


class ClosedPhysicalGridTests(unittest.TestCase):
    def test_complete_grid_preserves_all_cells_without_a_numbering_column(self):
        table,source=plain_grid();before=copy.deepcopy((table,source))
        result=recover_table(table,source)
        grid=result['logicalTable']
        self.assertIsNotNone(grid)
        self.assertEqual((3,2),(grid['rowCount'],grid['colCount']))
        self.assertIsNone(result['numberingColumnProposal'])
        self.assertEqual([],result['headerRows'])
        self.assertEqual([[0],[1],[2]],grid['physicalRows'])
        self.assertEqual([cell['text'] for cell in table['origins']],[cell['text'] for cell in grid['origins']])
        self.assertEqual([cell['bbox'] for cell in table['origins']],[cell['bbox'] for cell in grid['origins']])
        self.assertEqual([cell['glyphIds'] for cell in table['origins']],[cell['glyphIds'] for cell in grid['origins']])
        self.assertEqual([],grid['excludedPhysicalCellIds'])
        self.assertFalse(grid['completeTableGroupAccepted'])
        self.assertFalse(result['coverage']['wholeTableAccepted'])
        self.assertEqual(before,(table,source))

    def test_original_empty_cell_retains_unknown_blank_meaning(self):
        table,source=plain_grid();cell=table['origins'][-1]
        removed=set(cell['glyphIds']);source['glyphs']=[g for g in source['glyphs'] if g['glyphId'] not in removed]
        cell.update(text='',glyphIds=[],glyphOverlapIds=[]);table['rawGrid'][-1][-1]=''
        grid=recover_table(table,source)['logicalTable']
        self.assertIsNotNone(grid)
        self.assertEqual('',grid['origins'][-1]['text'])
        self.assertEqual('unknown_source_blank',grid['origins'][-1]['valueState'])

    def test_incomplete_or_inconsistent_grid_never_gets_a_whole_grid_fallback(self):
        mutations=[lambda t,s:t['origins'].pop(),
                   lambda t,s:t['origins'][0].update(spanKnown=False),
                   lambda t,s:t['origins'][0].update(rowSpan=2),
                   lambda t,s:t.update(rowCount=True),
                   lambda t,s:t['gridXEdges'].__setitem__(1,False),
                   lambda t,s:t['gridYEdges'].__setitem__(1,t['gridYEdges'][0]),
                   lambda t,s:t['gridYEdges'].__setitem__(1,float('nan')),
                   lambda t,s:t['rawGrid'][0].pop(),
                   lambda t,s:t['rawGrid'][0].__setitem__(0,'different'),
                   lambda t,s:t['origins'][0].update(text='unread'),
                   lambda t,s:t['origins'][1].update(col=0),
                   lambda t,s:t['origins'][0]['bbox'].__setitem__(2,101.),
                   lambda t,s:t['origins'][0]['bbox'].__setitem__(0,False),
                   lambda t,s:t['origins'][0].update(rowSpan=True),
                   lambda t,s:t['bbox'].__setitem__(0,False),
                   lambda t,s:t.pop('gridXEdges'),
                   lambda t,s:t.update(extractionError={'type':'Failed'})]
        for index,mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                table,source=plain_grid();mutate(table,source)
                self.assertIsNone(recover_table(table,source)['logicalTable'])

    def test_grid_does_not_grant_source_group_or_cross_page_authority(self):
        table,source=plain_grid()
        result=recover_page([table],source)
        self.assertEqual(1,len(result['logicalTables']))
        self.assertFalse(result['completeSourceGroupAccepted'])
        self.assertFalse(result['observationGroups']['groups'][0]['wholeSourceGroupAccepted'])
        self.assertIn('cross_page_members',result['observationGroups']['groups'][0]['unknown'])


if __name__=='__main__':unittest.main(verbosity=2)
