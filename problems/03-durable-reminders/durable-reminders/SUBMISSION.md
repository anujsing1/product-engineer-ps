# Product Engineering Challenge Submission

## Candidate

- **Name:** Anuj Singh
- **Email:** anujsing1@gmail.com
- **GitHub:** https://github.com/caygnus/product-engineer-ps/blob/main/problems/03-durable-reminders
- **Selected problem:** Problem 3: Durable Reminders and Follow-Ups
- **Demo video:** https://canva.link/pjj5txmtzfoxr31

## Run the project

**Prerequisites**

- JDK 17+
- No Docker, Maven, or external database required (H2 file DB + Maven Wrapper)

**Environment / configuration**

Default local run needs no secret environment variables. H2 stores durable state under `./data/scheduler`. For a non-local deployment you could override:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

```text
./mvnw spring-boot:run
```

API base: `http://localhost:8080/api/reminders`  
H2 console (optional): `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/scheduler`)

**Successful scenario**

```text
curl -s -X POST http://localhost:8080/api/reminders \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "Team standup",
    "targetZoneId": "UTC",
    "requestedLocalTime": "2026-09-20T12:05:00"
  }'
```

Wait until due (worker polls every 2s) or use a past `requestedLocalTime`, then:

```text
curl -s http://localhost:8080/api/reminders/{id}
```

Expect `state: DELIVERED`.

**Failure / recovery scenario (`FAIL_ONCE`)**

```text
curl -s -X POST http://localhost:8080/api/reminders \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "FAIL_ONCE please retry",
    "targetZoneId": "UTC",
    "requestedLocalTime": "2026-09-20T12:00:00"
  }'
```

First attempt records `TEMP_FAILURE` and reschedules (exponential backoff from 30s). Next attempt succeeds → `DELIVERED`.  
Use content `FAIL_ALWAYS` to exhaust 3 attempts → terminal `FAILED`.

## Run the tests

```text
./mvnw test
```

Tests use an in-memory H2 database, disable the scheduler tick, inject `MutableClock`, and call `SchedulerWorker.poll()` directly (no real-time sleeps, no paid providers).

## Acceptance scenarios and verification

| Scenario | Status | How verified |
|---|---|---|
| AC1 Scheduled delivery | Done | `ac1_scheduledDelivery_withInjectedClock` |
| AC2 Restart recovery | Done | `ac2_restartRecovery_overdueWorkDiscoveredOnPoll` (overdue durable row + poll) |
| AC3 Temporary failure | Done | `ac3_temporaryFailure_thenRetrySuccess` |
| AC3 Retry exhaustion | Done | `ac3_retryExhaustion_reachesFailed` |
| AC4 Duplicate execution | Done | `ac4_duplicateExecution_oneLogicalNotification` |
| AC5 Edit before execution | Done | `ac5_editBeforeExecution_supersedesOldSchedule` |
| AC6 Cancellation | Done | `ac6_cancelBeforeDelivery_noSuccessfulDelivery` + optimistic-lock race test |
| AC7 Time zones / DST | Done | Kolkata + New York instants; US fall-back overlap → later offset |

**Problem-specific verification benchmark**

```text
./mvnw test -Dtest=VerificationBenchmarkTest
```

**Observed result (local run)**

```text
Tests run: 1, Failures: 0, Errors: 0
=== Verification benchmark results ===
SCHEDULED=0
RUNNING=0
DELIVERED=15
CANCELLED=3
FAILED=2
successfulOccurrenceLogicalDeliveries=15
logicalDeliveriesAfterDupSimulation=16
```

Full suite observed:

```text
Tests run: 11, Failures: 0, Errors: 0
BUILD SUCCESS
```

The benchmark creates 20 items across `Asia/Kolkata` and `America/New_York`, includes delivered / edited / cancelled / `FAIL_ONCE` / `FAIL_ALWAYS`, partially processes then continues (restart-style), simulates duplicate delivery, advances `MutableClock` until settled, and asserts exactly one logical notification per successful occurrence (15).

**Demo failure/recovery path**

Show `FAIL_ONCE` → temp failure → retry → `DELIVERED`, or cancel/edit race with `@Version`.

## Architecture and data flow

```text
Client → ReminderController → ReminderService (zone→UTC Instant)
       → ReminderRepository / AttemptRepository → H2 (file: durable across restarts)

SchedulerWorker (Clock) → findDueWork(now) → markRunning
  → NotificationDestination.deliver(id_version_attempt)
  → markDelivered | markFailed(retry|terminal) + DeliveryAttempt
```

Durable schedule state in H2 is the source of truth (not in-memory timers). The worker drains due `SCHEDULED` rows after restarts the same way it does in steady state.

## Technology choices

**Stack:** Java 17, Spring Boot 3, Spring Data JPA, Bean Validation, **H2** (file for app, mem for tests), Maven Wrapper.

**Why H2 instead of Postgres/Docker:** Reviewers can run `./mvnw spring-boot:run` and `./mvnw test` with zero infrastructure. The exercise asks for durable workflow correctness, not a distributed queue. Multi-worker claiming uses optimistic `@Version` on claim/finalize (stale workers abort) rather than Postgres `SKIP LOCKED`.

**Alternatives considered:** Postgres + `FOR UPDATE SKIP LOCKED` (stronger concurrent claim, Docker required); in-memory-only timers (fail restart AC2).

**Trade-off accepted:** Single-process H2 is enough for the 6–8h scope; production would swap the datasource and optionally add `SKIP LOCKED` claiming.

## Important decisions

1. **Resolve IANA local time to UTC `Instant` at write time** with `withLaterOffsetAtOverlap()` (DST overlap → later occurrence; gaps use Java `atZone` adjustment).
2. **Rich `Reminder` aggregate** — no `setState()`; explicit transitions; `@Version` for edit/cancel vs execution races.
3. **Bounded retries in the worker** — max 3 attempts, exponential backoff `30s * 2^(attempt-1)`; `FAIL_ALWAYS` / exhausted attempts → `FAILED` + `TERMINAL_FAILURE` history.
4. **Delivery behind `NotificationDestination`** — OCP/DIP; mock hooks `FAIL_ONCE` / `FAIL_ALWAYS`; idempotency key `{id}_{version}_{attempt}`.

## Assumptions and limitations

- DST overlap policy: later offset. Nonexistent gap times: JVM `LocalDateTime.atZone` resolution (typically the post-transition offset).
- Mock idempotency map is in-memory (process-local); reminder durability is in H2 file.
- No auth, no real push/email/SMS, no recurring schedules.
- Batch size 10 per poll; poll interval 2s.

## Production and scale

**Now:** File H2, one scheduler, optimistic concurrency, mock destination, max 3 retries with exponential backoff.

**Change first for production:** real `NotificationDestination` + durable idempotency store; replace H2 with Postgres (optionally `SKIP LOCKED` for multi-worker claim throughput); metrics on attempts/lock-aborts; auth and secret management.

**Multiple workers today:** concurrent polls may select the same due row; the first `markRunning`/`save` wins on `@Version`, others abort — no double terminal delivery for a cancelled/edited row. Postgres `SKIP LOCKED` would reduce wasted claim races under high contention.

## AI usage

- **Tool:** Cursor.
- **Used for:** scaffolding, H2 migration, domain/worker/retry policy, tests, benchmark, submission text — from a planned design.
- **Reviewed by:** `./mvnw test` (11 tests, 0 failures) and manual review of state transitions / retry bounds / timezone assertions.

## Credibility note

Describe one product or system you previously helped ship:

- **The problem it solved:** [Fill in]
- **Your personal contribution:** [Fill in]
- **The scale or operational complexity involved:** [Fill in]
- **One difficult engineering or product decision:** [Fill in]
- **A public link or other evidence, when available:** [Fill in]

Confidential details may be anonymized and figures may be approximate.
