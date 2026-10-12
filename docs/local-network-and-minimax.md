# 局域网访问与 MiniMax 调用

部署日期：2026-10-09。以下地址使用当前连接中的以太网 IP；IP 更换后需同步客户端地址与 `.local/runtime.json` 中的前端 Origin。

| 用途 | 地址 |
| --- | --- |
| 前端 | `http://10.149.131.175:5173/#/vetting` |
| 起草页面 | `http://10.149.131.175:5173/#/drafting` |
| 应用后端 | `http://10.149.131.175:8080` |
| MiniMax OpenAI Base URL | `http://10.149.131.175:8092/v1` |
| MiniMax chat endpoint | `http://10.149.131.175:8092/v1/chat/completions` |
| Relay 健康检查 | `http://10.149.131.175:8092/health` |

同事需能访问该局域网地址。已加入同一 Tailscale 网络的设备也可使用 `100.81.17.113` 代替上述 IP。

## 凭据分工

应用和 relay 从 Windows 系统环境变量 `MiniMaxCN-token-plan` 读取官方凭据。启动后端时映射为其已有配置键 `MiniMaxCN_Token_Plan_API_Key`。runtime、命令行和启动收据只保存配置与环境变量名称。应用直接访问官方接口，独立 LAN relay 的两路并发限制仅适用于开发同事使用的代理 API。

开发同事使用的是独立的 relay token，保存于系统变量 `ConSense_MiniMax_Relay_Token`。官方 key 不能作为 relay 的访问 token。Relay 固定请求 `https://api.minimax.cn/v1/chat/completions`，服务端替换 Authorization，不返回官方 key。

本机交付文件为 `.local/state/network-binding-20261009/relay-client.env`，已被 Git 忽略。只向获得授权的开发同事提供此文件，其中的 `OPENAI_API_KEY` 是独立代理 token。该 token 允许使用本机代理并消耗同一个 MiniMax 套餐额度，仍应作为凭据保管。

普通前端使用者无需此 token；在页面的“模型来源”选择“MiniMax 国内 Token Plan”即可。该选择只影响聊天推理；OCR、embedding、rerank 保持各自配置。

## 调用示例

在同事的机器上设置客户端环境变量，`OPENAI_API_KEY` 使用交付文件中的独立 token：

```text
OPENAI_BASE_URL=http://10.149.131.175:8092/v1
OPENAI_API_KEY=<独立 relay token>
OPENAI_MODEL=MiniMax-M3
```

Python OpenAI SDK：

```python
import os
from openai import OpenAI

client = OpenAI(
    base_url=os.environ["OPENAI_BASE_URL"],
    api_key=os.environ["OPENAI_API_KEY"],
    timeout=620,
    max_retries=0,
)
response = client.chat.completions.create(
    model="MiniMax-M3",
    messages=[{"role": "user", "content": "用一句话介绍自己。"}],
    max_tokens=512,
    stream=False,
    extra_body={"thinking": {"type": "disabled"}, "reasoning_split": True},
)
print(response.choices[0].message.content)
```

Linux/macOS curl（假设客户端环境变量已设置）：

```bash
curl "$OPENAI_BASE_URL/chat/completions" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"MiniMax-M3","messages":[{"role":"user","content":"Reply exactly OK."}],"max_tokens":64,"stream":false,"thinking":{"type":"disabled"},"reasoning_split":true}'
```

Windows PowerShell：

```powershell
$requestBody = @{
    model = 'MiniMax-M3'
    messages = @(@{ role = 'user'; content = 'Reply exactly OK.' })
    max_tokens = 64
    stream = $false
    thinking = @{ type = 'disabled' }
    reasoning_split = $true
} | ConvertTo-Json -Depth 10
$reply = Invoke-RestMethod -Method Post -Uri "$env:OPENAI_BASE_URL/chat/completions" `
    -Headers @{ Authorization = "Bearer $env:OPENAI_API_KEY" } `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($requestBody))
$reply.choices[0].message.content
```

## 当前接口范围

- 支持 `GET /v1/models` 和非流式 `POST /v1/chat/completions`，均需 bearer token。
- 模型为 `MiniMax-M3`；`stream=true` 会明确返回 400。
- 输出预算范围 1–16,384 tokens，`n=1`。请求体最大 2 MiB，官方响应最大 8 MiB。
- 最多同时两个官方请求；额外调用返回 429 `relay_busy`。总处理连接上限八个，慢请求也计入。
- 单次上游超时 600 秒；客户端应设置相应超时。仅对明确的 HTTP 529 `overloaded_error` 自动等待 2 秒、5 秒重试，最多三次尝试；等待期间继续占用原有上游槽位。
- 此 OpenAI 接口用于文本聊天，未实现 embedding、rerank、OCR、Responses 或音视频接口。

官方 `529 overloaded_error` 表示 MiniMax 上游集群负载高。Relay 的有限重试耗尽后会保留最后一次状态码和脱敏后的官方 JSON。示例 OpenAI SDK 使用 `max_retries=0`，避免与代理重试叠加。401、429、其他 5xx、网络超时和模型语义错误均不会触发代理重试。529 与本机 Bonsai GPU 性能无关，短请求成功也不保证后续长请求一定成功。

应用端 MiniMax adapter 也采用相同的三次尝试和 2 秒／5 秒退避，仅处理明确的 529 过载。该策略直接作用于官方调用，不经 LAN relay；harness 的模型回答校验、变量识别规则和语义修复流程保持原样。若三次均过载，前端仍会显示 529，需要等待上游恢复。默认 OpenAI/Bonsai/Ollama 不启用这一 MiniMax 专用策略。

当前采用 HTTP，适用于受信任局域网或 Tailscale；没有部署公网入口或 TLS。Windows 防火墙保持开启，仅在 Private/Domain 配置文件中放行 5173、8080 和 8092，规则绑定对应可执行文件。Bonsai 的 8091 仍只监听 loopback。

## 维护

PowerShell 7.4+ 在仓库目录执行：

```powershell
& '.local/tools/powershell/pwsh.exe' -NoProfile -File scripts/local.ps1 -Action status
& '.local/tools/powershell/pwsh.exe' -NoProfile -File scripts/local.ps1 -Action start
& '.local/tools/powershell/pwsh.exe' -NoProfile -File scripts/local.ps1 -Action restart-minimax-relay
```

`restart-minimax-relay` 只重启收据确认归属的代理进程。系统任务 `ConSense-Local` 使用统一启动脚本恢复缺失服务。

官方凭据和独立 token 从 Windows 系统环境变量的最新值读取。轮换 relay token 后使用 `restart-minimax-relay`，同时向授权客户端更新交付配置；无需修改官方 key。轮换官方 key 后，代理需单独重启，应用后端需 `restart-backend` 才会加载新值。

离线验证：

```powershell
& 'C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' `
    -m unittest discover -s scripts/tests -p test_minimax_relay.py -v
```

本次验证收据位于 `.local/state/network-binding-20261009/lan-relay-acceptance.json`。已经通过本机 LAN IP 完成 HTTP/API/认证和真实生成验证，尚未使用同事的外部设备验证网络可达性。

Vetting 显式 MiniMax profile 目前缺少对应 token observer，额外来源包可能以 `budget_unknown` 被门控。配置接通和小请求成功不代表完整大上下文 Vetting 已验收；不要使用本地 Bonsai 的 token 计数充当云模型预算证明。
