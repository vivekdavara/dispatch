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

## The engine and the event loop

**Walk me through one engine pass.** [`AssignmentEngine.snapshotPass`](src/main/java/io/github/vivekdavara/dispatch/assignment/AssignmentEngine.java):
one query for the zone's `AVAILABLE` couriers (the snapshot; empty means the pass is over), one for up to 200
pending orders in serving order with each order's refusals, then for each order only Redis: `GEOSEARCH` within 5 km
and an `MGET` of the `seen` keys to drop stale couriers. Candidates are the fresh ones in the snapshot that haven't
refused the order; `CourierRanking` orders them and `claim` takes the best. A claimed courier leaves the snapshot,
and the pass stops when the snapshot is empty, because the rest of the backlog can't be served until someone frees
up, and that courier's event will start the next pass.

**What starts a pass?** Events published after a change commits: order created, courier online, offer declined,
offer expired, courier disconnected with an offer, delivery done
([`DispatchNeeded`](src/main/java/io/github/vivekdavara/dispatch/assignment/DispatchNeeded.java)). Plus a 5 s sweep
over zones that still have pending orders. Location pings deliberately don't trigger passes: they're the bulk of the
traffic and almost never change the outcome; the one case they do (a courier driving into range) is what the sweep
is for, so that case waits at most 5 s.

**How does `PassScheduler` coalesce bursts without losing a request?** One `AtomicInteger` per zone: `IDLE`,
`RUNNING`, or `RUNNING_AGAIN` ([`PassScheduler`](src/main/java/io/github/vivekdavara/dispatch/assignment/PassScheduler.java)).
A request either moves `IDLE → RUNNING` and submits a pass, or moves `RUNNING → RUNNING_AGAIN` and returns. A
finishing pass moves `RUNNING → IDLE`, and if that CAS fails (a request arrived meanwhile) it runs again. So 1,000
orders arriving during a pass cost one more pass, and every request is followed by a pass that *started after it*,
which is the property that matters: that pass will see the new order. `PassSchedulerTest.noRequestIsLost` checks it
with a ticket counter: each request takes a ticket, each pass records the highest ticket issued before it began,
and the last pass must have seen the last ticket.

**If concurrent passes are safe, why allow only one per zone?** Two passes over one zone want the same nearest
couriers and the same oldest orders, so they mostly cost each other failed compare-and-sets and lock waits.
Serial per zone, parallel across zones (4 threads), is the cheap way to get all the work and none of the contention.
Correctness never depends on it: the manual `POST /zones/{id}/dispatch` can run a pass alongside the loop.

**What if a pass throws, say Redis is unreachable?** The exception is caught inside the scheduler's loop, logged, and
the zone's state still goes back to `IDLE`, so the next event or sweep retries
(`PassSchedulerTest.aFailingPassDoesNotWedgeTheZone`).

**Events are in-process. What if the app dies between a commit and its event?** The event is lost, and that's fine:
events are hints, the database is the truth. A pending order with no event is picked up by the sweep within 5 s of
the restart, and overdue offers are found by the expiry tick, which reads Postgres. That's why there's no outbox
here: nothing downstream needs exactly-once delivery of these events, only a pass that eventually sees the rows.

**The snapshot is read once per pass. Why is a stale snapshot safe?** Because the claim is still a compare-and-set.
If a courier in the snapshot went offline or was taken by another pass, their `UPDATE … WHERE status =
'AVAILABLE'` matches nothing and the claim falls through to the next courier in the ranking: one wasted statement,
never a wrong offer. A courier who becomes free during the pass isn't in the snapshot, but their own event starts
another pass right after this one. `ConcurrentDispatchTest` runs eight passes stealing couriers from each other's
snapshots.

**What did paging the backlog fix (v1.0.0)?** A pass used to read one batch of 200 pending orders. If every order in
it was unservable (refused by every free courier, or more than 5 km from all of them), the pass ended there and
servable orders behind the batch were never examined: 200 stuck orders at the front wedged the zone. Now, while free
couriers remain, the pass reads the next batch by keyset (`(created_at − bonus, created_at, id) > (…the last
order's…)`, computed from that order's own row), and an order refused by every free courier skips the Redis lookup.
`BacklogPagingTest` (batch size 3) fails 4 of its 5 tests on the old pass. The old per-order pass is left exactly as
measured, one batch, since it's the "before" in the README.

## Offers over WebSockets

**Why a plain WebSocket?** The courier needs server push (offers) and a way to answer, and there are only a handful
of message types ([`ws/CourierMessages.java`](src/main/java/io/github/vivekdavara/dispatch/ws/CourierMessages.java)). STOMP
over SockJS would add a broker protocol for nothing; server-sent events are one-way, so answers would need HTTP
anyway. A real courier app also needs push notifications (APNs, FCM) for when it's backgrounded; that's out of scope.

**What if the courier isn't connected when they're offered an order?** The offer is real anyway: the socket is a
delivery channel, not the source of truth. If they connect within the 30 s, the hub sends the open offer right after
`hello`; if not, it expires and the order moves on. Since v1.0.0 an app without a socket can poll
`GET /api/v1/couriers/{id}/offer` ([`ws/OpenOfferController.java`](src/main/java/io/github/vivekdavara/dispatch/ws/OpenOfferController.java)),
which returns the same `offer` message.

**What happens when a courier's socket drops?** They get a 10 s grace to reconnect, and reconnecting cancels the
check ([`CourierSocketHandler.afterConnectionClosed`](src/main/java/io/github/vivekdavara/dispatch/ws/CourierSocketHandler.java)).
If they don't come back, `OfferService.disconnected` runs one transaction: an offer they hold is released (`EXPIRED`,
order back to `PENDING` and re-offered at once instead of after the rest of its 30 s), and the courier goes straight
from `OFFERED` to `OFFLINE`, so no pass can offer them something in between. A `BUSY` courier is left alone: they're
mid-delivery and report pickup and delivery over HTTP.

**Why does the grace check take a lock?** Without it, the timer could see "no session", the courier could reconnect
that instant, and the check would then take a connected courier offline and give their offer away. Connecting
(cancel the check, register the session) and the end of the grace (check for a session, take them offline) hold the
same lock, so one happens entirely before the other: either the check sees the new session and does nothing, or the
courier reconnects to find themselves offline and the app sets itself `AVAILABLE` again. The locks are striped (64
shared by all couriers) so memory doesn't grow with the fleet; two couriers on one stripe only serialize these
short sections.

**Pushes run on the engine thread. Can a slow phone stall the engine?** Not for long, and since v1.0.0 it can't
break it. Each session is wrapped in a `ConcurrentWebSocketSessionDecorator` with a 5 s send limit and a 64 KB
buffer: a send that finds another in flight is queued rather than blocking, and once a send has been stuck for 5 s
or the queue passes 64 KB, the next send throws `SessionLimitExceededException` on whatever thread made it. Before
v1.0.0 nothing caught that, so it escaped through the event publish: it ended the engine pass early, or turned an
HTTP answer that had already committed into a 500. Now `CourierSocketHandler.send` catches it and closes the session
on a virtual thread (closing a stuck connection can block too), which starts the courier's reconnect grace
(`SlowCourierSocketTest`). An offer is a few hundred bytes, so a write to a stalled connection normally lands in the
kernel's send buffer at once. What's left on the pass's thread is one order lookup per offer and the first write; at
larger scale the push would move to its own executor or a pub/sub fan-out so a pass never touches a socket.

**Can a courier get the same offer twice?** Yes, in one window: the resend after a reconnect can race a fresh push
of the same offer. It's harmless by design: the app dedupes by `assignmentId`, and answers are idempotent.

**Why are offers stamped at claim time?** An offer stamped with the start of its pass got a shorter answer window by
however long the pass took to reach it, and the measured latency was off by the same amount (found on day 4). The
stamp is truncated to microseconds, Postgres's precision, so the event and the stored row agree; CI on Linux, whose
clock has nanoseconds, caught that one when `OfferTimestampTest` failed there and passed on the Mac.

## Failure handling

**How do offer timeouts work?** A 1 s tick ([`DispatchLoop.expireOverdueOffers`](src/main/java/io/github/vivekdavara/dispatch/assignment/DispatchLoop.java))
calls [`OfferService.expireDue`](src/main/java/io/github/vivekdavara/dispatch/assignment/OfferService.java), which
reads offers still `OFFERED` past 30 s (served by the V2 partial index on open offers, so it costs the number of open
offers, not the history) in batches of 500 and releases each one with the same compare-and-set transaction as a
decline. Each expiry publishes an event, so the order is re-offered at once: an unanswered offer ends at most
about 31 s after it was made. Since v1.0.0, an offer whose rows are in an impossible state is logged and skipped
instead of ending the tick; before, being the oldest, it would have come first in every tick and stopped all
expiries.

**What if Redis goes down?** Matching stops: passes throw, are logged, and retry on the next event or sweep. Order
intake keeps working (it's Postgres only), and nothing in Redis needs saving: positions are soft state, couriers
re-ping every few seconds, so a fresh Redis refills itself (`dev-up.sh` runs it with persistence off). One thing to
change: `/api/v1/health` returns 503 when either store is down, so a load balancer would pull every instance during
a Redis outage, including order intake that still works. It should split liveness from readiness and degrade.

**What if Postgres goes down?** Every state change fails with a 5xx, failing passes are logged, and health is 503.
Nothing committed is lost. Location pings keep working (Redis only), except a courier's first ping after a restart,
which looks up their zone.

**What if the app crashes in the middle of a pass?** Each claim is its own transaction, so offers already committed
stand and the rest of the pass just didn't happen; the restarted app's sweep runs it again. Open offers expire on
its tick, and couriers who reconnect get theirs resent. What's lost is in memory: the reconnect-grace timers. A
courier who dropped just before the crash and never returns stays `AVAILABLE` in Postgres, but stops being a
candidate when their `seen` key expires, 60 s after their last ping.

**What happens to an order no courier will take?** It stays `PENDING`, its priority growing, and waits for a new
courier in range; there's no attempt cap. Since v1.0.0 it can't block the orders behind it. A production system
would escalate after N refusals or T minutes: a pay boost, a wider radius, or a human dispatcher.

**Tell me about a bug that was hard to find.** The simulator's smoke test failed now and then on "courier 13
reconnect failed": a WebSocket handshake refused, never when the test ran alone, and the bot's retry always got in.
The note only had the exception's class, so the first step was making the bot record what the server answered. That
said 101, so the server had accepted the upgrade and the JDK client had refused the response; the exception's cause
said why: `Response field 'Connection' multivalued: [upgrade, close]`. Tomcat closes a keep-alive connection after
100 requests by adding `Connection: close` to the 100th response, and it does that to a 101 too. The JDK's HTTP
client had opened the socket on a pooled connection that had already served 99 requests. A real courier app's client
can do the same (OkHttp would refuse that response as well), so this was a production bug, not a test problem: in
two 50K runs it hit 3 of the bots' 80 reconnects, which happen mid-run on well-used connections. The fix is one setting
(`server.tomcat.max-keep-alive-requests: -1`), and
[`KeepAliveUpgradeTest`](src/test/java/io/github/vivekdavara/dispatch/ws/KeepAliveUpgradeTest.java) makes it
deterministic: 99 `HEAD` requests and then the upgrade on one raw socket. Then the old reports turned out to have it
too: four of day 4's ten runs had one refused reconnect each, and the README had said "0 socket errors" for all ten
(corrected now). The lessons: make a flaky failure say what happened before trying to fix it, and read every counter
in a report, not just the ones the table shows.

## Measurement

**How did you measure assignment latency?** With a load simulator
([`src/test/.../sim/`](src/test/java/io/github/vivekdavara/dispatch/sim/Simulator.java)) that drives the running jar
only through its public interfaces: HTTP for orders, pings, pickup and delivery, and one real WebSocket per courier.
The scenario is seeded, so a before/after pair replays identical events: 10,000 orders (a 60 s rush at 3×, 20%
priority, 5% sent twice with the same key) and 40,000 pings over 5 minutes in 4 zones, plus 40 injected socket
drops. Bots answer in 0.2 to 1 s, decline 10% and send 5% of answers twice. *First offer* is from just before the
POST is sent to the first offer for that order arriving on any courier's socket, both read from `System.nanoTime()`
in the simulator's JVM; percentiles are exact nearest-rank over all 10,000 orders.

**Why join the timestamps by order id at the end?** Because the offer can reach a courier's socket before the
customer's POST has its response: the pass runs as soon as the insert commits. Timing per request would miss those
or count them negative ([`sim/Recorder.java`](src/test/java/io/github/vivekdavara/dispatch/sim/Recorder.java)).

**What was the optimisation, and what did it buy?** The day-2 pass asked Postgres, once per pending order, which
nearby couriers were free, and walked the whole 200-order batch even when nobody was. The snapshot pass reads the
zone's free couriers once and stops when they're all taken. Mean pass time fell from 15.7 to 2.0 ms (standard) and
from 51.1 to 2.5 ms (overload); p99 first offer from 26.8 to 16.9 s and from 105.6 to 72.2 s; the worst case from
55.1 to 20.4 s and from 166.7 to 78.3 s. With a courier free, the median didn't move (7.5 vs 7.6 ms): the query was
never the bottleneck for an order that finds a courier at once.

**Then why did the overload median get worse (14.2 to 18.4 s)?** Because the old pass was breaking the rule. It
looked couriers up live, so a courier freed while a long pass was on its 150th order went to the 150th order, ahead
of the 149 that outranked it. The simulator counts these priority inversions from Postgres: 522,301 pairs with the old
pass in overload, 82 with the snapshot pass. Strict priority order behaves like first come, first served, which
shortens the tail and raises the median, while the old, effectively random order let some new orders jump the queue.
Split by tier, PRIORITY orders went from a 4.6 s median to 50 ms under overload, which is what the 10-minute bonus is
for; the mean time to an accepted courier didn't change (24.9 s in both).

**How do you know the differences aren't noise?** Same seed, same build, one property flipped
(`dispatch.assignment.candidate-snapshot`), and two rounds run in the order before, after, after, before so drift
can't favour either side; every percentile in seconds agreed within 3% across rounds, except the old pass's
standard max (55.1 vs 65.9 s). The release check later re-ran the default pass six more times on three more builds,
and across all of them the p95, p99 and mean first offer stayed within 1.4% of each other. `CandidateSnapshotTest` shows
both passes make identical offers on a static zone from eight seeds, so the difference under load comes from
behaviour while things change, not from a different rule.

**Why are the p95s in seconds?** The fleet is short on purpose: `standard` is about 20% short during the rush and
`overload` about half. Those seconds are orders queueing for a free courier, which no engine change can remove. The
engine's own share is the server-side median, `created_at` to `offered_at` in Postgres: 3.5 to 4.6 ms.

**What isn't measured?** Maximum throughput (the load is fixed; nothing sweeps it until latency breaks), more than
one app instance, network latency between phones and server, and realistic delivery times (bots deliver in seconds).
The app, Postgres, Redis and the simulator share one laptop, so absolute numbers include their contention; the
comparisons are what the numbers are for.

## Scaling and what's missing

**How would you run more than one instance?** Three things are per-process today. Presence and the grace timers live
in memory, so they'd move to Redis (a key per connected courier with a TTL refreshed by the socket's instance). The
one-pass-per-zone rule lives in `PassScheduler`'s map, so zones would need an owner: a Postgres advisory lock per
zone held for the pass, or zones partitioned across instances; correctness doesn't depend on it (the claims are
compare-and-sets), efficiency does. And events are in-process, so "zone needs a pass" and "push this offer to the
instance holding this courier's socket" would go over a shared channel (Redis pub/sub, Postgres `LISTEN/NOTIFY`, or
Kafka at scale).

**What breaks first at 10× the load?** Not measured, so this is a hypothesis to test with a throughput sweep:
Postgres write load (every offer and every answer is a short transaction updating two or three rows), then serial
passes in a very busy zone (split the zone), then Redis `GEOSEARCH` calls (one per examined order per pass, cheap).

**What's missing before production?** Authentication: couriers name themselves in the request body
([`AssignmentController`](src/main/java/io/github/vivekdavara/dispatch/assignment/AssignmentController.java)), so any
client can act for any courier id. Order cancellation: `CANCELLED` exists in the schema and state machine, but no
endpoint sets it. Escalation for orders nobody takes. Heartbeats on the socket: a half-open TCP connection looks
connected until TCP gives up, and offers sent into it expire after 30 s. Presence in Redis for a second instance.
Rate limits, auth on the actuator endpoints, and alerting.

**What would you do differently?** Put the order's details on the `Offer` event, so a push doesn't read the order
back from Postgres on the engine thread, and do the push on its own executor. Split `/health` into liveness and
readiness, so a Redis outage doesn't take order intake down with it. And write the simulator first: it found three
real bugs that no unit test had (retried answers refused, the priority inversions, and the keep-alive upgrade).

## Where to start reading

1. [`DESIGN.md`](DESIGN.md): "The assignment rule", "Concurrency", "One engine pass, step by step".
2. [`domain/CourierRanking.java`](src/main/java/io/github/vivekdavara/dispatch/domain/CourierRanking.java) and
   [`domain/PriorityScore.java`](src/main/java/io/github/vivekdavara/dispatch/domain/PriorityScore.java): the rule.
3. [`assignment/AssignmentEngine.java`](src/main/java/io/github/vivekdavara/dispatch/assignment/AssignmentEngine.java):
   `snapshotPass`, `offerToBest`, `claim`.
4. [`assignment/OfferService.java`](src/main/java/io/github/vivekdavara/dispatch/assignment/OfferService.java):
   `accept`, `release`, `expireDue`, `disconnected`.
5. [`assignment/PassScheduler.java`](src/main/java/io/github/vivekdavara/dispatch/assignment/PassScheduler.java), then
   [`DispatchLoop.java`](src/main/java/io/github/vivekdavara/dispatch/assignment/DispatchLoop.java).
6. [`ws/CourierSocketHandler.java`](src/main/java/io/github/vivekdavara/dispatch/ws/CourierSocketHandler.java):
   connect, close, `graceEnded`, `send`.
7. [`order/OrderService.java`](src/main/java/io/github/vivekdavara/dispatch/order/OrderService.java) and
   [`V1__init.sql`](src/main/resources/db/migration/V1__init.sql).
8. Tests that make the claims: `ConcurrentDispatchTest`, `OfferServiceTest` (the races), `PassSchedulerTest`
   (`noRequestIsLost`), `CandidateSnapshotTest`, `BacklogPagingTest`, `KeepAliveUpgradeTest`, `SimulatorSmokeTest`.
