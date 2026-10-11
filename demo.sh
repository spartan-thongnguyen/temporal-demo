#!/usr/bin/env bash
# Drives the checkout demo with curl. Start the app first: ./run.sh
set -euo pipefail

API="${API:-http://localhost:8081}"
UI="${TEMPORAL_UI:-http://localhost:8233}"

new_id() { date +%H%M%S; }
call() { curl -sS -X "$1" "$API$2" -H 'Content-Type: application/json' ${3:+-d "$3"}; echo; }

start() {   # start <id> <json fields>
  call POST /checkout "{\"id\":\"$1\"$2}"
  echo "history: $UI/namespaces/default/workflows/order-$1"
  echo "next:    ./demo.sh status $1    ./demo.sh bank $1"
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  happy)    id="${1:-$(new_id)}"; start "$id" ',"fail503":1' ;;               # charge succeeds on attempt 2
  declined) id="${1:-$(new_id)}"; start "$id" ',"card":"DECLINED"' ;;         # non-retryable -> saga
  timeout)  id="${1:-$(new_id)}"; start "$id" ',"bankWait":"PT30S"' ;;        # no bank signal -> cancel in 30 s
  crash)    id="${1:-$(new_id)}"; start "$id" ',"crashAfterCharge":true' ;;   # app dies after the charge
  bank)     call POST /webhooks/bank "{\"orderId\":\"$1\"}" ;;                 # signal
  status)   call GET "/orders/$1/status" ;;                                    # query
  address)  call PUT "/orders/$1/address" "{\"address\":\"$2\"}" ;;            # update
  world)    call GET /world ;;                                                 # fake provider state
  *)
    cat <<'EOF'
usage: ./demo.sh <command> [order-id] [address]
  happy    [id]       start an order; the payment provider fails once with a 503
  declined [id]       start an order with a declined card (saga releases stock)
  timeout  [id]       start an order whose bank never confirms (cancels after 30 s)
  crash    [id]       start an order; the app dies right after the charge
  bank     <id>       bank webhook -> signal bankConfirmed
  status   <id>       query the workflow's status
  address  <id> <a>   update the shipping address (rejected once shipped)
  world    -          what the fake inventory, payments, carrier and email saw
EOF
    ;;
esac
