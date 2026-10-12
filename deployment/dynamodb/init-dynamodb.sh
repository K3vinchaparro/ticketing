#!/bin/sh
set -eu

ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:8000}"
export AWS_PAGER=""

ddb() {
  aws dynamodb "$@" --endpoint-url "$ENDPOINT"
}

echo "Waiting for DynamoDB at $ENDPOINT..."
attempts=0
until ddb list-tables >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 30 ]; then
    echo "DynamoDB did not become available" >&2
    exit 1
  fi
  sleep 1
done

table_exists() {
  ddb describe-table --table-name "$1" >/dev/null 2>&1
}

if table_exists events; then
  echo "Table events already exists, skipping"
else
  ddb create-table --table-name events \
    --attribute-definitions AttributeName=eventId,AttributeType=S \
    --key-schema AttributeName=eventId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST >/dev/null
  echo "Table events created"
fi

if table_exists orders; then
  echo "Table orders already exists, skipping"
else
  ddb create-table --table-name orders \
    --attribute-definitions \
      AttributeName=orderId,AttributeType=S \
      AttributeName=status,AttributeType=S \
      AttributeName=expiresAt,AttributeType=N \
    --key-schema AttributeName=orderId,KeyType=HASH \
    --global-secondary-indexes '[{
      "IndexName": "status-expiresAt-index",
      "KeySchema": [
        {"AttributeName": "status", "KeyType": "HASH"},
        {"AttributeName": "expiresAt", "KeyType": "RANGE"}
      ],
      "Projection": {"ProjectionType": "ALL"}
    }]' \
    --billing-mode PAY_PER_REQUEST >/dev/null
  echo "Table orders created"
fi

if table_exists idempotency_keys; then
  echo "Table idempotency_keys already exists, skipping"
else
  ddb create-table --table-name idempotency_keys \
    --attribute-definitions AttributeName=idempotencyKey,AttributeType=S \
    --key-schema AttributeName=idempotencyKey,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST >/dev/null
  ddb update-time-to-live --table-name idempotency_keys \
    --time-to-live-specification "Enabled=true,AttributeName=expiresAt" >/dev/null
  echo "Table idempotency_keys created"
fi

if table_exists ticket_audit; then
  echo "Table ticket_audit already exists, skipping"
else
  ddb create-table --table-name ticket_audit \
    --attribute-definitions \
      AttributeName=entityId,AttributeType=S \
      AttributeName=occurredAt,AttributeType=S \
    --key-schema \
      AttributeName=entityId,KeyType=HASH \
      AttributeName=occurredAt,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST >/dev/null
  echo "Table ticket_audit created"
fi

echo "DynamoDB initialization completed"
