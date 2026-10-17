"""Authored offline view checks; no real source or model invocation."""
import copy
from html.parser import HTMLParser
import json
from pathlib import Path
import tempfile
import unittest

from experiment_log import append_record
from render_iteration_log import render_registry


class DefaultView(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.folded = 0
        self.text = []
        self.open_details = []

    def handle_starttag(self, tag, attrs):
        if tag == 'details':
            self.open_details.append('open' in dict(attrs))
            self.folded += 1

    def handle_endtag(self, tag):
        if tag == 'details':
            self.folded -= 1

    def handle_data(self, data):
        if not self.folded:
            self.text.append(data)


class IterationLogTest(unittest.TestCase):
    def test_failed_history_unknown_scores_and_safe_links_survive_root_audit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / 'evidence with space.txt'
            evidence.write_text('authored evidence', encoding='utf-8')
            original = {'schemaVersion': 1, 'experimentId': 'I01', 'status': 'failed',
                        'scope': {'source': '<script>unsafe</script>', 'version': 'authored-v1', 'range': [1, 2]},
                        'hypothesis': 'Find one missing observation', 'changedFactors': ['none; audit only'],
                        'parameters': {'code': 'authored', 'model': 'frozen'}, 'inputFingerprints': {'source': 'authored'},
                        'measurements': {'tests': None, 'newCalls': 0, 'replay': 1, 'cache': 0, 'cost': {'seconds': 1}},
                        'evaluation': {'score': None, 'denominator': 57, 'oldCorrect': None, 'unknown': 57,
                                       'failure': 'authored timeout', 'rollback': 'not changed', 'rootVerdict': None},
                        'artifacts': [{'path': str(evidence)}, {'url': 'javascript:alert(1)'},
                                      {'url': 'https://example.invalid/?x="<script>', 'label': 'authored reference'}]}
            proof = append_record(root, original, 'finished')
            original_bytes = Path(proof['path']).read_bytes()
            render_registry(root)
            legacy = DefaultView()
            legacy.feed((root / 'index.html').read_text(encoding='utf-8'))
            self.assertIn('本轮成果待补充', ''.join(legacy.text))
            self.assertNotIn('authored timeout', ''.join(legacy.text))
            audited = copy.deepcopy(original)
            audited['evaluation']['rootVerdict'] = '需补证：来源未验收'
            append_record(root, audited, 'independent_audit')
            human = copy.deepcopy(audited)
            human['humanSummary'] = {'document': '示例合同 <script>unsafe</script>',
                                     'problem': '原有顺序丢失', 'approach': '保留原始块顺序',
                                     'outcome': '离线对照已保留顺序', 'remaining': '真实来源尚未复测',
                                     'next': '由审核人检查原页后决定'}
            append_record(root, human, 'human_summary')
            result = render_registry(root)
            self.assertEqual(1, result['iterations'])
            self.assertEqual(original_bytes, Path(proof['path']).read_bytes())
            rendered = (root / 'index.html').read_text(encoding='utf-8')
            for value in ('authored timeout', 'not changed', 'denominator', 'oldCorrect', 'newCalls', 'replay', 'cache', '需补证'):
                self.assertIn(value, rendered)
            self.assertIn('&quot;score&quot;: null', rendered)
            self.assertIn('&quot;unknown&quot;: 57', rendered)
            self.assertIn('evidence%20with%20space.txt', rendered)
            self.assertIn('independent_audit', rendered)
            self.assertIn('finished', rendered)
            self.assertNotIn('<script>', rendered)
            self.assertNotIn('href="javascript:', rendered)
            self.assertIn('&lt;script&gt;', rendered)
            self.assertIn('href="#I01"', rendered)
            self.assertEqual(['index.html'], [path.name for path in root.glob('*.html')])
            visible = DefaultView()
            visible.feed(rendered)
            text = ''.join(visible.text)
            for value in human['humanSummary'].values():
                self.assertIn(value, text)
            self.assertLess(text.index('需补证'), text.index('作用文件', text.index('审核结论')))
            self.assertNotIn('authored timeout', text)
            self.assertNotIn('inputFingerprints', text)
            self.assertNotIn('denominator', text)
            self.assertFalse(any(visible.open_details))
            self.assertIn('离线对照已保留顺序', text)
            self.assertNotIn('authored-v1', text)
            self.assertEqual(original, json.loads(original_bytes)['record'])

            second = copy.deepcopy(human)
            second['experimentId'] = 'I02'
            second['humanSummary']['outcome'] = '第二轮追加成果'
            second['ledgerOverview'] = {'achieved': '已读对示例 <19/19>',
                                        'inProgress': '正在验证正常保存',
                                        'remaining': '完整语义需要单独验收'}
            append_record(root, second, 'human_summary')
            self.assertEqual(2, render_registry(root)['iterations'])
            ledger = (root / 'index.html').read_text(encoding='utf-8')
            self.assertIn('id="I01"', ledger)
            self.assertIn('id="I02"', ledger)
            self.assertIn('第二轮追加成果', ledger)
            self.assertIn('已读对示例 &lt;19/19&gt;', ledger)
            self.assertLess(ledger.index('已经做成'), ledger.index('轮次目录'))
            self.assertIn('正在验证正常保存', ledger)
            self.assertIn('完整语义需要单独验收', ledger)
            self.assertEqual(['index.html'], [path.name for path in root.glob('*.html')])

            withdrawn = copy.deepcopy(second)
            withdrawn['ledgerOverview'] = None
            withdrawn['evaluation']['rootVerdict'] = '撤回概述中的成绩，按本次审核结论处理'
            append_record(root, withdrawn, 'root_correction')
            render_registry(root)
            corrected = (root / 'index.html').read_text(encoding='utf-8').split('<nav', 1)[0]
            self.assertIn('撤回概述中的成绩', corrected)
            self.assertNotIn('已读对示例', corrected)


if __name__ == '__main__':
    unittest.main()
