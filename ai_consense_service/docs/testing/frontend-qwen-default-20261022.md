# 前端冷启动默认 Qwen3.8 27B

用户要求质量优先、正常使用 Qwen3.8 27B，同时保留 Flash-Next 显式切换。实际新 origin 冷启动时，原 store 先设 null，获取后端 `defaultProfile=local` 后会选本地模型，偏离本任务的默认选择。

本轮最小修复在 store 使用 `storedLlmProfile() ?? 'qwen'`，加载配置清单时保留当前选择。明确保存的 local、minimax-cn、qwen、qwen-flash 继续有效；所选配置缺失或不可用时显示该状态，不静默切换。未改后端默认语义或省略 profile 的 API 行为，没有把界面默认假称用户已保存的偏好。

根因回归使用真实 Pinia／Vue store，只模拟存储和服务边界：原实现3 PASS／7 FAIL，采用后10 PASS；既有渲染控件脚本的10条 PASS、TypeScript 类型检查和 Vite 构建均实际通过。控件测试中的本地模型场景现在显式保存 local，继续检验操作开始时模型选择不会随随后切换而改变。

真实浏览器 `http://localhost:5177` 在存储偏好为 null 时显示 qwen／qwen3.8-27b／配置就绪；没有清理存储或发送新问题。此结果仅证明前端选择与配置显示，不证明新模型质量或额度。证据在 `.local/frontend-qwen-cold-default38/{root-store-green01.tap,root-controls-green01.log,root-typecheck01.log,root-build01.log,real-cold-origin147.json}`。
