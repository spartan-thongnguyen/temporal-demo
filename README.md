# Temporal checkout demo

This demo is the order checkout from the "Temporal Deep Dive" video, as an app that you can run. It uses Kotlin, Spring Boot, and the Temporal Java SDK. One process is both the REST API (the Temporal client) and the worker.

The flow is the one in the video: reserve, charge, wait up to 3 days for the bank, ship, notify. Fake inventory, payment, carrier, and email systems record every side effect in `data/world.json`. You can count the charges after each crash.

## What maps to the video

| Video scene | Code | How you see it |
|---|---|---|
| Part 2 · Workflow | `CheckoutWorkflow.kt`, `CheckoutWorkflowImpl.kt` | `./demo.sh happy` |
| Part 2 · Activity | `CheckoutActivitiesImpl.kt` | `>>>` lines in the app log |
| Part 2 · Worker | `application.yml`, `@WorkflowImpl`, `@ActivityImpl` | `./run.sh` |
| Part 2 · Client | `CheckoutController.kt` | the `curl` calls in `demo.sh` |
| Part 3 · Event history | the Temporal Service | Web UI, or `temporal workflow show -w order-<id>` |
| Part 3 · Replay | `trace()` in `CheckoutWorkflowImpl.kt` | kill the app while it waits, then restart it |
| Part 3 · Retries | `fail503` in `Order` | `./demo.sh happy`: the charge succeeds on attempt 2 |
| Part 3 · Idempotency | `chargePayment`, `FakeWorld.kt` | `./demo.sh crash` |
| Part 4 · Saga | the `try`/`catch` and the `!paid` branch | `./demo.sh declined`, `./demo.sh timeout` |
| Part 4 · Signal, Query, Update | `bankConfirmed`, `status`, `changeAddress` | `./demo.sh bank`, `status`, `address` |
| Part 5 · Versioning (CI replay test) | `CheckoutWorkflowTest.kt` | `mvn test` |

## Prerequisites

You need these tools:

- Java 17. If `JAVA_HOME` is not set, `run.sh` uses `/opt/homebrew/opt/openjdk@17`.
- Maven.
- A Temporal Service on `127.0.0.1:7233`, with the Web UI.

If a Temporal Service already listens on port 7233, use it. For example, a Docker container can provide it, with the Web UI on http://localhost:8233. Otherwise, start a local server in its own terminal:

```
temporal server start-dev
```

To use a different address, set `TEMPORAL_ADDRESS`, for example `TEMPORAL_ADDRESS=host:7233 ./run.sh`.

## Step 1: Start the app

Run this command in a terminal in this directory. The first run builds the jar.

```
./run.sh
```

The API listens on http://localhost:8081. Keep this terminal visible. It shows each side effect (`>>>`) and each workflow decision (`LIVE` or `REPLAY`).

## Step 2: Kill the worker while the order waits

This is the main demo: Part 3, Replay. Use a second terminal for the `demo.sh` commands.

1. Start an order:

   ```
   ./demo.sh happy 123
   ```

2. Read the status until it shows `AWAITING_BANK`:

   ```
   ./demo.sh status 123
   ```

3. Stop the app with Ctrl+C in the first terminal. This is the pod crash.
4. Open the order in the Web UI. The workflow is still running. The last event is `TimerStarted`.
5. Start the app again:

   ```
   ./run.sh
   ```

6. Send the bank signal:

   ```
   ./demo.sh bank 123
   ```

The app log shows the replay. The reserve and charge steps print `REPLAY`, because the history answers them and nothing runs again. The ship and notify steps print `LIVE`. Then `./demo.sh status 123` returns `DONE`, and `./demo.sh world` shows one charge.

You can also send the signal while the app is down. The Temporal Service stores the signal. When the app starts, it finishes the order:

```
temporal workflow signal -w order-123 --name bankConfirmed
```

## Step 3: Run the other scenarios

Each command starts a new order. If you do not give an ID, the script uses the time of day.

| Command | What happens |
|---|---|
| `./demo.sh crash` | The charge succeeds, then the app stops itself before it reports the result. Run `./run.sh`. After the 10 s start-to-close timeout, Temporal retries the charge with the same idempotency key. The fake provider returns the first payment and charges nothing new. |
| `./demo.sh declined` | The card is declined. The activity throws a non-retryable failure. The workflow releases the stock and ends with `PAYMENT_FAILED`. |
| `./demo.sh timeout` | The bank signal never arrives. After 30 s, the workflow refunds the payment, releases the stock, and ends with `CANCELLED`. |
| `./demo.sh address <id> "<address>"` | An update. The workflow rejects a blank address, and rejects any change after shipping starts (HTTP 409). After the order finishes, the API returns 404. |
| `./demo.sh world` | Shows what the fake systems recorded: reservations, payments, shipments, emails, and idempotency keys. |

To make your own order, send JSON to `POST /checkout`. All fields except `id` are optional. See `Model.kt`.

```
curl -X POST localhost:8081/checkout -H 'Content-Type: application/json' \
  -d '{"id":"200","bankWait":"PT2M","fail503":3}'
```

## Step 4: Run the tests

The tests use the Temporal test server in memory. Time skipping makes the 3-day timer finish in milliseconds. You do not need a running Temporal Service.

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn test
```

The tests do these checks:

- The bank signal arrives. The order ships, with one charge after a 503.
- The bank signal never arrives. After 3 days of test time, the saga refunds and releases.
- The card is declined. Nothing is retried and no money moves.
- A query and an update work on a running order.
- The same idempotency key gives one charge.
- Replay: the current code matches a recorded history, and a version that adds a step before the charge fails with a `NonDeterministicException`.

## Notes

- The video shows the idempotency key as `order-123:7`. The Java SDK uses a UUID as the activity ID, so the real key looks like `order-123:e7aa91f6-...`. The key stays the same on every retry.
- A query on an order that is not in the worker cache replays the workflow code. For example, this happens after a restart, or after the order finishes. That is why `REPLAY` lines can appear after `./demo.sh status`.
- To reset the fake systems, stop the app and delete the `data` directory. The workflow histories stay in the Temporal Service.
