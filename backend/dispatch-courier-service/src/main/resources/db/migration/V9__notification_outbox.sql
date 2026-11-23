-- Future committed assignment/review changes only; no proof replay or status mutation.
-- V8's reviewer check omitted REJECTED although the review API records its reviewer.
-- Replace that check additively so a genuine rejection can commit and emit its event.
DO $$ DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='dispatch.proof_review'::regclass
    AND contype='c' AND pg_get_constraintdef(oid) LIKE '%reviewed_by_subject%'
  LOOP EXECUTE format('ALTER TABLE dispatch.proof_review DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE dispatch.proof_review ADD CONSTRAINT ck_review_decision_reviewer CHECK (
  (decision IN ('APPROVED','REJECTED','COMPLETION_PENDING','COMPLETED','COMPLETION_FAILED','BLOCKED'))
  = (reviewed_by_subject IS NOT NULL AND reviewed_at IS NOT NULL));

CREATE FUNCTION dispatch.inbox_assignment_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE eid uuid := gen_random_uuid();
BEGIN
    INSERT INTO dispatch.outbox_event(event_id,aggregate_id,event_type,payload,occurred_at)
    VALUES(eid,NEW.parcel_id,'notification.assignment',jsonb_build_object(
      'eventId',eid,'eventType','notification.assignment','occurredAt',NEW.occurred_at,'aggregateId',NEW.parcel_id,
      'payload',jsonb_build_object('action',NEW.action,'oldCourierId',NEW.old_courier_profile_id,
        'newCourierId',NEW.new_courier_profile_id)),NEW.occurred_at);
    RETURN NEW;
END $$;
CREATE TRIGGER trg_assignment_inbox AFTER INSERT ON dispatch.assignment_audit
FOR EACH ROW EXECUTE FUNCTION dispatch.inbox_assignment_event();

CREATE FUNCTION dispatch.inbox_review_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE eid uuid := gen_random_uuid(); parcel uuid; courier text; revision integer;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.decision IS NOT DISTINCT FROM OLD.decision THEN RETURN NEW; END IF;
    IF NEW.decision = 'COMPLETION_PROCESSING' THEN RETURN NEW; END IF;
    SELECT p.parcel_id,r.submitted_by_subject,r.revision_no INTO parcel,courier,revision
      FROM dispatch.proof_revision r JOIN dispatch.proof_of_delivery p ON p.id=r.proof_id
      WHERE r.id=NEW.revision_id;
    INSERT INTO dispatch.outbox_event(event_id,aggregate_id,event_type,payload,occurred_at)
    VALUES(eid,parcel,'notification.proof',jsonb_build_object(
      'eventId',eid,'eventType','notification.proof','occurredAt',now(),'aggregateId',parcel,
      'payload',jsonb_build_object('decision',NEW.decision,'courierSubject',courier,
        'reviewId',NEW.id,'revision',revision,'reason',NEW.rejection_reason)),now());
    RETURN NEW;
END $$;
CREATE TRIGGER trg_review_inbox AFTER INSERT OR UPDATE OF decision ON dispatch.proof_review
FOR EACH ROW EXECUTE FUNCTION dispatch.inbox_review_event();
