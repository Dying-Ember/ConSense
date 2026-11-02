# 08: 用外置稳定 OCR 完成一次新资料处理

## 2026-10-30 Source CPU OCR GL 依赖静态发布（历史时点）

现 Source venv 和 overlay 在 UID10001 下导入 cv2 原报缺 libGL.so.1；候选七库使隔离环境 cv2、RapidOCR、ONNX Runtime、Pillow 导入及微型 PNG 编解码通过。七个库与经本机 Ubuntu 签名索引固定的七个 deb 逐项 SHA 一致，七份版权文件随库发布。新 ext4 目录的 14 文件共 2,978,918 字节，root:root/0444、目录0555；独立 UID10001 逐项读回 SHA 一致且不可写。发布回执 SHA256 b65a3c84083b18d6abe8329ec590bf3d1cfd063a68d5d990b2565ac31336149e；独立读回回执 SHA256 39a47812b67c1f5a3f72e1e897bb507fc1cc7e4b100ec15fec4fe945c6010e24，证据位于 .local/container-rehearsal-20261029/source-identity-ocr-gl-independent-readback-20261030/。五角色当时运行；GPU owner 保留 release_unknown。库尚未挂入 Source，条件依赖、容器实际导入、OCR recipe、GPU双路和业务均未验，票据保持开放。


**2026-11-01：**MinerU 运行配套的独立编译器视图首次 CPU 探针因缺少 `libjansson.so.4` 失败；补入已核对的库后，第二次非 root、无网络无 GPU 探针编译 Python 扩展并导入、链接 `libcuda.so.1` 通过。Source 原容器已恢复，五角色、双 health 和历史文档指针独立读回正常。此项未调用 CUDA、未执行 GPU 模型或 OCR；原失败保留。

**2026-11-01 demo 对齐：**沿原上传／OCR 接口，用已采用的真实 Paddle 和 MinerU、外置权重处理代表性资料；原件、物理页与结果保持对应，图纸按既定规则排除。复用已有结果，不换模型、不为演示新增长期持久化工程或重复整库处理。GPU 互斥和当前任务清理仍需满足实际运行要求。

**What to build:** 新上传的代表性资料经真实容器使用现有双路 OCR 处理，能查看对应的原页和结果。

**2026-11-01 14:10 Source 实跑：**同一真实 PDF 物理页 3556 的新请求 `67f1bd62422943cfad324c91f5f3d8d9` 从 Source `/ocr` 在 57.958 秒返回 HTTP 200，结果含 PaddleOCR-VL 与 MinerU 两路；Paddle 复用既有成功记录，复用绑定的 PDF、页图 SHA 和物理页与本次一致，MinerU 为本次实际推理，operation `e63ee854a7014ac9851e576f89c35f9b` 记录 `completed/released`、子组为空。结果含 23 个 regions、2,863 个字符，采用状态为 `candidate_unverified`，不代表识别质量已验。实际 PDF 与页图字节 SHA 已独立复核匹配，物理页关联仍依既有 raster plan。原请求、结果、双路原始证据及失败保留于预演数据；回执 `/srv/consense-rehearsal-20261029/evidence/local01/ticket08-b1-single-page-67f1bd62422943cfad324c91f5f3d8d9/summary.json`。App 现有外置配置启用 OCR、使用 local 模式指向 Source；尚未证明 Java／浏览器业务上传、项目与版本关联及原页结果回查，不能将 Source 单页结果当作业务通过。

**2026-11-01 业务入口验证进行中：**内置浏览器创建隔离项目 `proj_mvxmgte2cplp`，上传获授权 2b 文件 `08 - AoA (scan).pdf`（4 页，原件 SHA-256 `544defab851a2e44a4e6ab13d1bf2bea66c24ca651789a65e41d0b11a14ca4f6`），系统创建文档 50 并先要求确认身份。已通过页面逐页查看原件，确认为文字合同资料、非图纸，按项目资料确认并启动详细解析；详细解析在第 1 页渲染时报 `UnsatisfiedLinkError`，未产生新 GPU operation；当前 App 的独立 Java 字体探针复现 libharfbuzz 缺失；完整字体库及配置候选在隔离 JRE 中完成中英文绘制，尚未在容器内验证。原始文档错误日志无详细栈，不能百分之百追认该次缺失库。记录仍为 PARSING，启动恢复与原件保留待核。双路执行、结果与释放未通过。

**2026-11-01 字体修复及重试：**App 已挂载独立字体运行目录，容器内中英文绘制探针通过；原文档 50 因前次异常由启动恢复标为 FAILED，原件 SHA 不变，按现有入口对同一文件重试一次。重试仍在第 1 页渲染报 `UnsatisfiedLinkError`，未见 GPU operation 或双路 OCR；字体探针不足以证明 PDF 渲染链，正定位其他原生依赖。后续确认缺 libjpeg.so.8，连同 liblcms2 补入组合运行时；新容器以同一 JRE/PDFBox 独立渲染 doc50 原件第一页通过。再通过页面对同一文件重试，已进入物理第 1 页真实 Paddle OCR，任务仍在运行，MinerU、最终结果和释放待验证。原失败和第二次失败均保留。

**2026-11-01 业务双路实跑：**隔离项目 `proj_mvxmgte2cplp` 文档 50 为 2b AoA 4 页原件，SHA-256 `544defab851a2e44a4e6ab13d1bf2bea66c24ca651789a65e41d0b11a14ca4f6`。经原页面身份确认及现有解析入口，四页 Paddle 与 MinerU 均真实运行，8 个 operation completed／released、所属进程组为空，最终 GPU owner 不存在。按物理页：1 Paddle `abd93842a5a54cd7ad38d943989d8d6c`、MinerU `ee435a87aa7a457caefb65d5fe8c25a0`；2 `51a66c6cf4a24e26be4e8560a8ccff89`、`c98a2e8a25514b1dbc199a54e6522b92`；3 `cac9f0e7ab664453a633af49c01dcbb6`、`f7d6ca41a99b45bdb1ebfaa6021acd19`；4 `609b669c5b4d4e3f8e37f4e23d7875ee`、`7e9f5d92f05e43d2910c18f61efbcf11`。各 operation 的 request/status/transaction 位于专用 WSL `/srv/consense-rehearsal-20261029/instances/local01/gpu-serial04-r7-20261030/workspace/gpu-operations/<operationId>/`；逐项只读重算两路原始输出及页图摘要与事务一致，同页两路原件 SHA、页号和页图 SHA 一致。页面显示 PARTIAL：4 页 OCR、无未完成页，四页文字质量仍待原页核对；内置浏览器已显示第1页识别文字（合同编号、标题）并由该页打开同文件物理第1页，来源路由 SHA `0954ee1e…`、业务内容 SHA `a4b40724…`。此为代表性业务链，不是四页全文质量或赛方验收。初次字体和 JPEG 库失败及页面状态保留。

**2026-11-01 demo 关票边界：**上述 4 页双路真实处理及页面第 1 页原文／原页回查满足代表性正向业务；App 状态仍为 PARTIAL，四页文字质量未经逐页验收。两次真实原生库错误及误留 PARSING 已保留：现已针对 LinkageError 的上传和继续解析两入口补 FAILED 收口、保留原件并重新抛错，2 项 JPA 回归通过，修复版已换入；换版后 doc50 原件、路由与业务内容 SHA 均与换版前一致，原件及解析页 1 读回成功。历史两条图纸记录为 EXCLUDED，缺文件与页不符分别复用容器夹具和合成响应测试；本次未在浏览器逐条重演这些负例，不能声称图纸上传全过程、缺文件或页码错误的全部实机路径已验证。七库字体/JPEG/色彩运行时已外置并记入交付说明，赛方 ABI、H800 与识别质量仍未验。

**原关联（按实际需要）:** 03 — 资料服务；04 — 显卡任务。实际双路 OCR 需要资料路径及 GPU 正常可用，不额外等待无关整票。

**Status:** done — 按本机代表性 demo 范围关票；AoA 为 PARTIAL，四页文字质量和赛方环境未验

**前次运行记录（历史；后续复验见下）：**真实单页任务 `53fa3bd01b5b467e831d4d4f3e8ef6d2` 的 Paddle 已完成，MinerU 在启动 `ptxas` 时报告资源暂不可用，Source 返回 HTTP 503；MinerU operation `d98db775ceb740088dae09fc330a1fa4` 为 `release_unknown`、owner 保留。原本预演容器自行设置 1 核、8 GiB、128 线程；现已按冻结 GPU Job 方案原位调整为 14 核、180 GB 内存上限、不额外限制进程数，本机实际总内存约 62.8 GiB。先处理该已结束任务的实际占用状态，再在既有接口上复验；配置变化是否解决问题尚未知。

**后续复验（历史时点）：**d98 owner 已在线归档，Source 短暂停止接收请求后恢复，GPU／Source 均未重启；原 d98 状态仍为 `release_unknown`。资源调整后，同一页新任务 `53fb6de6cfff497191069ce983c1f812` 的 MinerU operation `56cb13715a204b1eb638c8a6f4504d6c` 已持久生成对应原页的原始结果，但 worker 因未观察到可信 GPU PID 将释放记为未知，Source 返回 503；当前 owner 仍保留。直接阻断是 OCR session 及消费端的释放判定，正在按已有任务进程组语义最小修复；不伪造 PID 或改写历史状态，模型质量和完整双路业务尚未验收。

**2026-10-30 18:11 +08：**OCR 环境 attempt3 复用已提取 MinerU 目录而未重搬 11.2 GB；UID10001 静态验证 exit0，69,405 项、关键包版本和 `pip check` 通过。Paddle 约 7.3 GB 新归档正在进行，尚无 attempt3 终态 receipt；这不是容器内双路 OCR。固定 GPU owner 仍为一次失败 BGE 的 release_unknown，禁止新 GPU 任务和自动重启；票08保持未关。

**2026-10-30 18:01 +08：**OCR venv attempt2 的 MinerU 归档、ext4 提取和入口重定位 exit0；UID10001 验证命令因本机 `setpriv -u` 不支持而 exit1，Python 验证未运行，Paddle 未开始。失败回执与部分新目录保留，唯一重任务位释放；接续将只验证已提取 MinerU并搬运 Paddle，不重复 11 GB 归档。两套环境、固定 GPU stage、真实双路和释放仍未验，票08不关。

**2026-10-30 17:46 资产与环境实况：**历史 OCR 模型身份 49／49 项实字节已分两处发布至专用 ext4 只读目录，逐项 SHA 与 UID10001 只读核验通过；尚未挂入 GPU 或加载。旧 OCR venv 复制 attempt1 在复制前因 PowerShell 将 WSL stderr 警告当错误失败，原回执保留。主控登记唯一重任务位并启动 attempt2；已越过源目录／容量检查，MinerU tar 截至只读点查为 2,130,821,120 字节且仍在写入，无终态 receipt 或新 venv 验收。Paddle 归档、环境重定位、固定 stage、Source／App OCR 路由、真实双路与释放均未验，票08不关。

**2026-10-30 16:15 接缝实况：**运行中的 Vetting Source `/health` 报 `ocrRouteExposed=true`，OpenAPI 有 `/ocr` POST 和任务 GET/result/DELETE；旧“路由未注册”判断已撤回。现运行 Source 内 `complete_ocr` 导入因缺 `PIL` 失败；锁定的 Pillow 12.3.0 已在独立外置目录由 UID10001 验证导入及小 PNG 编解码，但尚未挂入运行容器。运行 App 的 `CONSENSE_OCR_ENABLED=false`，且未设置 `CONSENSE_OCR_BASE_URL`。因此 OCR 业务链仍未实际通过；两处运行配置须在受控换版后重验，不将软件测试计作本票验收。

**验收（demo 范围）：**

- [x] 通过现有业务入口以代表性授权资料在真实容器运行已采用的双路 OCR；复用已有 Paddle 成功结果和原权重，修复实际阻断 MinerU 的问题。
- [x] 结果对应正确原件、物理页、项目和版本；两路原始结果及现有采用依据保留，按原有方式可回查，不为拼接结果改写原始证据。
- [x] 图纸按已确认规则识别并排除，不送 OCR 或 CAD 解析；缺文件、页不符及处理失败应明确提示，不当作成功。
- [x] 保留本次真实结果和失败；容器 OCR 可运行不代表识别质量或真实赛方环境已验。无需为 demo 单独重建存储或逐次重启证明。
