-- Dispatch schema v1: zones, couriers, orders, assignments.
-- See DESIGN.md for the state machines these statuses follow.

CREATE TABLE zones (
    id          text PRIMARY KEY,
    name        text NOT NULL,
    center_lat  double precision NOT NULL CHECK (center_lat BETWEEN -90 AND 90),
    center_lng  double precision NOT NULL CHECK (center_lng BETWEEN -180 AND 180),
    radius_km   double precision NOT NULL CHECK (radius_km > 0),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE couriers (
    id            uuid PRIMARY KEY,
    zone_id       text NOT NULL REFERENCES zones (id),
    name          text NOT NULL,
    status        text NOT NULL DEFAULT 'OFFLINE'
                  CHECK (status IN ('OFFLINE', 'AVAILABLE', 'OFFERED', 'BUSY')),
    -- When the courier last became AVAILABLE; the fairness tie-break uses it.
    idle_since    timestamptz,
    last_seen_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX couriers_zone_status_idx ON couriers (zone_id, status);

CREATE TABLE orders (
    id               uuid PRIMARY KEY,
    idempotency_key  text NOT NULL UNIQUE,
    -- Hash of the creation request, so a reused key with a different body can be rejected.
    request_hash     text NOT NULL,
    zone_id          text NOT NULL REFERENCES zones (id),
    pickup_lat       double precision NOT NULL CHECK (pickup_lat BETWEEN -90 AND 90),
    pickup_lng       double precision NOT NULL CHECK (pickup_lng BETWEEN -180 AND 180),
    dropoff_lat      double precision NOT NULL CHECK (dropoff_lat BETWEEN -90 AND 90),
    dropoff_lng      double precision NOT NULL CHECK (dropoff_lng BETWEEN -180 AND 180),
    tier             text NOT NULL DEFAULT 'STANDARD' CHECK (tier IN ('STANDARD', 'PRIORITY')),
    status           text NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'OFFERED', 'ASSIGNED', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- The engine scans a zone's pending orders; a partial index keeps that scan small as delivered orders pile up.
CREATE INDEX orders_pending_by_zone_idx ON orders (zone_id, created_at) WHERE status = 'PENDING';

CREATE TABLE assignments (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      uuid NOT NULL REFERENCES orders (id),
    courier_id    uuid NOT NULL REFERENCES couriers (id),
    status        text NOT NULL DEFAULT 'OFFERED'
                  CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'COMPLETED', 'CANCELLED')),
    distance_m    double precision NOT NULL CHECK (distance_m >= 0),
    offered_at    timestamptz NOT NULL DEFAULT now(),
    responded_at  timestamptz,
    CHECK ((status = 'OFFERED') = (responded_at IS NULL))
);

-- The double-assignment guards: at most one live (offered or accepted) assignment per order and per courier.
-- An ACCEPTED row stays live until the order is delivered (COMPLETED) or cancelled (CANCELLED).
CREATE UNIQUE INDEX assignments_one_live_per_order
    ON assignments (order_id) WHERE status IN ('OFFERED', 'ACCEPTED');
CREATE UNIQUE INDEX assignments_one_live_per_courier
    ON assignments (courier_id) WHERE status IN ('OFFERED', 'ACCEPTED');

CREATE INDEX assignments_order_idx ON assignments (order_id);
