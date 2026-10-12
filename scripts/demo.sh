#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
ADMIN_API_KEY="${ADMIN_API_KEY:-local-admin-key}"
CAPACITY="${CAPACITY:-10}"
BUYERS="${BUYERS:-100}"

json_field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^,\"}]*\)\"\{0,1\}.*/\1/p" | head -n 1; }
step() { printf '\n== %s\n' "$1"; }

step "1. Create an event with $CAPACITY tickets"
event=$(curl -sS -X POST "$BASE_URL/events" \
  -H 'Content-Type: application/json' -H "X-Api-Key: $ADMIN_API_KEY" \
  -d "{\"name\":\"Rock Fest\",\"date\":\"2099-01-01T20:00:00Z\",\"venue\":\"Movistar Arena\",\"capacity\":$CAPACITY}")
echo "$event"
event_id=$(echo "$event" | json_field eventId)

step "2. $BUYERS customers try to buy 1 ticket at the same time"
seq 1 "$BUYERS" | xargs -P 50 -I{} curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE_URL/orders" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$event_id-{}" \
  -d "{\"eventId\":\"$event_id\",\"customerId\":\"customer-{}\",\"quantity\":1}" | sort | uniq -c \
  | sed 's/^/   HTTP responses: /'
echo "   202 = accepted and queued. 409 = rejected up front because the event was already full."

step "3. Wait for the asynchronous consumer and check the inventory"
sleep 5
curl -sS "$BASE_URL/events/$event_id/availability"; echo
echo "   Expected: reserved=$CAPACITY, available=0. Never more than the capacity."

step "4. Place one more order: the event is sold out, so it is rejected up front and never queued"
curl -sS -w '\n   HTTP %{http_code} (409 = not enough tickets)\n' -X POST "$BASE_URL/orders" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$event_id-late" \
  -d "{\"eventId\":\"$event_id\",\"customerId\":\"late-customer\",\"quantity\":1}"

step "5. Send the same purchase twice with the same Idempotency-Key (no duplicate is created)"
second_event=$(curl -sS -X POST "$BASE_URL/events" \
  -H 'Content-Type: application/json' -H "X-Api-Key: $ADMIN_API_KEY" \
  -d '{"name":"Jazz Night","date":"2099-01-01T20:00:00Z","venue":"Teatro Colon","capacity":5}')
second_event_id=$(echo "$second_event" | json_field eventId)
for attempt in first second; do
  curl -sS -o /dev/null -w "   $attempt attempt: HTTP %{http_code}\n" -X POST "$BASE_URL/orders" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$second_event_id-retry" \
    -d "{\"eventId\":\"$second_event_id\",\"customerId\":\"retry-customer\",\"quantity\":1}"
done
echo "   Expected: 202 (order created), then 200 (the same order is returned)."
sleep 2
curl -sS "$BASE_URL/events/$second_event_id/availability"; echo
echo "   Expected: reserved=1. The retry did not reserve a second ticket."

printf '\nDone. Event id: %s\n' "$event_id"
echo "Reservations return to the inventory when they expire (RESERVATION_TTL, 10 minutes by default)."
