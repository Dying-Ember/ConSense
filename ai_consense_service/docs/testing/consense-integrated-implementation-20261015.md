# ConSense 统一实施进度与未验收门（2026-10-15）

本记录是实施进度，不是来源质量或整体验收结论。统一规格与原 OER D1–D10、EAC01–22、ENG-00–10 全部保留。原北京时间 08:30 目标未达；完整 Goal 继续，截止提醒已停用。

**最新用户执行边界：环境和代码实现自动化必须继续完成并要求绿灯，效果验证暂不开始。** 赛方原件前后对照、真实解析效果评分、SL 版本对比及后续业务效果验收均保持未执行。前后端本地隔离预览已恢复，HTTP 页面、后端项目列表及前端代理均 200；预览使用此前已验证构建，不把可打开页面视作业务验收。

本轮代码收尾已修 Vetting 普通原件角色绑定、Word 移动修订、嵌套单元格跨度及纵向合并续格开头的原生行定位。XML 回退中无法验证的合并保留原始格路径和合并值，并明确 unknown 与下载提示。最终前端 recovery08 为 **166/166 通过、0 失败、0 跳过，类型检查与 Vite 构建 exit 0**。独立 Standards 与 Spec 恢复差异终审均 0 开放代码必修，绑定工作字节清单 `266e26f197ce5f80d31c247c18a6c140f14c7a735ae79be99212f42798a9a447`；分别深入核查 33 和 31 文件，不冒称对所有历史整合内容逐行重审。下方旧轮状态保留历史语境，后续 Java/Python 实际终态另附。

隔离分支为 `codex/consense-integrated-20261015`，实施固定起点为 `d2b280a5e17b2cba4ad373cc47fae50288e3bb6d`，交付整合起点为 `55fa448e965fab7f292d53fb63477bde7b2dfee6`。用户要求的 spec 本地提交已完成：`5f0b6eb38c2a1a78e23e6779b301f146b54d15b5`。实现整合仍在验证，不能把该文档提交称为代码完成。

## 代码与检查

| 工作线 | 当前实现与验证 | 保持开放的门 |
| --- | --- | --- |
| 正常 OER 上传链 | 观察先行、身份/用途路由、同源 frame、原生表格候选、完整两路页结果、零文字对象及 unknown 持久化已接线。新增原生无边框表格缺口触发；原生文字不被冲突视觉候选替换。 | 正常赛方原件的完整前后对照、旧正确保持、不同作者和独立来源质量。 |
| Word | 保留原始/有效/删除/删除线、编号、符号、表格与原生图片/对象/OMML。图片仅绑定原件包内关系和资产 SHA；公式未渲染时保原始 OOXML 和 unknown，不平拼成错误字面数。正常候选/模型投影保必要来源和 unknown，原始 XML 留来源层。 | 完整真实 Word 来源质量、真实渲染定位；不推算物理 Word 页，不做图纸细读。 |
| 双解析 native API | Paddle 与 MinerU 完整原始返回、原生数组、输入/输出和真实终态捕获；校验实际 NPY、CUDA/精度声明、EOS/cap/pad、layout session 完整 IO。官方 BF16 的类声明 FP32 保留模块不被误拒。推理后的格式化失败保输入、manifest 和数组。 | 实际供应商模型执行和真实加载身份；源码/契约替身不授模型或来源分数。 |
| Vetting | checkpoint/lease/恢复、报告快照、实际执行账、完整 normal candidate、跨 run 人工记录，以及按强 source identity 和完整 required-member 闭合的局部复用已接线。未知关系或新来源身份回退全 generation 重查。 | 新原件完整业务审查与报告验收；candidate 不等 accepted canonical。 |
| Advisory / 前端 | 自然问题、应用前缀、有限修复、原子历史/引用、原件阅读、流式输出已整合；前端 recovery08 为 166 项通过且类型检查/Vite 构建通过；5 个独立脚本也通过。Advisory 214 项聚焦 Java 检查为较早切片，不能相加。 | 真实模型/来源/浏览器业务验收。端到端及效果按用户要求暂缓。 |
| 离线交付 | 独立 CPU/单 GPU 制品、严格清单、资源释放、MySQL 空目标恢复边界、CPU coordinator、实际 request/source/profile 绑定 GPU Job、native 持续会话和业务 LLM 接线已实现。最终根 URL/model catalog、精确本地 OCR recipe 两条接缝有 22 项及扩展 63 项聚焦通过，仍以当前完整 Python 轮终态为准。 | Linux/断网/MySQL/K8s/H800 实际目标验收未执行；代码/契约通过不等实际部署可运行或模型质量通过。 |

以上检查存在继承和切片重叠，不能相加作唯一总数。

## 完整自动化与审查的实际状态

恢复后的最新 Java full06：289 类、2,818 reported、2,691 实际执行通过、0 failures/errors、127 既有条件 skipped，Maven BUILD SUCCESS。比旧轮多 4 个条件组未运行，是未传 `consense.test.specification-source`；这些测试使用精确原件字节及人工 parsed-state，只验证身份/持久化，不做 fresh parse 或质量评分。正在恢复旧轮同一精确字节参数重跑 full07，不能把这 4 组额外 skipped 当完成。默认系统 Python 缺 FastAPI 导致的 full05 失败保留，full06 已指定现有测试虚拟环境。Java full04 的 native malloc 中断与 Python 重启中断均无完整通过终态。

Python recovery05 综合轮仍在运行；已观察到的 9 个错误为下述精确历史缺件，尚无新轮最终收据。补充环境/代码检查：`pip check`、两工具目录 `compileall`、`npm ls --depth=0` 全部 exit 0；独立 authored 归档恢复测试 6/6；README 四脚本和进度脚本均通过，后者 10/10。该脚本初次因漏声明新增原件阅读组件的测试边界而 10 项失败，补既有 placeholder 后通过，所有原断言保留。两轴恢复差异复核均为 0 开放代码必修；源清单和逐项失败记录留在忽略目录，不能把此前失败覆盖成通过。

首次 Python 适用范围原始运行：`testsRun=1601`、`passed=1520`、7 条断言失败记录、77 条错误记录、0 skipped。子测试和重复继承发现会产生多条记录，因此不能用通过数直接相减得到错误数。原始失败已保存，不能将后续聚焦绿灯替换该运行结论。

已定位并修复三类自足夹具/环境问题（66 项聚焦绿）及错误的伪 PDF 头资产夹具；后者改为实际 authored 五页 PDF，59 项正常 CAS/registry/HTTP 检查绿。生产来源闭合和同源校验未放宽。完整 Java 适用检查第三轮已结束：2,825 reported cases，2,702 实际执行通过，0 failures、0 errors，123 既有外部条件 skipped；另有两类及两项历史外部重放显式排除，逐项另存适用清单，不算通过。打包及构建 SHA 正在固定。第二轮 2,824 cases／20 failures 的原始日志与 XML 仍保留。

另有 8 项旧 embedding 源码兼容重放与 1 项历史英语查询重放缺少精确原始资产。该问题在 2026-10-09 旧审查已有记录。旧 compatibility 协议也不能自动授权当前扩展的源码依赖；没有制造历史 receipt 或替代 64 条查询来换绿。这九项保持未验证，原全量失败保持。

Standards 独立审查发现的全源平方级读取已修复；同一实际源码 CountedList 观察由 `N²+2N` 降为 `3N`，按页/故事隔离和 unknown 保持。Spec 审查发现原生无边框表格触发、完整两路结果消费、Word 原生对象及离线接线四处缺口已实施；后续命令行模块绑定、Job 创建竞态、原件角色绑定与普通 Word 原生定位问题亦已修并经过独立恢复差异复核。较早 31 项、83 项切片及其中失败完整保留，不相加，也不授来源或实际目标质量分。

## 真实验证顺序与边界

继续完成代码、必要自动化与两轴审查后，首个真实验收是赛方 GCT、AoA 扫描件、SCC 等已登记原件，从正常上传、解析、持久化到原件回取，按完整同范围/同分母比较修复前后和旧正确保持。准备文件、人工 parsed-state、CAS 元数据和契约替身均不能替代该链。

三份完整原件的独立 source-only 参考已准备完成；GCT/AoA 全六页已逐页查看，SCC 全部 native story/文字/表格/公式保留；CER 仍 null，未作人工确认或质量采用。隔离基线 archive 和只读持久化 CLOB 回读工具已准备，后者仅在 authored 内存 H2 自检。原件前后对照尚未执行，本轮实际模型调用为零，不能判断是否显著提升。只有明确改善后才做后续非端到端验收；SL 多版本比较最后处理。活动服务/数据库、fixed05/Local05、Drafting、gold、原件和权重未切换或发布，无自动 push/PR。

完整证据在隔离工作区忽略目录 `.local/`，当前执行账本在主工作区 `.local/state/consense-implementation-20261015/execution-state.json`。关键收据包括 `product-owned-final-receipt-attempt02.json`、`python-full-validation-20261015/receipt.json`、`python-fixture-repair-20261015/`、`state/java-final-integration-20261015/`、`standards-review-20261015/` 和 `spec-review-20261015.json`。它们分别记录实际范围、源码身份、失败和未执行，随复测追加，不覆盖旧失败。
