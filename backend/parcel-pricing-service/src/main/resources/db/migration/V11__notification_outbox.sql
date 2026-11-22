-- Additive, future transitions only. Reuses the existing disabled-by-default outbox relay.
CREATE FUNCTION parcel.inbox_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE eid uuid := gen_random_uuid();
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
    INSERT INTO parcel.outbox_event(event_id,aggregate_id,event_type,payload,occurred_at)
    VALUES(eid,NEW.id,'notification.parcel',jsonb_build_object(
      'eventId',eid,'eventType','notification.parcel','occurredAt',now(),'aggregateId',NEW.id,
      'payload',jsonb_build_object('status',NEW.status,'trackingRef',NEW.tracking_ref,
        'customerSubject',CASE WHEN NEW.merchant_id IS NULL THEN NEW.customer_subject ELSE NULL END,
        'merchantSubject',NEW.merchant_id)),now());
    RETURN NEW;
END $$;
CREATE TRIGGER trg_parcel_inbox AFTER INSERT OR UPDATE OF status ON parcel.parcel
FOR EACH ROW EXECUTE FUNCTION parcel.inbox_event();
