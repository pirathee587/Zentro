CREATE TABLE dispatch.assignment_operation (
 operation_id UUID PRIMARY KEY,
 parcel_id UUID NOT NULL,
 actor_subject VARCHAR(255) NOT NULL,
 mode VARCHAR(16) NOT NULL CHECK (mode IN ('ASSIGN','ASSIGNED')),
 state VARCHAR(16) NOT NULL CHECK (state IN ('PREPARED','COMMITTED','ABORTED')),
 settled BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX assignment_operation_one_unsettled ON dispatch.assignment_operation(parcel_id) WHERE NOT settled;
