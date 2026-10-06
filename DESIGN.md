# Dispatch: design

Dispatch is a backend that matches delivery orders to couriers in real time. A customer order comes in, the system
finds the best available courier nearby, offers the order to that courier over a WebSocket, and reassigns it if the
courier declines or doesn't answer in time.

This document is the plan the code follows. Sections marked *(D2+)* describe parts that later milestones build.

## Goals and non-goals

Goals:

- Assign each order to a courier within a few hundred milliseconds of it becoming assignable.
- Never assign one courier two active orders, and never give one order two active couriers, even under concurrency.
- Order creation is idempotent: a client that retries a request with the same idempotency key gets the same order.
- Every decision is explainable: the rule is deterministic, and the inputs (distance, wait time, tier) are stored.

Non-goals: routing along real roads (we use straight-line distance), batching several orders onto one courier,
pricing, and customer-facing tracking.

## Domain

| Concept | What it is | Where it lives |
|---|---|---|
| **Zone** | A service area: a centre point and a radius (e.g. "Boston Back Bay", 3 km). Orders and couriers belong to one zone; matching never crosses zones. | Postgres `zones` |
| **Courier** | A person who delivers. Has a status: `OFFLINE`, `AVAILABLE`, `OFFERED` (an offer is pending), `BUSY` (carrying an order). | Postgres `couriers` (record), Redis (live position) |
| **Order** | A pickup and drop-off location, a tier (`STANDARD` or `PRIORITY`), and a status: `PENDING` → `OFFERED` → `ASSIGNED` → `PICKED_UP` → `DELIVERED`, or `CANCELLED`. | Postgres `orders` |
| **Assignment** | One offer of one order to one courier, with its outcome: `OFFERED` → `ACCEPTED`, `DECLINED` or `EXPIRED`. An order can have many assignments over its life (one per attempt) but only one active one. | Postgres `assignments` |

### State machines

```mermaid
stateDiagram-v2
    direction LR
    state "Order" as O {
        [*] --> PENDING
        PENDING --> OFFERED: courier chosen
        OFFERED --> ASSIGNED: courier accepts
        OFFERED --> PENDING: decline / timeout
        ASSIGNED --> PICKED_UP
        PICKED_UP --> DELIVERED
        PENDING --> CANCELLED
        OFFERED --> CANCELLED
    }
```

A courier moves `AVAILABLE` → `OFFERED` when an order is offered, back to `AVAILABLE` on decline or timeout, to
`BUSY` on accept, and back to `AVAILABLE` on delivery. `OFFLINE` couriers are never candidates.

## The assignment rule

Two questions: **which order goes next**, and **which courier gets it**.

### 1. Which order goes next: the order priority score

Pending orders in a zone are served highest score first:

```
priority(order, now) = tierBonus(order.tier) + minutesWaiting(order, now)

tierBonus(STANDARD) = 0
tierBonus(PRIORITY) = 10
```

So a `PRIORITY` order is treated as if it had already waited 10 extra minutes. Because waiting time keeps growing, a
standard order can never be starved: after 10 minutes it outranks any newly created priority order. Ties go to the
older order, then to the smaller order id, so the order is total and deterministic.

### 2. Which courier gets it: nearest available, with tie-breaks

For the chosen order, candidates are couriers that are `AVAILABLE`, in the order's zone, have reported a location
in the last 60 seconds, and are within `maxPickupKm` (default 5 km) of the pickup point. Among candidates:

1. **Nearest first**, by great-circle (haversine) distance from the courier to the pickup.
2. Distances within **50 m** of each other count as a tie (GPS noise is larger than that). A tie goes to the courier
   who has been **idle longest**, which spreads work fairly.
3. A remaining tie goes to the smaller courier id.

If there's no candidate, the order stays `PENDING` and is retried when a courier becomes available or moves.

Why this rule: nearest-courier is what minimises pickup time for the order in hand, which is what customers feel.
It's greedy (it doesn't minimise total distance across all orders the way a bipartite matching would), and that's a
deliberate trade: it's O(candidates) per order, easy to explain, and a global optimiser can be swapped in behind
the same interface later. The fairness tie-break keeps one well-placed courier from taking every order.

The rule lives in pure functions (`PriorityScore`, `CourierRanking`) with no I/O, so it's unit-tested directly.

## Architecture

```mermaid
flowchart LR
    C[Customer app] -- POST /api/v1/orders --> API[Spring Boot API]
    K[Courier app] -- location pings --> API
    API -- orders, couriers, assignments --> PG[(PostgreSQL)]
    API -- GEOADD / GEOSEARCH --> R[(Redis GEO)]
    API -- offers --> WS[WebSocket hub]
    WS -- offer / accept / decline --> K
    E[Assignment engine] -- pending orders --> PG
    E -- nearby couriers --> R
    API -- events --> E
```

- **PostgreSQL** is the source of truth for orders, couriers and assignments. Schema changes go through Flyway.
- **Redis GEO** holds live courier positions: one sorted set per zone (`couriers:geo:{zoneId}`), updated on every
  ping with `GEOADD`, queried with `GEOSEARCH ... BYRADIUS ... ASC`. Pings arrive far more often than orders, so
  they never touch Postgres on the hot path. A per-courier key with a TTL (`courier:seen:{id}`) marks freshness.
- **Assignment engine** *(D2–D3)*: woken by events (order created, courier became available, offer expired), it
  takes the zone's top pending order, asks Redis for nearby couriers, ranks them with `CourierRanking`, and claims
  the winner in one Postgres transaction.
- **WebSocket hub** *(D3)*: pushes offers to the courier's open connection and receives accept/decline.

### Concurrency: how double-assignment is prevented

The database enforces it, not application code alone:

- A partial unique index allows at most one assignment with status `OFFERED` or `ACCEPTED` per order, and another
  allows at most one per courier.
- The engine claims a courier with a conditional update (`UPDATE couriers SET status='OFFERED' WHERE id=? AND
  status='AVAILABLE'`); if it updates zero rows, someone else got the courier first and the engine tries the next
  candidate.
- Order state changes use the same compare-and-set pattern on `status`.

So two engine threads racing for the same courier can't both win, and a bug in the engine fails loudly with a
constraint violation instead of silently double-assigning.

### Idempotent order creation *(D2)*

`POST /api/v1/orders` requires an `Idempotency-Key` header. `orders.idempotency_key` is unique; a retry with the
same key returns the existing order (and a request with the same key but a different body is rejected with 409).

## Schema (Flyway `V1__init.sql`)

- `zones(id, name, center_lat, center_lng, radius_km)`
- `couriers(id, zone_id, name, status, idle_since, last_seen_at, created_at, updated_at)`
- `orders(id, idempotency_key UNIQUE, zone_id, pickup/dropoff lat-lng, tier, status, created_at, updated_at)`
- `assignments(id, order_id, courier_id, status, distance_m, offered_at, responded_at)` with the two partial unique
  indexes above.

Check constraints keep coordinates in range and statuses to the known values.

## API surface

| Method | Path | Milestone |
|---|---|---|
| GET | `/api/v1/health` (and `/actuator/health`) | D1 |
| POST | `/api/v1/orders` (Idempotency-Key header) | D2 |
| GET | `/api/v1/orders/{id}` | D2 |
| PUT | `/api/v1/couriers/{id}/location` | D2 |
| PUT | `/api/v1/couriers/{id}/status` | D2 |
| WS | `/ws/couriers/{id}` (offers, accept, decline) | D3 |

## Failure handling *(D3–D4)*

- **Offer timeout:** an offer not answered in 30 s expires; the order goes back to `PENDING` and the courier back
  to `AVAILABLE` (and is skipped for that order).
- **Courier disconnect:** a courier whose location goes stale (no ping for 60 s) drops out of candidates; a pending
  offer to a disconnected courier expires normally.
- **Redis loss:** positions are soft state. Couriers resend location every few seconds, so Redis refills itself.

## Measurement plan *(D4)*

A simulator replays 50K synthetic events (orders and location pings) against the API and records assignment
latency (order created → offer sent). Report p50/p95 before and after one optimisation, with the command used.

## Ports

App 8101, Redis 6381, Postgres 14 database `dispatch` on 5432.
