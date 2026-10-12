# G305 实际报告：全页视觉检查与前端 JSON 下载修复

日期：2026-10-10，Asia/Shanghai。范围是一个真实 G305-only 开发 checkpoint；不是官方八题或完整多来源 Vetting 质量评分。来源／任务详见 [实际报告收集](./oer-g305-real-vetting-report-collection-20261010.md)，输入预算限制见 [MiniMax 调查](./oer-minimax-input-budget-and-provenance-review-20261010.md)。

## 真实任务与导出身份

实际项目 `g305-native-9c5e9309-1cc1-46b5-9a2b-172a7e16b4c7`，job `d7e27142-4fea-4876-b221-3578bfb4c26e`。任务执行结束，19/19；17 次实际 MiniMax-M3 global 返回均为 `[]`，17 个主题仍为 partial。零发现不代表完整检查、没有缺陷或合同质量通过。69 个 checklist 均 unchecked；没有改写真人审阅或确认状态。

原始导出保存在根目录忽略目录 `.local/state/oer-g305-vetting-job-20261010/run-040348-91faa2/`：

| 原响应 | 字节 | SHA-256 |
| --- | ---: | --- |
| `actual-report.json` | 502787 | `b2088dc7cf8e88bc2a4980b1c0da209b2c485c204c1946c0b3f8c059891a8a4b` |
| `actual-report.docx` | 17885 | `ca267d9044dc1909129f2c715ebb15e5615be0de1588d89863f79e1cf209c79e` |
| `actual-report.pdf` | 66812 | `a1b4afdeb3c82e8eee38950ae9583d72cd1cedae1646bcf1adab35f9afe254b0` |

## 全页视觉检查

使用仓库本机官方 LibreOffice 26.2.6、打包 `render_docx.py`、Poppler，按120DPI渲染上述不变 DOCX；18个派生页全部打开检查。另将服务器实际 PDF 独立渲染，23个原报告页全部打开检查。页图／渲染日志保存在 `.local/state/oer-g305-vetting-visual-20261010/run-qa-0419/{docx-render,pdf-render}/`。

所见正文和表格可读，没有发现裁切、重叠或缺字。长表有重复表头／跨页行，分页差异来自两个报告渲染器；18／23不是合同源页码映射。局部排版问题是DOCX派生第16页 Appendix II 的 `Comment` 列内折词，以及实际PDF第22→23页 ledger 的单个 `0` 跨页；没有据此发现内容丢失。

这次视觉检查也看到了独立审计已定位的三个产品验收缺陷：英文报告两条 canonical 限制仍是中文；legacy “全部可解析正文”后缀与 adopted canonical 观察范围冲突。DOCX派生第18页和实际PDF第23页有这些文字。旧报告和原失败始终保留。源代码最小修复已通过59项复制checkout回归，见 [覆盖说明回归](./vetting-canonical-report-coverage-20261010.md)；没有替换在线Java或生成真实G305新版报告，不追认这些旧字节已变成正确报告。

## 实际 JSON 下载故障与通用最小修复

修复前，真实前端选择JSON后显示 `服务器返回错误信息，未生成文件`，没有产生下载。保存的真实成功报告回放到实际 `api.download` 同样重现。原因是 `requireBinary` 将所有 `application/json` 当作错误 envelope，虽然 Vetting 允许合法JSON报告。

改动仅在前端下载边界：

- `src/api/client.ts` 增加显式JSON文档校验选项；默认 binary／preview 路径保持原错误门控。所有顶层自有 `code` envelope（包括 `code=0`）仍拒绝，畸形JSON仍拒绝。
- `src/api/index.ts` 只有 Vetting 的JSON导出启用校验，验证 schemaVersion、reportStructureVersion、请求project.id、非空completed job.id以及必要数组。错误项目／类型／schema／运行状态不会创建文件。
- `tests/vetting-report-download.test.cjs` 执行真实导出callsite和实际client，通过HTTP边界stub覆盖JSON／PDF／DOCX、错误envelope、畸形和错来源。校验职责是报告类型、身份和集合形状，不是完整 finding／evidence 或业务语义验证。

修复验证：`vue-tsc --noEmit` exit0；三个相关 Node test files通过、0失败；隔离Vite build exit0。独立审计另27个replay cases通过，包括502787字节真实JSON完整保持、各错误envelope和binary/preview兼容。测试文件数与replay case分母分别报告，不能相加成模型成绩。完整日志、源码freeze和独立审计receipt保留。

## 真实浏览器复验

仅替换本轮拥有的5175隔离preview，旧PID92416／build保留在操作receipt中，新PID97616。使用冻结构建，不写在线5173 dist；API仍为18082。

内置浏览器2/tab8刻意reload新bundle，恢复同一个真实completed job，选择JSON并点击一次Export report。实际观察到 `JSON review report generated and download started`，产生 `C:\Users\Administrator\Downloads\ConSense_Vetting_Report_.json`。下载502787字节，与上述后端原报告SHA及bytes完全相同；project／job身份也一致。保存原下载副本、实际DOM和viewport screenshot。没有新建Vetting job、index、Ask或模型调用。

10个受保护listener的PID／CIM启动时间复核不变。最初只读守卫使用Get-Process更高的100ns精度与CIM基线直接比较而报错，实际时间差低于1微秒；核对同源CIM timestamp后通过，没有执行任何服务操作。初始preview launcher也有一次DateTime重解析失败，发生在stop前，旧失败保存；两者都是本地验证工具问题，不作为产品模型失败。

核心证据：

| 根目录相对路径 | 用途／SHA |
| --- | --- |
| `.local/state/oer-g305-vetting-visual-20261010/run-qa-0419/visual-and-browser-qa-receipt.json` | 每个导出／页PNG／DOM／截图／build／测试日志bytes和SHA；receipt SHA `5e52bc56496ed4d80255127330af5f4a58be7a8f80cef5e08ec3c717925d2492` |
| 同目录 `browser-json-export-success-dom.txt`／`browser-json-export-success.png` | 真实恢复任务、JSON选择和成功DOM；viewport截图不保证包含短暂toast |
| 同目录 `frontend-json-fix/protected-listeners-after-browser.json` | 10个实际listener同源timestamp复验及初次精度守卫说明 |
| `.local/state/oer-g305-independent-vetting-audit-20261010/run-041420-75ca9c/frontend-json-download-review-receipt.json` | 独立27cases，SHA `03340b0b95ccff8dfe925d150322cd7b3f7d3dec32531c7e91ec2c0e416549f7` |

未演练非空finding／跨文件证据、完整模型输入预算或全部语义覆盖；官方八题、正式多来源Vetting评分、权重训练、Linux／H800验收均不能由此追认完成。
