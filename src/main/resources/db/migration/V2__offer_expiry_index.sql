-- The expiry sweep looks for offers still OFFERED past their deadline once a second. A partial index keeps that
-- lookup proportional to the number of open offers, not to the whole assignment history.
CREATE INDEX assignments_open_offers_idx ON assignments (offered_at) WHERE status = 'OFFERED';
