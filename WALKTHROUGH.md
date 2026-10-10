# Dispatch: walkthrough

The questions an interviewer is likely to ask about this project, with short answers that point at the code. The
README has the measured results and DESIGN.md the full rules; this file is for explaining them out loud. Paths are
under `src/main/java/io/github/vivekdavara/dispatch/` unless they start with `src/test`.

## The project in a minute

**What is it?** A backend that matches delivery orders to couriers in real time. A customer posts an order; the
engine picks the nearest free courier with a priority rule, claims both in one Postgres transaction, and pushes the
offer to the courier's phone over a WebSocket. The courier accepts or declines; a decline, 30 s of silence, or a
dropped connection sends the order to the next courier. Java 21, Spring Boot 3.5, PostgreSQL 14 (Flyway), Redis GEO.

**Where does state live?** Postgres is the source of truth: zones, couriers, orders, and one `assignments` row per
offer. Redis holds only live courier positions (a GEO set per zone plus a 60 s "seen" key per courier), because
pings arrive every few seconds per courier and never need to touch Postgres. Presence (who has a socket open) is in
the app's memory.

**What's measured?** A load simulator replays 50,000 synthetic events (10,000 orders, 40,000 location pings) over
HTTP and one real WebSocket per courier. With a courier free, an order's first offer arrives in about 8 ms at the
median (client clock, POST sent to offer on the socket). One engine optimisation cut mean pass time 8× to 20× and
the p99 by a third, and in doing so exposed a fairness bug in the old pass; the README has the numbers.

**What would you point at first?** The claim in [`AssignmentEngine.claim`](src/main/java/io/github/vivekdavara/dispatch/assignment/AssignmentEngine.java)
(two compare-and-sets and an insert), [`PassScheduler`](src/main/java/io/github/vivekdavara/dispatch/assignment/PassScheduler.java)
(coalescing without losing a request), and the priority-inversion finding in the README.

## The matching rule

**Which order goes next?** Highest `tierBonus + minutesWaiting` first, where PRIORITY is worth 10 minutes
([`domain/PriorityScore.java`](src/main/java/io/github/vivekdavara/dispatch/domain/PriorityScore.java)). Ties go to
the older order, then the smaller id, so the order is total and the engine is deterministic.

**Can a standard order starve?** No. Waiting time grows without bound and the bonus is fixed, so a standard order
that has waited 10 minutes outranks every priority order created after it. `PriorityScoreTest` checks the crossover.

**Which courier gets it?** The nearest `AVAILABLE` courier with a fresh ping within 5 km
([`domain/CourierRanking.java`](src/main/java/io/github/vivekdavara/dispatch/domain/CourierRanking.java)). Couriers
within 50 m of the nearest count as tied, because GPS noise is bigger than that, and the tie goes to whoever has
been idle longest (then the smaller id). The rest of the ranking is the fallback list if the winner is claimed by
another pass first.

**Why the idle-time tie-break?** Fairness. Without it, the courier parked closest to a busy restaurant takes every
order from it. `idle_since` is reset on decline and expiry too, so turning work down never keeps your place in line
([`OfferService.release`](src/main/java/io/github/vivekdavara/dispatch/assignment/OfferService.java)).

**Why greedy nearest-courier instead of an optimal matching (Hungarian, min-cost flow)?** It minimises pickup time
for the order in hand, which is what the customer feels; it's O(candidates) per order, deterministic and easy to
explain. A batch matcher minimises total distance across all orders and pays for it in latency (you have to wait to
collect a batch) and in explainability. Both rule pieces are pure functions with no I/O, so a global matcher could
replace `CourierRanking` behind the same call.

**Why straight-line distance?** No routing engine in scope. Haversine
([`domain/GeoPoint.java`](src/main/java/io/github/vivekdavara/dispatch/domain/GeoPoint.java)) is cheap and Redis
`GEOSEARCH` speaks it; the error is a river or a highway between courier and pickup. A real system would rank the
top few by ETA from a routing service.

**Can a courier who declined an order be offered it again?** No. A refusal is data, not memory: every `DECLINED`
or `EXPIRED` assignment row excludes that courier for that order, read by the pass in the same query as the pending
orders (`AssignmentRepository.pendingWithRefusals`, an `ARRAY(…)` subquery). It survives restarts for free.

## Correctness under concurrency

**How do you make sure a courier never gets two orders at once?** The database enforces it. Two partial unique
indexes allow at most one live (`OFFERED` or `ACCEPTED`) assignment per order and per courier
([`V1__init.sql`](src/main/resources/db/migration/V1__init.sql)). The code never relies on them in the normal path:
every state change is a compare-and-set on the row's current status, e.g. `UPDATE couriers SET status = 'OFFERED'
WHERE id = ? AND status = 'AVAILABLE'`, and zero rows updated means someone else got there first. The indexes are
the backstop that turns an engine bug into a loud constraint violation instead of a silent double assignment.

**Walk me through a claim.** One transaction
([`AssignmentEngine.claim`](src/main/java/io/github/vivekdavara/dispatch/assignment/AssignmentEngine.java)): CAS
the order `PENDING → OFFERED` (zero rows: another pass took it, skip the order); then down the ranking, CAS each
courier `AVAILABLE → OFFERED` until one succeeds; insert the assignment; commit. If every ranked courier was taken
meanwhile, roll back and the order stays `PENDING`. Only after the commit is the `Offer` event published, so the
courier can never answer an offer that isn't in the database yet.

**Why compare-and-set rather than `SELECT … FOR UPDATE` or `SERIALIZABLE`?** Every transition here is a single-row
status change, and a conditional `UPDATE` is atomic on its own: one round trip, and the row lock lasts only until
the short transaction commits. It's safe under Postgres's default `READ COMMITTED` because an `UPDATE` that waited
on a concurrent writer re-checks its `WHERE` against the row's new version before changing it. `SERIALIZABLE`
would give the same guarantee but with serialization failures to retry; here the loser of a race just moves on to
the next courier.

**Can claims and answers deadlock?** No, because locks are always taken in one order. A claim locks its order, then
courier rows one at a time, and holds a courier only at its last step (the insert, which waits on nothing). Answers
(accept, decline, expiry, disconnect, delivery) lock assignment, then order, then courier. A claim never touches an
existing assignment row, and a CAS that doesn't match (`status = 'AVAILABLE'` on an `OFFERED` courier) takes no
lock, so no cycle can form. `ConcurrentDispatchTest` runs eight passes over one zone at the same instant, five
times: 12 couriers, 30 orders, exactly 12 offers, no constraint errors.

**A courier accepts at the same moment the offer expires. Who wins?** Whoever moves the assignment row first. Accept,
decline and expiry all start with `UPDATE assignments … WHERE id = ? AND status = 'OFFERED'`, so exactly one of
them updates it; the order and courier updates that follow can't lose, and if one ever did, `follow()` throws and
the whole transaction rolls back rather than leave the three rows disagreeing
([`OfferService`](src/main/java/io/github/vivekdavara/dispatch/assignment/OfferService.java)). `OfferServiceTest`
races accept against decline and against expiry, five times each, and checks the order and courier match the winner.

**How do you test races at all?** Real Postgres and Redis, committed data in a throwaway zone
([`src/test/.../support/Fixtures.java`](src/test/java/io/github/vivekdavara/dispatch/support/Fixtures.java)), threads
released together by a `CountDownLatch`, and `@RepeatedTest(5)`. The assertions are about end states (exactly one
order, exactly one winner, rows that agree), which hold whichever thread wins.

## Idempotency

**How is order creation idempotent?** Every `POST /api/v1/orders` carries an `Idempotency-Key`. The order row
stores the key (unique) and a SHA-256 of a canonical form of the request
([`OrderService.create`](src/main/java/io/github/vivekdavara/dispatch/order/OrderService.java),
[`RequestHash`](src/main/java/io/github/vivekdavara/dispatch/order/RequestHash.java)). New key: 201. Same key and
same body: 200 with the original order and `Idempotent-Replayed: true`. Same key, different body: 409.

**What if the original and the retry arrive at the same time?** The insert is `INSERT … ON CONFLICT
(idempotency_key) DO NOTHING RETURNING …`. The loser gets no row back instead of a unique-violation error, reads the
winner, and replays it (or returns 409 if the bodies differ). `ConcurrentOrderCreationTest` fires 16 simultaneous
requests with one key, five times: always exactly one order.

**Why hash a canonical form instead of the raw body?** So formatting doesn't matter: whitespace, field order,
`42.35` versus `42.350`, and an explicit `"tier": "STANDARD"` versus an omitted tier all hash the same.

**What does a replay return?** The order as it is now (its status may have moved on to `OFFERED` or `DELIVERED`),
not a stored copy of the first response. That's simpler and is what a client polling for its order wants; the
trade-off is that a replay isn't byte-for-byte the first response, which is what Stripe-style idempotency stores.

**Are courier answers idempotent too?** Since day 4, yes: repeating an accept or decline that already took effect
returns the same assignment (and acks again on the socket) instead of a 409, because an app whose connection
dropped before the acknowledgement can't know whether its answer landed. A *different* answer to a settled offer is
still a 409. The simulator found this one: before the fix, every retried answer was refused.

**Do keys expire?** No; the unique column is permanent, and keys are global rather than per client. A production
API would scope keys to the caller's credentials and expire them after a day or so. There is no authentication yet
(see the README's limitations), so there's no caller to scope them to.
