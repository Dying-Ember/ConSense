#!/bin/sh
# Create one role; the caller supplies the target's mounts, network and configuration.
set -eu

if [ "$#" -lt 1 ]; then
    echo 'usage: competition-create-role.sh app|h2|qdrant|source|gpu [docker create options...]' >&2
    exit 2
fi
role=$1
shift
docker_bin=${DOCKER_BIN:-docker}
image_prefix=${IMAGE_PREFIX:-consense-competition}
name=${CONTAINER_NAME:-consense-$role}

case "$role" in
    app)
        exec "$docker_bin" create --name "$name" "$@" --user consense:consense \
            --entrypoint /bin/sh "$image_prefix/app:demo" /artifacts/app-entrypoint.sh
        ;;
    h2)
        h2_heap=${H2_HEAP:-16g}
        h2_bind=${H2_BIND_ADDRESS:-127.0.0.1}
        h2_port=${H2_TCP_PORT:-28682}
        exec "$docker_bin" create --name "$name" "$@" --user consense:consense \
            --entrypoint /runtime/jre/bin/java "$image_prefix/h2:demo" \
            "-Xmx$h2_heap" "-Dh2.bindAddress=$h2_bind" -Djava.io.tmpdir=/runtime/data/h2/tmp \
            -cp /runtime/lib/h2.jar org.h2.tools.Server -tcp -tcpPort "$h2_port" \
            -ifExists -baseDir /runtime/data/h2
        ;;
    qdrant)
        exec "$docker_bin" create --name "$name" "$@" --user consense:consense \
            --entrypoint /runtime/bin/qdrant "$image_prefix/qdrant:demo" \
            --config-path /runtime/config/qdrant-server.yaml
        ;;
    source)
        exec "$docker_bin" create --name "$name" "$@" --user consense:consense \
            --entrypoint /runtime/envs/source/bin/python "$image_prefix/source:demo" \
            -u /runtime/tools/offline_delivery/source_service_entrypoint.py
        ;;
    gpu)
        exec "$docker_bin" create --name "$name" "$@" --user consense:consense \
            --entrypoint /usr/bin/python3 "$image_prefix/gpu:demo" \
            /runtime/tools/offline_delivery/fixed_gpu_worker.py --config /runtime/config/gpu.json
        ;;
    *) echo "unknown role: $role" >&2; exit 2 ;;
esac
