#!/usr/bin/env bash
# Starts the NoQ notification service (8081) and the main app (8080) with one command.
#   ./run-demo.sh          -> in-memory H2 database (no MySQL needed, data resets on restart)
#   ./run-demo.sh mysql    -> MySQL (reads noq-app/.env, see noq-app/.env.example)
# Press Ctrl+C to stop both. Logs: .demo-logs/notification.log and .demo-logs/app.log
set -m   # job control: every background job gets its own process group, so we can stop Maven AND its Java child
cd "$(dirname "$0")" || exit 1

die() { echo "ERROR: $*" >&2; exit 1; }   # exiting runs the EXIT trap, which stops anything already started

MODE="${1:-h2}"
PROFILE=""
case "$MODE" in
  h2) PROFILE="-Dspring-boot.run.profiles=h2" ;;
  mysql)
    # application.properties imports noq-app/.env itself (spring.config.import), so we only check it is usable.
    [ -f noq-app/.env ] || die "noq-app/.env not found. Run: cp noq-app/.env.example noq-app/.env  (then set DB_PASSWORD)"
    ! grep -q '^DB_PASSWORD=change-me' noq-app/.env || die "Set your real DB_PASSWORD in noq-app/.env"
    ;;
  *) die "Usage: ./run-demo.sh [h2|mysql]" ;;
esac

for port in 8080 8081; do
  ! lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1 || die "Port $port is already in use. Stop whatever is using it first."
done

PGIDS=""
stopped=""
cleanup() {   # runs once, on any exit; the script's original exit status is kept
  [ -z "$stopped" ] || return
  stopped=1
  echo; echo "Stopping services..."
  for g in $PGIDS; do kill -TERM -- "-$g" 2>/dev/null; done
  for g in $PGIDS; do
    i=0
    while kill -0 -- "-$g" 2>/dev/null && [ $i -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
    kill -KILL -- "-$g" 2>/dev/null   # only if it did not stop in 10 s
  done
  wait 2>/dev/null
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p .demo-logs

echo "Starting notification service on :8081 (log: .demo-logs/notification.log)..."
./mvnw -q -pl noq-notification-service spring-boot:run < /dev/null > .demo-logs/notification.log 2>&1 &
NOTIF=$!
PGIDS="$NOTIF"

echo "Waiting for it to be ready..."
i=0
until curl -fs http://localhost:8081/actuator/health >/dev/null 2>&1; do
  if ! kill -0 $NOTIF 2>/dev/null; then
    tail -n 20 .demo-logs/notification.log >&2
    die "Notification service failed to start (see .demo-logs/notification.log)"
  fi
  [ $i -lt 120 ] || die "Notification service not ready after 120 s (see .demo-logs/notification.log)"
  i=$((i + 1)); sleep 1
done

echo "Starting NoQ app on :8080 in '$MODE' mode (log: .demo-logs/app.log)..."
echo
echo "  Customer menu : http://localhost:8080/"
echo "  Staff login   : http://localhost:8080/staff   (staff / staff123, kitchen / kitchen123)"
echo "  Notifications : http://localhost:8081/api/notifications"
echo
echo "Ctrl+C to stop both."
echo

set -o pipefail
./mvnw -pl noq-app spring-boot:run $PROFILE < /dev/null 2>&1 | tee .demo-logs/app.log &
APP=$!   # the pipeline's last process (tee); it shares one process group with Maven and Java
PGIDS="$NOTIF $(ps -o pgid= -p $APP | tr -d ' ')"

wait $APP
rc=$?
[ $rc -eq 0 ] || echo "ERROR: NoQ app exited with status $rc (see .demo-logs/app.log)" >&2
exit $rc
