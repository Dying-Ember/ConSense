# 资料打不开时的排查

先记下项目、文档 ID、所选版本、时间和页面错误；接口同时记录 HTTP 状态与业务码。不要将密钥、查询正文或原件内容复制到普通日志或工单。以下示例是本机专用 WSL 预演的路径，赛场以实际部署记录为准。

1. **服务状态。** 在专用 WSL 中使用 `docker --host unix:///run/consense-docker/docker.sock ps`，查看 App、Source、H2、Qdrant、GPU 的当前对象；`ss -ltn` 查看实际监听。预演中 App 为 28680，Source Vetting/Advisory 为 28681/28685。查看对应容器的状态、退出码和受保护日志中的错误阶段；health 成功只证明入口响应，不证明文件可读。
2. **挂载与路径。** 从当前部署记录取得 App 和 Source 的完整容器 ID，以 `docker --host unix:///run/consense-docker/docker.sock inspect --format '{{json .Mounts}}' "$APP_CID"` 和相同方式查看 `"$SOURCE_CID"`。核对宿主路径、容器目标及只读/可写属性。2026-11-01 的预演快照：
   - App `/artifacts` 为只读发布目录，`/runtime/config` 与 `/runtime/app-source` 为只读配置，`/runtime/data/app/uploads` 为可写上传目录。
   - Source `/runtime/tools` 和 `/runtime/index-evidence` 为只读，`/runtime/state/vetting`、`/runtime/state/advisory` 分别为可写状态目录。
   - 宿主预演根为 `/srv/consense-rehearsal-20261029/`。这些是当时实际挂载，换版后必须以当前 `inspect` 和登记为准；原件或旧绝对路径还需对照项目记录及实际路径映射，不能仅按文件名推断。
3. **权限。** 按部署记录检查原件、状态和临时目录的所有者、模式及父目录遍历权限。预演进程要求 `consense` UID/GID `10001:10001`。可从已核容器 PID 的 `/proc/<PID>/status` 查看 `Uid`、`Gid`，用 `stat` 或 `namei -l` 查看已登记路径。root 可读不代表业务进程可读；不要为排查修改活动原件、数据库或密钥权限。
4. **项目与版本。** 对照当前项目/文档 ID、模块、原件 SHA、source/projection revision 与部署清单；检查原件是否存在且与登记摘要一致，缺失、越界、映射错误或版本不符时保留错误，不用同名文件替代。预演 App 的配置入口是 `SPRING_PROFILES_ACTIVE` 及 `SPRING_CONFIG_ADDITIONAL_LOCATION`，当时后者指向 `/runtime/config/` 和 `/runtime/app-source/`；查看实际对象时只摘取非秘密字段。历史索引路由使用 `/runtime/index-evidence/candidate-receipt.json`，需要与当前项目 key 和实际集合相符；不要将旧 CID 或历史回执当作当前运行身份。
5. **恢复与复查。** 状态或版本不明时，先保存非敏感错误与摘要并核实当前运行配置；不要盲目重试请求、重启、重建索引或覆盖文件。确需恢复时按当前运行方案协调，随后以同一项目、文件和版本读取原件及对应页面；一次 health、重启或单件读回不代表所有资料通过。
