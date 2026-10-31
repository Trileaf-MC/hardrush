#!/usr/bin/env bash
# Build HardRush from source and start a reusable Fabric test server in test-server/<instance>.
# macOS / Linux / WSL. Windows: test-server.cmd takes the same options.
set -euo pipefail

usage() {
    cat <<'EOF'
用法：./test-server.sh [选项]

  --instance NAME    实例名，默认 default（test-server/NAME）
  --reset world      删除存档后启动，保留配置、mods 和白名单
  --reset all        实例恢复为全新状态，只保留已下载的原版服务端，不重新下载
  --port PORT        服务端端口，默认 25565
  --seed SEED        新存档种子（配合 --reset world/all 使用）
  --mode MODE        新一轮初始模式 normal | hardcore，默认 hardcore
  --memory SIZE      最大内存，如 2G、4096M，默认 2G
  --offline/--online 关闭／开启正版验证，默认开启
  --no-competition   关闭比赛运行时（--competition 重新开启），默认开启
  --mc VERSION       游戏版本，对应 platforms/mcXXX，默认 26.3
  --no-build         不重新构建，直接用实例里现有的模组启动

除 --reset、--no-build 外，其余选项会记在实例里，之后不用重复传。
EOF
}

root=$(cd "$(dirname "$0")" && pwd)
instance=default
mc=26.3
props=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --instance|--reset|--port|--seed|--mode|--memory|--mc)
            [ $# -ge 2 ] || { echo "$1 需要一个值" >&2; exit 2; }
            case "$1" in
                --instance) instance=$2; props+=("-PtestServerInstance=$2") ;;
                --reset) props+=("-PtestServerReset=$2") ;;
                --port) props+=("-PtestServerPort=$2") ;;
                --seed) props+=("-PtestServerSeed=$2") ;;
                --mode) props+=("-PtestServerMode=$2") ;;
                --memory) props+=("-PtestServerMemory=$2") ;;
                --mc) mc=$2 ;;
            esac
            shift 2 ;;
        --offline) props+=(-PtestServerOnlineMode=false); shift ;;
        --online) props+=(-PtestServerOnlineMode=true); shift ;;
        --competition) props+=(-PtestServerCompetition=true); shift ;;
        --no-competition) props+=(-PtestServerCompetition=false); shift ;;
        --no-build) props+=(-PtestServerNoBuild=true); shift ;;
        *) echo "未知选项：$1（--help 查看用法）" >&2; exit 2 ;;
    esac
done

platform="mc${mc//./}"
if [ ! -f "$root/platforms/$platform/build.gradle" ]; then
    echo "不支持的游戏版本 ${mc}：仓库里没有 platforms/${platform}" >&2
    exit 2
fi

cd "$root"
./gradlew ":platforms:$platform:prepareTestServer" ${props[@]+"${props[@]}"}
exec "$root/test-server/$instance/start.sh"
