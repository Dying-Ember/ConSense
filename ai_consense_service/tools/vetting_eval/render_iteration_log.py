"""Render one OER HTML ledger from the existing immutable experiment registry.

--input registers an experiment_log v1 JSON snapshot before rendering. Use an
experimentId such as I01; later root audits append a new snapshot under that ID.
Scope holds source/version/range, parameters holds the single change and runtime
identities, measurements holds tests/calls/cost, and evaluation holds scores,
denominators, oldCorrect, unknowns, rootVerdict, failure and rollback. Optional
humanSummary supplies document/problem/approach/outcome/remaining/next prose;
technical fields and immutable history are collapsed by default. Evidence paths
Optional ledgerOverview on a root-reviewed record describes achieved work,
current work and remaining acceptance for the whole ledger.
are absolute or relative to --root. No models,
source documents or services are accessed; links do not verify acceptance.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from html import escape
import json
import os
from pathlib import Path
from urllib.parse import quote, urlsplit

from experiment_log import append_record, atomic_write, events, registry_lock, validate_record


DEFAULT_ROOT = Path(__file__).resolve().parents[3] / '.local/oer-recognition-evolution-20261015/logs'
SECTIONS = [('scope', '来源／版本／范围'), ('hypothesis', '假设与预期证据'),
            ('changedFactors', '单因素变化'), ('parameters', '代码／模型身份与变化'),
            ('measurements', '测试／新调用／replay／cache／成本'),
            ('evaluation', '同分母评分／旧正确／未知／root 判定／失败与回退')]
STYLE = '''body{font:16px/1.6 system-ui,sans-serif;max-width:1200px;margin:2rem auto;padding:0 1rem;color:#202b36}
table{border-collapse:collapse;width:100%;margin:1rem 0}th,td{border:1px solid #ccd5df;padding:.7rem;text-align:left;vertical-align:top;overflow-wrap:anywhere}
th{background:#eef3f8}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f5f7;padding:1rem}
a{color:#075bab}details{margin:1rem 0}summary{cursor:pointer;font-weight:600}
.verdict{border-left:4px solid #075bab;padding:.7rem 1rem;background:#eef5fc;margin:1rem 0}
.verdict h3,.summary h3{font-size:1.1rem;margin:0 0 .5rem}.verdict p{margin:0}
.verdict p+h3{margin-top:1rem}
.summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:1rem;margin:1.5rem 0}
.summary article{border:1px solid #ccd5df;border-radius:6px;padding:1rem;background:#fafcfe;overflow-wrap:anywhere}
.summary p{white-space:pre-line}.summary strong{display:block;margin-top:1rem}
.technical{border-top:1px solid #ccd5df;padding-top:1rem}.notice{color:#596978}
@media(max-width:900px){.summary{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:560px){.summary{grid-template-columns:1fr}}'''


def display(value):
    if value is None:
        return '未记录（null）'
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)


def page(title, body):
    return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            f'<title>{escape(title)}</title><style>{STYLE}</style><body><h1 id="top">{escape(title)}</h1>'
            + body + '</body></html>')


def human_summary(record):
    raw = record.get('humanSummary')
    raw = raw if isinstance(raw, dict) else {}
    defaults = {'document': '涉及文件待补充', 'problem': '本轮问题说明待补充',
                'approach': '处理办法待补充', 'outcome': '本轮成果待补充，暂不据技术记录推定通过',
                'remaining': '剩余问题待补充', 'next': '下一步待审核确认'}
    return {key: raw[key].strip() if isinstance(raw.get(key), str) and raw[key].strip() else fallback
            for key, fallback in defaults.items()}


def root_verdict(record):
    verdict = record['evaluation'].get('rootVerdict')
    return verdict.strip() if isinstance(verdict, str) and verdict.strip() else '审核结论待补充；暂不判定通过。'


def ledger_overview(record):
    overview = record.get('ledgerOverview')
    if not isinstance(overview, dict):
        return '<section class="verdict"><h2>当前进展</h2><p>' + escape(root_verdict(record)) + '</p></section>'
    body = '<section class="verdict"><h2>现在到底做成了什么</h2>'
    for key, label in [('achieved', '已经做成'), ('inProgress', '正在完成'), ('remaining', '还需验收')]:
        value = overview.get(key)
        if isinstance(value, str) and value.strip():
            body += '<h3>' + label + '</h3><p>' + escape(value.strip()) + '</p>'
    return body + '</section>'


def path_link(path, root, label):
    target = Path(path)
    if not target.is_absolute():
        target = root / target
    try:
        href = quote(Path(os.path.relpath(target.resolve(), root)).as_posix(), safe='/')
    except ValueError:  # Windows evidence may be on another drive.
        href = target.resolve().as_uri()
    return f'<a href="{escape(href, quote=True)}">{escape(str(label))}</a>'


def evidence_links(artifacts, root):
    links = []
    for artifact in artifacts:
        label = display(artifact)
        if isinstance(artifact, dict) and isinstance(artifact.get('path'), str):
            link = path_link(artifact['path'], root, artifact.get('label') or artifact['path'])
        elif isinstance(artifact, dict) and isinstance(artifact.get('url'), str) and urlsplit(artifact['url']).scheme in ('http', 'https'):
            link = f'<a href="{escape(artifact["url"], quote=True)}" rel="noreferrer">{escape(str(artifact.get("label") or artifact["url"]))}</a>'
        else:
            link = escape(label)
        links.append(f'<li>{link}<pre>{escape(label)}</pre></li>')
    return '<ul>' + ''.join(links) + '</ul>' if links else '<p>未记录证据链接</p>'


def render_registry(root):
    root = Path(root).resolve()
    with registry_lock(root):
        grouped = defaultdict(list)
        overview_record = None
        latest_record = None
        for path, event in events(root):
            latest_record = event['record']
            grouped[latest_record['experimentId']].append((path, event))
            if 'ledgerOverview' in latest_record:
                overview_record = latest_record if isinstance(latest_record['ledgerOverview'], dict) else None
        index = ['<p>所有轮次都保存在这一本台账中，后续继续追加。每轮说明作用文件、问题、办法、成果和剩余问题。</p>',
                 '<nav aria-label="轮次目录"><p>跳到轮次：']
        sections = []
        for ident, history in sorted(grouped.items()):
            record = history[-1][1]['record']
            # Reuse the registry's identifier guard before deriving any filename.
            validate_record(record)
            if ident.lower() == 'index':
                raise ValueError('Iteration ID index would overwrite the HTML index')
            human, verdict = human_summary(record), root_verdict(record)
            body = '<section id="' + escape(ident, quote=True) + '"><h2>' + escape(ident) + '</h2>'
            body += '<section class="verdict"><h3>审核结论</h3><p>' + escape(verdict) + '</p></section>'
            body += '<section class="summary">'
            for key, title in [('document', '作用文件'), ('problem', '什么问题'), ('approach', '采用办法')]:
                body += f'<article><h3>{title}</h3><p>{escape(human[key])}</p></article>'
            body += '<article><h3>取得成果</h3><p>' + escape(human['outcome']) + '</p>'
            body += '<strong>本轮判断</strong><p>' + escape(human['remaining']) + '</p>'
            body += '<strong>下一步</strong><p>' + escape(human['next']) + '</p></article></section>'
            body += '<details class="technical"><summary>展开技术证据、来源身份与评分记录</summary>'
            body += '<p class="notice">未知不等于零，运行成功不等于来源通过。新调用、复算、缓存及代码、来源、H800 验收分别认定；证据链接未由渲染器验证。</p>'
            body += '<p>保存状态：' + escape(display(record['status'])) + '</p>'
            for key, title in SECTIONS:
                body += f'<h2>{title}</h2><pre>{escape(display(record.get(key)))}</pre>'
            known = {key for key, _ in SECTIONS} | {'schemaVersion', 'experimentId', 'status', 'artifacts', 'humanSummary'}
            extra = {key: value for key, value in record.items() if key not in known}
            if extra:
                body += '<h2>其他记录</h2><pre>' + escape(display(extra)) + '</pre>'
            body += '<h2>证据链接</h2>' + evidence_links(record['artifacts'], root)
            body += '<details><summary>展开全部历史事件（' + str(len(history)) + '）</summary>'
            for event_path, event in history:
                label = event['recordedAtUtc'] + ' / ' + event['eventType']
                body += '<details><summary>' + escape(label) + '</summary>' + path_link(event_path, root, '原始事件 JSON')
                body += '<pre>' + escape(display(event)) + '</pre></details>'
            body += '</details></details><p><a href="#top">返回目录</a></p></section>'
            sections.append(body)
            index.append('<a href="#' + escape(ident, quote=True) + '">' + escape(ident) + '</a>　')
        index.append('</p></nav>')
        if grouped:
            index.insert(0, ledger_overview(overview_record or latest_record))
        atomic_write(root / 'index.html', page('OER 解析优化台账', ''.join(index + sections)).encode('utf-8'))
    return {'root': str(root), 'index': str(root / 'index.html'), 'iterations': len(grouped)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=DEFAULT_ROOT)
    parser.add_argument('--input', type=Path, help='Existing experiment_log v1 record JSON; omitted to rebuild HTML only')
    parser.add_argument('--event-type', default='observation')
    args = parser.parse_args()
    if args.input:
        record = json.loads(args.input.read_text(encoding='utf-8-sig'))
        append_record(args.root, record, args.event_type)
    print(json.dumps(render_registry(args.root), ensure_ascii=False))


if __name__ == '__main__':
    main()
