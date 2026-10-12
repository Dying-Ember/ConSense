# MiniMax 中继接入、测试与开发移交

更新日期：2026-10-09。此文档面向在自己机器上运行 ConSense 前后端的开发同事。前端通过同事本机后端调用 MiniMax；用户的机器提供带独立访问 token 的中继，官方 MiniMax API key 只由中继服务器持有。

合并并拉取前后端更新后，如果已经收到负责人提供的 `relay-client.env`，将它重命名为 `.env.relay`，放到 **后端仓库根目录，与 `pom.xml` 同级**。按后端 `docs/minimax-relay-handoff.md` 开头的快捷命令读取并启动：SDK 地址和 token 会自动映射为 Java 配置。前端只配置 `CONSENSE_API_TARGET`，此凭据文件不放进前端。

## 地址与凭据

| 用途 | 地址或值 |
| --- | --- |
| 中继健康检查 | `http://10.149.131.175:8092/health` |
| 同事后端的 MiniMax root | `http://10.149.131.175:8092`，不加 `/v1` |
| OpenAI SDK Base URL | `http://10.149.131.175:8092/v1` |
| 模型 | `MiniMax-M3` |
| 前端的模型来源 ID | `minimax-cn`，页面仍显示“MiniMax 国内 Token Plan” |

同事需能访问该局域网 IP；中继主机需要保持在线。IP 变更后请同步后端和开发客户端配置。

请向项目负责人单独取得中继 token。此凭据允许调用中继并消耗同一个 MiniMax 套餐额度，应保管在本机环境变量或 Git 忽略的客户端配置中。不要将 token、官方 key 或包含它们的配置提交到代码仓库、PR、截图和测试日志。

前端无需中继 token，也不保存任何模型 API key。前端只保存用户选中的模型来源 ID；服务端负责认证和调用。健康检查不需要 token，HTTP 200 只能证明中继进程可达，真实模型生成还需要认证与上游调用验证。

## 同事本机前后端启动

先取得配套的 [ai_consense_service 后端仓库](https://github.com/s1765029698/ai_consense_service) 最新代码。后端包含专用 `minimax-relay` 配置，必须与 `h2` 一起启用；仅使用默认官方 MiniMax 配置无法直接调用此中继。

在启动后端的 PowerShell 窗口中设置：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'h2,minimax-relay'
$env:CONSENSE_MINIMAX_BASE_URL = 'http://10.149.131.175:8092'
$env:CONSENSE_MINIMAX_RELAY_API_KEY = '<单独取得的中继 token>'
$env:CONSENSE_ALLOWED_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
mvn spring-boot:run
```

后端专用配置读取 `CONSENSE_MINIMAX_RELAY_API_KEY`，不回退到官方 API key。该配置保持模型来源 ID 为 `minimax-cn`，请求模型为 `MiniMax-M3`，并将 529 过载重试交给中继，避免应用与中继重复重试。后端启动端口以其启动日志为准，以下示例使用 `8080`。

在前端仓库打开另一个终端：

```powershell
npm ci
$env:CONSENSE_API_TARGET = 'http://127.0.0.1:8080'
npm run dev -- --host 127.0.0.1 --port 5173
```

打开 `http://127.0.0.1:5173/#/drafting`，在顶部“模型来源”选择“MiniMax 国内 Token Plan”，确认模型显示为 `MiniMax-M3`。`CONSENSE_API_TARGET` 指向同事本机 ConSense 后端，由 Vite 转发 `/api`；不要把它设置成 `8092` 中继端口，因为中继没有 ConSense 的项目、资料、变量或文档接口。

模型来源选择作用于新操作。已开始的识别保留操作开始时的来源，历史报告显示当次记录的模型。切换顶部选择不会重跑识别或重标记旧结果。配置可用状态表示服务端已配置，不等于真实上游配额、连通性或答案准确性通过验收。

## 使用 MiniMax 参与测试与代码修改

开发与回归应包含 MiniMax-M3 的真实调用，不仅运行本地 mock 测试。建议按下面的顺序执行并保留证据：

1. 创建明确标为 `TEST ONLY` 的独立项目，上传获授权的测试资料，使用 `minimax-cn` 发起识别。不要覆盖现有用户项目或修改其已确认的变量。
2. 对照测试集的冻结答案和原始资料，核对值、引用、完整列表、冲突及应留空字段。保存 run ID、模型来源、harness 版本、资料清单／文件哈希、耗时和失败记录。模型完成调用不等于答案正确。
3. 把失败的最小复现、相关源码片段和预期行为提供给 MiniMax-M3，请它分析原因、建议修复和回归用例。发送前移除凭据及不在授权范围内的资料。
4. 由开发者审查建议并落到代码，执行相应单元／集成测试和前端类型检查、构建，再用同一失败题及不同变量值的新题进行真实 MiniMax 回归。不要为了提高评分改写冻结答案或放宽 harness 的证据门控。
5. 在 PR 中分别记录本地自动测试、真实 MiniMax 业务评估、浏览器操作验收结果及尚未验证的环节。模型生成的代码或它对结果的自评都不能代替这些验证。

前端可移植检查命令见本仓库 [README](../README.md)。有关外部原始 OCR fixture 的既有限制仍适用。OCR、embedding 和 rerank 使用各自的配置，此文本中继只提供聊天模型。

## 独立开发客户端调用示例

需要让 MiniMax 分析测试或代码时，可以从开发脚本调用 OpenAI SDK。先在本机配置环境变量，`OPENAI_API_KEY` 使用单独取得的中继 token：

```text
OPENAI_BASE_URL=http://10.149.131.175:8092/v1
OPENAI_API_KEY=<单独取得的中继 token>
OPENAI_MODEL=MiniMax-M3
```

安装客户端依赖 `pip install openai`，然后使用：

```python
import os
from openai import OpenAI

client = OpenAI(
    base_url=os.environ["OPENAI_BASE_URL"],
    api_key=os.environ["OPENAI_API_KEY"],
    timeout=1900,
    max_retries=0,
)
response = client.chat.completions.create(
    model=os.environ.get("OPENAI_MODEL", "MiniMax-M3"),
    messages=[
        {"role": "system", "content": "分析给定的最小复现和源码，指出证据充分的原因，提出小范围修复及独立回归验证。"},
        {"role": "user", "content": "在这里提供已脱敏、获授权的测试失败信息和相关代码。"},
    ],
    max_tokens=4096,
    stream=False,
    extra_body={"thinking": {"type": "disabled"}, "reasoning_split": True},
)
print(response.choices[0].message.content)
```

这段代码得到模型的分析／修改建议；把建议应用到代码并运行验证仍由开发者完成。使用其他 OpenAI 兼容开发工具时，也需确认支持关闭流式响应；标准工具的完整编程代理或工具调用流程尚未通过此中继验收。

## 接口范围与排查

- 提供需 bearer token 的 `GET /v1/models` 和 `POST /v1/chat/completions`；模型固定为 `MiniMax-M3`。
- 只支持非流式请求，`stream=false`，`n=1`；输出预算为 1–16,384 tokens。
- 最多同时两个上游请求；额外调用返回 `429 relay_busy`。单次上游超时为 600 秒。
- 只有明确的 HTTP 529 `overloaded_error` 才自动等待 2 秒、5 秒重试，最多三次尝试；最坏等待可能超过 30 分钟，因此后端专用配置超时为 1,900,000 毫秒，独立 SDK 示例超时为 1900 秒且 `max_retries=0`。
- 前端保留各功能原有的等待时间：Drafting 识别两小时、生成一小时，均覆盖上述单次中继等待；Advice 的默认请求等待为十分钟，持续过载时可能先显示超时。Vetting 以异步任务提交并读取状态。浏览器超时或关闭页面不能保证中止服务端和上游的调用。
- 持续 529 表示官方集群仍然过载，重试耗尽后会保留失败状态；401、其他 5xx、网络超时及语义错误不会触发这项重试。
- 该中继没有 embedding、rerank、OCR、Responses 或音视频接口，也没有直接浏览器跨域调用入口。

连接失败时，先检查同事设备能否访问健康检查，再检查独立 token、后端的两个 active profile 和 endpoint root。模型选择不可用时先检查本机后端配置；历史记录或普通读取接口可用不代表模型调用可用。不要把鉴权失败、资料门控失败或真实模型错误自动切换成另一个模型的结果。

此地址使用 HTTP，适用于受信任局域网，不提供公网 TLS 入口。向其他同事共享时，应由项目负责人另行交付访问 token，避免将其写入仓库中的示例。
