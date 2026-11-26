#!/usr/bin/env bash
# ============================================================================
# ms-ai 单机管理脚本（云机 /root/ms-ai/ 下执行）
#
# 用法：
#   bash 10-start-ms-ai.sh start | stop | restart | status
#
# ★ 复刻 ms-demo 里踩出来的三条铁律：
#   1) 找进程只用 pgrep -x java，再用 /proc/PID/cmdline 逐个比对。
#      ★ 绝不能用 pgrep -f 'ms-ai.jar' —— 它会匹配到执行本脚本的 ssh/shell 自身
#        （命令行里就含 jar 名），一 kill 就把自己干掉，表现为「部署静默半成品」。
#   2) 新 .sh 上传后必须确认是 LF（tr -dc '\r' | wc -c == 0），
#      CRLF 会让脚本静默失效。
#   3) 启停统一经本脚本，不要手工 nohup java，方便以后加 JVM 参数。
# ============================================================================
set -u

APP_HOME=/root/ms-ai
JAR="$APP_HOME/ms-ai.jar"
ENV_FILE="$APP_HOME/deploy.env"
LOG_DIR="$APP_HOME/logs"
PID_FILE="$APP_HOME/ms-ai.pid"
JAR_PATTERN="ms-ai.jar"

# ---- JVM 参数：这台机器只有 ~2G 内存，且已经在跑 nacos/gateway/demo 等多个 Java 进程 ----
# SerialGC：单线程 GC，线程少、额外内存开销最小，适合小堆单核盒子
JAVA_OPTS="-Xms192m -Xmx320m -XX:MaxMetaspaceSize=160m -Xss512k \
-XX:+UseSerialGC -XX:TieredStopAtLevel=1 \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$LOG_DIR \
-Djava.awt.headless=true"

cd "$APP_HOME" || exit 1
mkdir -p "$LOG_DIR"

# 只认 java 进程，逐个比对 /proc/PID/cmdline
java_pids_matching() {
    local pat="$1"
    local p
    for p in $(pgrep -x java 2>/dev/null); do
        if tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -qF -- "$pat"; then
            echo "$p"
        fi
    done
}

load_env() {
    [ -f "$ENV_FILE" ] || { echo "缺少 $ENV_FILE"; exit 1; }
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
}

start() {
    local pids
    pids=$(java_pids_matching "$JAR_PATTERN")
    if [ -n "$pids" ]; then
        echo "已在运行，PID: $pids"
        return 0
    fi
    load_env
    if [ -z "${DASHSCOPE_KEY:-}" ]; then
        echo "DASHSCOPE_KEY 为空，请在 $ENV_FILE 里配置"
        exit 1
    fi
    # setsid：脱离当前 ssh 会话，防止 SIGHUP 把服务带走（ms-demo 里踩过）
    setsid nohup java $JAVA_OPTS \
        -jar "$JAR" \
        >> "$LOG_DIR/ms-ai.out" 2>&1 &
    echo $! > "$PID_FILE"
    echo "启动中，PID=$(cat "$PID_FILE")，日志 $LOG_DIR/ms-ai.out"
}

stop() {
    local pids
    pids=$(java_pids_matching "$JAR_PATTERN")
    if [ -z "$pids" ]; then
        echo "未运行"
        return 0
    fi
    for p in $pids; do
        echo "停止 $p"
        kill "$p" 2>/dev/null
    done
    # 等最多 15 秒优雅退出
    for i in $(seq 1 15); do
        sleep 1
        pids=$(java_pids_matching "$JAR_PATTERN")
        [ -z "$pids" ] && { echo "已停止"; return 0; }
    done
    echo "优雅退出超时，强制 kill"
    for p in $pids; do kill -9 "$p" 2>/dev/null; done
}

status() {
    local pids
    pids=$(java_pids_matching "$JAR_PATTERN")
    if [ -z "$pids" ]; then
        echo "未运行"
        return 1
    fi
    for p in $pids; do
        echo "运行中 PID=$p RSS=$(awk '/VmRSS/{printf "%.0fMB", $2/1024}' "/proc/$p/status")"
    done
    echo "--- 健康检查 ---"
    curl -s -m 5 --noproxy '*' -o /dev/null -w "本地 8086 连通性: %{http_code}\n" http://127.0.0.1:8086/
    curl -s -m 8 --noproxy '*' http://127.0.0.1:8086/ai/rag/stats
    echo
}

case "${1:-status}" in
    start)   start ;;
    stop)    stop ;;
    restart) stop; sleep 2; start ;;
    status)  status ;;
    *)       echo "用法: $0 start|stop|restart|status" ;;
esac
