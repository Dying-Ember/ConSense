# 手测：上传状态、DOCX视觉链缺口与第二步交互

2026-10-22 用户在 Drafting 手测中提出三项反馈：已上传状态不明显；DOCX识别很快且全部提示未推算页码；第二步“识别要求与候选”含义不清。

## 已实施的界面调整

标准模板的成功上传行增加绿色底色、左侧绿色标记、加粗文件名和独立“✓ 已上传”徽标；未上传维持灰色，部分解析／身份待确认使用警告状态，失败使用错误状态。沿用现有tag和主题色，不改来源／识别／采用逻辑。

当前5177的Vite前端已加载改动。TypeScript检查通过，既有 `drafting-template-view.test.cjs` 的12项检查通过；真实浏览器在隔离项目核到NTT成功行和SCT／SCC未上传行有不同背景、徽标和左侧标记。证据 `.local/upload-state78/{typecheck02.log,template-view-test.log,browser02.log,uploaded-status.png}`。任务空间4已完成关闭，没有重发上传／模型／识别操作。

## 已确认的生产缺口，未实现修复

当前普通上传的实际路径为：Drafting上传／替换或沟通资料上传 → 公共DocumentParser → DOCX原生POI／OOXML解析 → 保存来源与结构。`parseTemplate`继续调用同一解析器，仅增加模板证据投影；DOCX返回 `pages=[] / pageCount=0 / ocrUsed=false`。`OerNativeDocxObservation`也明确记录 `rendition_assets_and_native_to_visual_mapping_not_produced`。

本次只读查询用户Sean项目 `proj_mvjgi1gq7o1v`：3份STANDARD_TEMPLATE及15份PROJECT_INPUT全部PARSED，页数0、ocr_used=false。证据 `.local/upload-state78/sean-parse-metadata.log`。这些PARSED仅代表当前原生解析完成，不能解释为转PDF、视觉OCR、布局／页码映射或三路质量核验已完成；后台也未自动排队补跑这些阶段。

[现行范围规则](../oer-scope-and-rules.md)已要求DOCX转同源PDF、执行Paddle／MinerU两套视觉解析并与POI核对。此前[双视觉＋POI实测](docx-poi-dual-visual-validation-20261009.md)是6份全量原生输出与11个完整派生页的离线关键点对照；原记录明确线上Java／OER profile未切换。不能把这份实验记录当作任意上传文件均已自动完成三路处理的证据。

因此，用户指出的“只造了半个轮子”对应真实产品接线缺口。此前笼统保证“没有重复造轮子／整体已复用”缺少生产全链证据，现明确撤回。当前公共读取、局部热修已生效，不等于所有来源准备／识别／布局能力都已接入公共生产链。Drafting两处本地提取切块、完整session/frame与装配后继仍未完成，保持在[现行spec](../specs/oer-demand-driven-business-access-20261022.md)中，不再报告为不存在。

待补实现必须沿既有公共来源准备、LibreOffice、OCR服务及registry／CAS复用；绑定原件SHA、派生PDF SHA、renderer／字体／配方和每路实际覆盖／失败。原生正文与删除／修订语义保持，三路分歧不多数表决；派生PDF页码按其渲染身份记录，不冒称Word原件固定分页。公共状态要区分原生读取完成、视觉准备／运行／失败／未执行、页码映射和核验结果。已有可用内容及人工兜底保持，不让局部视觉失败抹掉全部结果。本次没有新启整批OCR、改变解析结果或假设后续已完成。

## 第二步的业务含义与交互问题

第二步应从模板找出需要填写、选择或修改的事项，再从邮件／会议记录等资料中寻找候选答案；候选需保原文出处，之后交用户核对、采用或修改。例如模板要求填写工期，会议记录中的36个月可以成为候选，仍不自动采用。

当前界面把建立核对草稿、按预算调用模型识别模板要求、识别沟通材料等内部操作并列，名称和先后关系难以理解。记录为待改的人类交互问题；本次未改变识别预算、提示词、候选／采用语义或重发模型调用。
