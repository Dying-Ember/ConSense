# External App diagnostic logging

The competition App can opt into `app-competition-logback.xml` without changing the default logging configuration. Mount the XML read-only and a dedicated persistent logs directory read-write for the container user. Do not place logs in uploads or the temporary directory. For example, using actual operator-selected host paths:

- mount the reviewed XML at `/runtime/config/app-competition-logback.xml` read-only;
- mount a dedicated directory at `/runtime/logs` with write access for the runtime UID/GID;
- set `LOGGING_CONFIG=file:/runtime/config/app-competition-logback.xml` and `LOGGING_FILE_NAME=/runtime/logs/app.log`.

Boot writes ordinary logs to `app.log`, and the SQL exception helper and transaction template emit a fixed safe message and the exception class, when present, to `app.log.framework` and the console. Their messages, SQL codes, SQLState and exception chains are intentionally withheld. The XML requires an App JAR containing `com.consense.common.SafeExceptionCategory`; deploy that JAR and this configuration together. Both files use size/time rotation and the configured `logging.logback.rollingpolicy` size, history and cap values; the framework archive name is based on the explicit log filename. Preserve any protected original evidence separately; do not rotate it as ordinary logging.

Always use an explicit writable file path. The configuration is opt-in and does not override another external `logging.config`; a replacement config needs its own privacy review. The two framework logger routes do not guarantee that other loggers, user-supplied patterns or subprocess output are free of sensitive data. Confirm directory ownership, startup, restart, rotation, retention, disk-failure behavior and request correlation on the actual target before relying on it.

## Finding an operation and checking storage

Use the actual App log directory and an identifier recorded for the operation; the examples below run in an authorized Linux shell with read access to that directory. Do not paste private log contents into public tickets. This searches the App's two current files and their compressed rotated files only. A missing match can mean the operation was never logged, used another identifier, or was rotated away; it does not prove that the operation did not run.

In the local rehearsal observation at 2026-10-31 07:52 +08, the dedicated host directory was `/srv/consense-rehearsal-20261029/instances/local01/app-logs-20261031-a1`, mounted as `/runtime/logs`, with `app.log` and `app.log.framework` created. At that time the directory was UID/GID 10001 and mode 0700. This is a historical observation, not a claim about the current container: check the current mount and identity before using the path. Other deployments must use their own registered directory.

```sh
LOG_DIR=/path/to/the/registered/app-logs
TASK_ID='replace-with-the-recorded-request-or-task-id'
grep -nF -- "$TASK_ID" "$LOG_DIR/app.log" "$LOG_DIR/app.log.framework"
for file in "$LOG_DIR"/app.log*.gz; do
    [ -f "$file" ] || continue
    zgrep -nF -- "$TASK_ID" "$file"
done
```

Read the matching stage, outcome and error category together with the operation's separately retained Source and GPU receipts. Their identifiers are not necessarily the App or browser identifier: follow a recorded mapping, and do not infer a link from timestamps alone. If no mapping was recorded, the cross-process association is unknown. Raw evidence and task results are separate from these rotating ordinary logs.

For a write failure, check the exact mounted directory and the actual runtime user's access, as well as both free space and free inodes:

```sh
id
stat -c '%U:%G %a %n' "$LOG_DIR"
test -w "$LOG_DIR" && printf '%s\n' 'directory writable by this user' || printf '%s\n' 'directory not writable by this user'
df -h -- "$LOG_DIR"
df -i -- "$LOG_DIR"
```

Run the permission check as the actual App runtime identity or explicitly distinguish the identity used. If the directory, mount, ownership or capacity is wrong, restore the registered persistent destination and its approved access before a coordinated restart or retry. Preserve existing logs and separate evidence; do not delete them to make space without the applicable retention decision. Recheck a new operation and its log after recovery rather than treating a writable directory alone as proof of successful logging.
