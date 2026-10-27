#!/bin/bash
# Starts, stops and checks the bridge (port 3000) and the drop-box (port 8000) as ordinary background
# processes that belong to no terminal and no AI session: they keep running after you close either.
#
#   ./scripts/bb.sh start|stop|restart|status [bridge|dropbox]     (default: both)
#
# Logs go to logs/, process ids to run/ (both git-ignored). The drop-box PIN is read from dropbox/.pin
# (git-ignored, one line) or the DROPBOX_PIN environment variable.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run"
LOGS="$ROOT/logs"
NODE_BIN="$ROOT/tools/node/bin"
mkdir -p "$RUN" "$LOGS"

PORT_BRIDGE="${PORT:-3000}"
PORT_DROPBOX="${DROPBOX_PORT:-8000}"

pin() {
  if [ -n "${DROPBOX_PIN:-}" ]; then echo "$DROPBOX_PIN"; return; fi
  [ -f "$ROOT/dropbox/.pin" ] && tr -d '[:space:]' < "$ROOT/dropbox/.pin"
}

alive() { [ -f "$RUN/$1.pid" ] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null; }

# Runs a command in its own session, fully detached, logging to logs/<name>.log.
detach() {
  local name="$1" dir="$2"; shift 2
  (
    cd "$dir" || exit 1
    PATH="$NODE_BIN:$PATH" nohup perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV' "$@" >> "$LOGS/$name.log" 2>&1 < /dev/null &
    echo $! > "$RUN/$name.pid"
  )
}

start_one() {
  local name="$1"
  if alive "$name"; then echo "$name: already running (pid $(cat "$RUN/$name.pid"))"; return; fi
  case "$name" in
    bridge)
      [ -x "$NODE_BIN/node" ] || { echo "bridge: Node not found; run scripts/setup-tools.sh first"; return 1; }
      detach bridge "$ROOT/server" node server.js ;;
    dropbox)
      local p; p="$(pin)"
      [ -n "$p" ] || { echo "dropbox: set a PIN first (put it in dropbox/.pin or export DROPBOX_PIN)"; return 1; }
      detach dropbox "$ROOT/dropbox" python3 server.py --port "$PORT_DROPBOX" --pin "$p" ;;
  esac
  sleep 1
  if alive "$name"; then echo "$name: started (pid $(cat "$RUN/$name.pid")), log: logs/$name.log"; else echo "$name: failed to start, see logs/$name.log"; return 1; fi
}

stop_one() {
  local name="$1" pat
  case "$name" in bridge) pat="node server.js" ;; dropbox) pat="server.py --port" ;; esac
  if alive "$name"; then kill "$(cat "$RUN/$name.pid")" 2>/dev/null; fi
  pkill -f "$pat" 2>/dev/null # also catches copies that were started some other way
  rm -f "$RUN/$name.pid"
  echo "$name: stopped"
}

status_one() {
  local name="$1" url
  case "$name" in bridge) url="http://localhost:$PORT_BRIDGE/api/ping" ;; dropbox) url="http://localhost:$PORT_DROPBOX/" ;; esac
  if curl -s -m 3 -o /dev/null "$url"; then echo "$name: running and answering ($url)"; else echo "$name: not answering"; fi
}

targets() { case "${1:-all}" in bridge|dropbox) echo "$1" ;; all|"") echo "bridge dropbox" ;; *) echo "usage: $0 start|stop|restart|status [bridge|dropbox]" >&2; exit 2 ;; esac; }

cmd="${1:-}"
for t in $(targets "${2:-}"); do
  case "$cmd" in
    start) start_one "$t" ;;
    stop) stop_one "$t" ;;
    restart) stop_one "$t"; sleep 1; start_one "$t" ;;
    status) status_one "$t" ;;
    *) echo "usage: $0 start|stop|restart|status [bridge|dropbox]" >&2; exit 2 ;;
  esac
done
