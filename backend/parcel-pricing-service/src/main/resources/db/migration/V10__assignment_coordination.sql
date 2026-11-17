-- Coordination only; apply through an explicitly approved deployment, never this task.
CREATE TABLE parcel.assignment_claim (
 operation_id UUID PRIMARY KEY,
 -- No FK: an ABORTED missing-parcel operation must retain a release tombstone.
 parcel_id UUID NOT NULL,
 actor_subject VARCHAR(255) NOT NULL,
 mode VARCHAR(16) NOT NULL CHECK (mode IN ('ASSIGN','ASSIGNED')),
 state VARCHAR(16) NOT NULL CHECK (state IN ('HELD','CONFIRMED','RELEASED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX assignment_claim_one_held ON parcel.assignment_claim(parcel_id) WHERE state='HELD';
