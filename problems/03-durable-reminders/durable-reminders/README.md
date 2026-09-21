# Durable Reminders

A small Spring Boot service that schedules reminders in an IANA time zone, persists them in H2, and delivers them with a background worker. Delivery is a local fake webhook (no email, SMS, or paid provider). Schedule state survives process restarts because it lives in a file database, not in memory.

## Prerequisites

- **JDK 17 or newer** (`java -version`)
- **curl** (or any HTTP client) to exercise the API
- **No Docker, no PostgreSQL, and no global Maven install.** The Maven Wrapper (`./mvnw`) downloads Maven on first use.

Port **8080** must be free.

## Setup and run

From the repository root:

```bash
./mvnw spring-boot:run
```

The app listens on `http://localhost:8080`.

On startup it creates (or reopens) an H2 file database at `./data/scheduler`. Hibernate updates the schema automatically (`ddl-auto: update`). There are no required environment variables and no secrets to configure for local use.

Stop the process with `Ctrl+C`. Data in `./data/` remains, so overdue `SCHEDULED` reminders are picked up again on the next start.

Optional H2 console: `http://localhost:8080/h2-console`

- JDBC URL: `jdbc:h2:file:./data/scheduler`
- User: `sa`
- Password: empty

## How delivery works

`POST` returns **201 Created** immediately. It does not wait for delivery.

A worker polls every **2 seconds**. It claims reminders whose state is `SCHEDULED` and whose `executionInstant` is at or before the current clock, then calls a fake destination.

| Content contains | Simulated HTTP status | Result |
|---|---|---|
| (normal text) | 200 | `DELIVERED` |
| `FAIL_ONCE` | 503 on the first attempt, 200 after | back to `SCHEDULED` with a later `executionInstant`, then `DELIVERED` |
| `FAIL_ALWAYS` | 500 every attempt | `FAILED` after **3** attempts |

Retry delay is exponential: `30s * 2^(attempt-1)` (30s, 60s, then terminal failure). There is no `RETRYING` state. A temporary failure returns the reminder to `SCHEDULED`.

States: `SCHEDULED`, `RUNNING`, `DELIVERED`, `CANCELLED`, `FAILED`.

Invalid edits or cancels (for example, cancelling a delivered reminder) return **409 Conflict**. A missing id returns **404**. A malformed body returns **400**.

`GET` returns the reminder only. Attempt history is stored in the `delivery_attempts` table and in application logs, not nested in the JSON body.

## API

Base path: `http://localhost:8080/api/reminders`

| Method | Path | Success |
|---|---|---|
| `POST` | `/api/reminders` | 201 Created |
| `GET` | `/api/reminders/{id}` | 200 |
| `PUT` | `/api/reminders/{id}` | 200 (only while `SCHEDULED`) |
| `DELETE` | `/api/reminders/{id}` | 200, state `CANCELLED` |

Request body for create and edit:

```json
{
  "content": "Team standup",
  "targetZoneId": "Asia/Kolkata",
  "requestedLocalTime": "2026-09-21T18:30:00"
}
```

`targetZoneId` is an IANA zone such as `UTC`, `Asia/Kolkata`, or `America/New_York`. `requestedLocalTime` is wall-clock time in that zone (no offset in the string). The service converts it to a UTC `executionInstant`. When a local time occurs twice because of a daylight-saving fallback, the **later** offset is used.

## Manual tests with curl

Use a `requestedLocalTime` that is already in the past in the chosen zone if you want the worker to pick the item up on the next poll (within about 2 seconds). Examples below use UTC times in the past relative to a running clock in 2026; if your machine clock is later, any earlier local time still works.

### 1. Create and deliver

```bash
curl -s -D - -X POST http://localhost:8080/api/reminders \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "Team standup",
    "targetZoneId": "UTC",
    "requestedLocalTime": "2020-01-01T00:00:00"
  }'
```

Copy `id` from the JSON body (HTTP status is **201**). Then:

```bash
curl -s http://localhost:8080/api/reminders/PASTE_ID
```

Poll that URL until `"state":"DELIVERED"`.

### 2. Inspect a future reminder (stays scheduled)

```bash
curl -s -X POST http://localhost:8080/api/reminders \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "Far future",
    "targetZoneId": "Asia/Kolkata",
    "requestedLocalTime": "2030-01-01T09:00:00"
  }'
```

`GET` the id. State stays `SCHEDULED` and `executionInstant` is the UTC equivalent of 09:00 in `Asia/Kolkata`.

### 3. Edit before delivery

Create another far-future reminder, then replace time and content (only valid while `SCHEDULED`):

```bash
curl -s -X PUT http://localhost:8080/api/reminders/PASTE_ID \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "Updated copy",
    "targetZoneId": "America/New_York",
    "requestedLocalTime": "2030-06-01T12:00:00"
  }'
```

### 4. Cancel before delivery

```bash
curl -s -D - -X DELETE http://localhost:8080/api/reminders/PASTE_ID
```

Expect **200** and `"state":"CANCELLED"`. The worker will not deliver it.

### 5. Temporary failure, then success (`FAIL_ONCE`)

```bash
curl -s -X POST http://localhost:8080/api/reminders \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "FAIL_ONCE please retry",
    "targetZoneId": "UTC",
    "requestedLocalTime": "2020-01-01T00:00:00"
  }'
```

After the first poll (about 2 seconds), `GET` shows `SCHEDULED` again and a later `executionInstant` (about 30 seconds ahead). Logs show a simulated **503**. After that instant, the next poll delivers it and state becomes `DELIVERED`.

### 6. Retry exhaustion (`FAIL_ALWAYS`)

```bash
curl -s -X POST http://localhost:8080/api/reminders \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "FAIL_ALWAYS forever",
    "targetZoneId": "UTC",
    "requestedLocalTime": "2020-01-01T00:00:00"
  }'
```

The worker tries three times (delays about 30s, then 60s). Final state is `FAILED`. Logs show simulated **500**.

### 7. Restart recovery

1. Create a due reminder (`requestedLocalTime` in the past) and stop the app with `Ctrl+C` **before** it reaches `DELIVERED` (stop immediately after the 201, or create it and kill within the 2 second poll window — easier: create it, confirm `SCHEDULED` if you kill fast enough, or stop after you see it still scheduled).
2. Start again with `./mvnw spring-boot:run`.
3. `GET` the same id. The worker finds the overdue row and moves it to `DELIVERED`.

A reliable variant: create with `FAIL_ONCE`, wait until state is `SCHEDULED` with a future `executionInstant`, stop the app, wait past that instant, start the app, and confirm delivery.

### 8. Error cases

Missing reminder:

```bash
curl -s -D - http://localhost:8080/api/reminders/does-not-exist
```

Expect **404**.

Invalid body (blank content):

```bash
curl -s -D - -X POST http://localhost:8080/api/reminders \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "",
    "targetZoneId": "UTC",
    "requestedLocalTime": "2020-01-01T00:00:00"
  }'
```

Expect **400**.

Cancel or edit after `DELIVERED` (use an id that already delivered):

```bash
curl -s -D - -X DELETE http://localhost:8080/api/reminders/PASTE_DELIVERED_ID
```

Expect **409**.

## Automated tests

Tests use an in-memory H2 database and a controllable clock. They do not sleep for real minutes and do not call an external provider.

All acceptance tests:

```bash
./mvnw test -Dtest=SchedulerIntegrationTest
```

One scenario at a time:

```bash
./mvnw test -Dtest=SchedulerIntegrationTest#ac1_scheduledDelivery_withInjectedClock
./mvnw test -Dtest=SchedulerIntegrationTest#ac2_restartRecovery_overdueWorkDiscoveredOnPoll
./mvnw test -Dtest=SchedulerIntegrationTest#ac3_temporaryFailure_thenRetrySuccess
./mvnw test -Dtest=SchedulerIntegrationTest#ac3_retryExhaustion_reachesFailed
./mvnw test -Dtest=SchedulerIntegrationTest#ac4_duplicateExecution_oneLogicalNotification
./mvnw test -Dtest=SchedulerIntegrationTest#ac5_editBeforeExecution_supersedesOldSchedule
./mvnw test -Dtest=SchedulerIntegrationTest#ac6_cancelBeforeDelivery_noSuccessfulDelivery
./mvnw test -Dtest=SchedulerIntegrationTest#ac6_cancelRace_optimisticLockAbortsStaleWrite
./mvnw test -Dtest=SchedulerIntegrationTest#ac7_timeZones_kolkataAndNewYork_deterministicInstants
./mvnw test -Dtest=SchedulerIntegrationTest#ac7_dstOverlap_usesLaterOffset
```

Verification benchmark (20 items, mixed outcomes, clock advance, one logical notification per success):

```bash
./mvnw test -Dtest=VerificationBenchmarkTest
```

Full suite:

```bash
./mvnw test
```

## Configuration

`src/main/resources/application.yml`:

| Setting | Default | Meaning |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:file:./data/scheduler` | Durable local database |
| `scheduler.retry.max-attempts` | `3` | Attempts before `FAILED` |
| `scheduler.retry.base-delay` | `PT30S` | First retry delay; later delays double |

Optional overrides (do not commit real secrets):

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Project layout

```text
src/main/java/com/durable/scheduler/
  api/            REST controller and request DTO
  service/        create, edit, cancel; zone to UTC instant
  domain/         Reminder state machine, DeliveryAttempt
  worker/         poll, claim, finalize
  delivery/       DeliveryService, WebhookClientPort
  infrastructure/ MockWebhookClientAdapter
  retry/          RetryPolicy and exponential backoff
  repository/     Spring Data JPA
```

## Out of scope

Authentication, recurring schedules, real push/email/SMS, a distributed queue, and a management UI are intentionally not included.
