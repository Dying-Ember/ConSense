# 官方 Q1.6 首次实际业务结果

2026-10-10，Asia/Shanghai。依据 [当前范围](../oer-scope-and-rules.md)、[官方业务计划](./oer-official-business-acceptance-plan-20261010.md) 及冻结收集器。生成仅使用 question-only 和实际已采用来源，未读 2d／2e；题目曾在开发中暴露，不称盲测。本页先记录首次输出，后续修改／重跑分别另存，不能覆盖这次结果。

## 实际运行与输出

项目 `official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`，真实 GCC id1＋项目 SCC id2；仅采用 GCC65–70 的50 whole units及 SCC17相关22 whole units，非完整合同。Java 18083 是旧冻结 Jar `2321add597a844995b35a8a0f9b3fd041c171aae2a5b1e2d36e90228ecd56426`，Advisory OER8875，显式 profile `minimax-cn`／configured model `MiniMax-M3`。response 的 modelIdentity 仅证明 configured_profile；provider原始响应及token usage未由收集器保存。

问题要求将 GCC17.1 的争议避免与解决程序概括为流程图。首次 POST 于07:18:52.435389开始，07:34:58.748323收到 HTTP200，elapsed966.297秒；该时间包含 Java 来源门控、真实检索／重排、模型调用、回答后门控和保存，不能当纯模型推理时间。Java日志07:31:19.890记录模型已返回，07:32:59线程采样仍在模型返回后的重复来源复验，依据见 [性能诊断收据](../../../.local/state/oer-canonical-recheck-design-audit-20261010/q16-live-stack-0740-readonly-v1/diagnostic-receipt-cp936-v2.json)。

实际 raw：`.local/state/oer-official-q16-first-run-20261010/advice-071210-b73943/003-question-1.6-response.bin`，SHA `72abb036b3703759d22484073484c5a865b7b970d1f612b1f0b09a8437de964d`。结果 `grounded=false`、`basisScope=insufficient`、answer为空、messageId2、流程图字段unsupported。澄清要求提供 GCC17.1(1)–(18)完整正文，明确是概括标准GCC还是SCC修订后的项目条款，并要求识别采用依据。没有交付题目要求的流程图，不计为Q1.6通过；是否准确说明缺证及各维度评分由隔离source-only评价另记。

sources-before／after均HTTP200且raw SHA相同 `d6aa393b45fbcb8436c30f757379d66a61dbf78c79bcc0f8759d09db03c734e5`。index-after于07:41:45结束，HTTP200／399.422秒，raw SHA与before相同 `2dafb5ce92715bcdf12c14b89f817be44cdcdc24789954d75b1fc17a13606f70`。Root核collector session78628退出0、收据终态collected_unscored，收据SHA `47d49611437c97f0c82ae66f52cc15ceba64d702261d0d339cb1b8a80ac22802`；五份raw逐SHA独立重开通过。这证明首次收集的身份栅栏有效，不把collected_unscored称为质量通过。

## 首次失败的已观测原因及预算边界

实际retrieve返回3个结构单元，每个1 member：GCC17.1标题、GCC17总标题及一个项目SCC单元。保存的GCC元数据包含正文及typed parent关系，但SourceUnits只按相同clauseId／观察块分组，没有消费跨whole-unit的父子关系；17.1 structure context有31条relations，却只有标题的1 part。结构完整的标题不等于章节正文完整。详见 [实际检索保真审计](./oer-official-q16-actual-retrieval-readonly-validation-20261010.md)。

另一个影响因素是本轮忽略目录中的准备runner显式采用 candidate-limit24／top-k3；正式ConsenseProperties默认50／10。Advisory canonical路径复用Vetting检索配置，不能用旧legacy Advice的stuffing／topK下限来解释本次行为。正文17.1(1)在实际rerank第11位，小返回预算先被标题占用。本次同序列source-only重放：unique SourceUnits top3为GCC正文0／30，top10为5／30，top20与24都只返回19个unique units、正文13／30；实际candidate池只有13个正文，另17个未入池，单加K不够。正式默认50候选并未在此重放，不能推称其召回数字。只沿既有typed parent声明的context-only离线prototype可补30／30，保留原payload／scores／pool／unknown，不表示已进模型或法律闭包通过；详见 [标题正文闭包诊断](./oer-official-q16-title-body-closure-diagnosis-20261010.md)。

这轮证据首先支持检索预算及已发布结构未被消费的问题；不能从本次缺证拒答推出MiniMax法律能力不足，也没有进行OCR或embedding／reranker权重训练。原件、sealed public、首次索引／排序和回答均保留，不向生成端补入评分答案。

## 独立评价和后续变化

Root使用冻结prepare_offline_answer工具在新的eval-only目录 `actual-first-answer-0735-v1` 提取：83项JSON／来源检查与24项pointer离线比较通过，HTTP／模型新增0。answer文本确实0字节。随后Root在 `actual-first-pointers-0742-v1` 执行每个实际来源1个代表pointer GET：两次200，GCC46.641秒／SCC17.266秒，材料差异0，收据SHA `bd9252b3569e414255f9cc93a1151dde2024aa22dd995834472531958d558ea6`。其余pointer仍offline-only；这不是24个live回取、原图serving或浏览器通过。

独立source-only评分收据 `.local/state/oer-official-q16-eval-only-20261010/actual-first-answer-independent-score-v1/independent-first-response-score.json`，SHA `5d69753cc7ff66f934d8fe7e8bdb967455be980758c580940859b029017f29ca`：正文／流程图任务未完成，30项要求全部未回答（17核心／13支持），不是30项事实矛盾，不计算空回答的事实准确率。实际业务返回4 units／24 whole-member pointers：GCC两个标题、SCC标题及完整SCC17.301的21成员；GCC必要正文0／30。第四unit来自Java采用上下文补充，勿与原retrieve的3 units混为同一阶段。

拒答基本符合实际返回集；“only observed-region boundaries”措辞过宽，因为SCC完整正文确实在上下文。已有原件／public含完整GCC17.1，要求用户再提供正文不证明原件缺失。题目字面要求标准GCC概括，可用reference-only范围回答，项目采用澄清并非该字面问题的必需条件；是否在补全证据后仍过度澄清，留新轮真实输出评估，不为这一首轮局部观察先加题目特判。独立保真核59个源检查父单元、24个返回成员、30项原文引用／身份均通过，24 pointers只有可选null省略，无假DOCX bbox／材料差异；独评新HTTP／模型0，未读2d／2e。

重复复验的最小改动已精确落主源码两文件和一个新契约测试，主源码最终SHA与隔离175项通过的固定版相等，37个漂移负例仍拒绝；[三文件应用收据](../../../.local/state/oer-canonical-recheck-design-audit-20261010/main-apply-v1/apply-receipt.json)保留原文件和diff。新Jar已在复制checkout离线打包，SHA `762b671197f04015554f79adc4bcb0d90b1d275b86cbe958fafd8de601faa76e`，与旧Jar全部687文件成员逐bytes比较，仅两个批准class不同；其余class／配置／库相等。截止07:45未切18083，未测真实加速。标题闭包进入另一个ignored复制源码的Python／Java对等契约实现阶段，主Python和运行索引未改。后续受控恢复／克隆持久库、启动新Jar与新index收据另存新run，不覆写本轮结果。

本页不表示八题通过、完整多来源Vetting通过、浏览器流程图交付、权重微调、Linux发布包或H800部署通过。
