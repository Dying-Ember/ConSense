# Drafting 来源清单引导语的回溯耗时修复

2026-10-20 持续三模块端到端测试期间，全量后端冻结运行的三次有限只读线程采样中，两次命中 `OerTemplateEvidence.sourceListIntroduction`，第三次已进入原生 DOCX 索引，不能把整个运行称为卡死。原谓词的两个重叠 `.*` 在没有结尾冒号的长正文上反复寻找 Unicode 字母和冒号。独立字符读取计数在 513、1025、2049、4097 字符输入上分别为 395,523、1,577,475、6,300,675、25,184,259；长度翻倍时工作量接近四倍。

产品只改 `OerTemplateEvidence.java`：在同一 `trim()` 后的字符串上，先检查严格结尾的冒号／可选单个横线，再检查 Unicode 字母是否存在。两条静态正则的 matcher 仍为调用内对象。所有直接调用点已核对：`units` 的无编号和有编号成员，以及 `articleReplacementNumber` 的前置判断。没有页码、文件名或原件特判，没有结果缓存、来源修订或业务采用状态变化，分析版本仍为 `native-template-rules-20261019-r21`。

原语义完整保留：Java `trim()` 只去除两端不大于 U+0020 的字符；默认 `\s` 仍为 ASCII 空白；`\p{L}` 仍支持 Unicode／补充平面字母；尾部只允许一个可选的 ASCII `-`、en dash 或 em dash。新结尾用 `\z`，避免 `find()` 配合 `$` 接受 U+0085／U+2028／U+2029 前的伪结尾；空白使用 possessive 量词，且可选横线与空白不相交。结尾后不可能有字母，因此独立检查字母存在没有把冒号前字母要求放宽。

正式最终 RED 与 GREEN 使用同一测试文件。RED03 共 11 项，10 通过、1 个预期性能失败，0 error、0 skip；实际私有谓词处理 65,537 字符普通正文耗时 21.4637646 秒，触发 1 秒门。GREEN02 九个相关 suite 共 270 项全部通过，0 error、0 skip，`BUILD SUCCESS`，同一输入耗时 0.0024698 秒。测试还覆盖 30,941 个短串穷举、5,000 个固定种子混合 Unicode 输入，以及换行、冒号、单横线、组合字符、补充平面字母、孤立 surrogate 和非 ASCII 空白边界。

三份实际原件的完整 `ParsedDocument`（含来源证据、coverage、块与全文）、全部原生段落的清单判断，以及包含私有字段／原 XML 的完整 `TemplateIndex`，均与 RED03 完整字节一致。比较使用 `Files.mismatch == -1`，不删字段、不忽略数值、不归一化内容。三份原件本身的字节和 SHA256 也保持。

| 原件 | 源 SHA256 | 原生段落 | 完整解析 JSON 字节 | 完整索引字节 | 解析前／后本机观察秒 |
| --- | --- | ---: | ---: | ---: | ---: |
| NTT | `60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c` | 669 | 53,671,418 | 3,138,884 | 2.7900130 / 2.4783402 |
| SCT | `7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4` | 1,243 | 86,824,903 | 5,983,668 | 3.1434659 / 2.6338160 |
| SCC | `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208` | 2,087 | 152,468,347 | 10,386,442 | 9.0987304 / 2.9078461 |

合计 3,999 个原生段落、292,964,668 字节完整解析、19,508,994 字节完整索引，另有完整逐段 lexical 决策文件。表中时间包含正常解析和来源证据构建，不含快照序列化；RED 使用 4 GiB、GREEN 使用 16 GiB，且当时存在其他并发测试与实际服务调用，因此只是本机观察，不是服务承诺，也不是整个业务流程的提速比例。

原先两轮 RED 分别保留为 11 项中 10 通过、1 个性能失败。首 GREEN 的 provider 在完成 5 个索引测试后终止：验收 helper 把 `valueToTree` 节点与 JSON 反序列化节点直接比较，JUnit 展开约 152 MB expected／actual 时发生 4 GiB heap OOM，新的 6 项测试未形成完整 XML，不能记为通过。该轮保存的三源解析／lexical／索引九个文件随后独立逐字节复算，首差异均为 -1。helper 改为完整持久化 JSON 的流式字节比较并保留紧凑差异 offset，最终 RED03／GREEN02 用同一修订测试重新执行；旧 OOM 与不完整状态没有覆盖。

执行从已核验的 Round06 冻结源 `534f0925749971bec2fc6bb7325aa7a841da98537c1ef17dccce4fc5725abb52` 的 1,482 个源文件派生，只增加本次测试，GREEN 再覆盖唯一产品文件。最终 GREEN02 冻结源为 `b536960bfd60196b81e9cfda13bdc11243794f762f76c839af549b3c119509be`。每轮完成后重新核对全部 1,483 个源文件。全量 Round06 的运行源码、输出目录、进程均未修改；本工作没有实际 LLM／OCR 调用、API 写入或服务重启，运行部署由主任务另行控制。

完整可重算入口在忽略目录 `.local/e2e-20261020/`：

- `drafting/round07-list-introduction-summary.json`：五轮正式状态、全部 XML／日志哈希、逐用例结果、原件输入和九份完整输出比较、字符读取计数、三次有限线程采样、源文件复核。
- `drafting/round07-aborted-green-raw-byte-comparison.json`：首 GREEN 废票的完整九文件流比较，显式保留 provider OOM／未完成状态。
- `vetting/drafting07-final-peer-review.json`：独立 peer 的最终 0 Must 结论、九文件逐块对照，以及最终整合源与绿色冻结源的两份 SHA 复核。
- `build/round07-{baseline,red,green,red03,green02}-final/`：五轮各自完整 Surefire reports，原票分别保留。
- `build/round07-red03-evidence/` 与 `build/round07-green02-evidence/`：全部三源解析、逐段 lexical 决策与时间记录；对应 `*-index/` 为完整原生索引。
- `drafting/freeze_round07.py`、`run_round07.ps1`、`summarize_round07.py`：从固定源构建／运行／冻结和复算；输出均使用独立目录，不允许覆盖既有证据。

这证明本次最小正则修复的来源语义保持、长正文性能门和相关契约回归；不代替全量后端结果、实际模型质量或浏览器业务验收。独立 peer 对产品语义与测试边界给出 0 Must，最终集成包还须由主任务运行自身的既定检查。
