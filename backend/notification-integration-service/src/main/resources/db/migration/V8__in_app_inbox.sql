-- Prepared only. Apply to isolated tests first; no historical backfill or worker activation.
CREATE TABLE notification.inbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    recipient_subject VARCHAR(255) NOT NULL,
    portal VARCHAR(16) NOT NULL CHECK (portal IN ('CUSTOMER','MERCHANT','COURIER','DISPATCHER')),
    type VARCHAR(64) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(700) NOT NULL,
    parcel_id UUID NOT NULL,
    target VARCHAR(512) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ,
    UNIQUE(event_id, recipient_subject, portal)
);
CREATE INDEX ix_inbox_owner ON notification.inbox(recipient_subject, portal, occurred_at DESC, id DESC);
CREATE INDEX ix_inbox_unread ON notification.inbox(recipient_subject, portal) WHERE read_at IS NULL;

-- Operational details stay private: never include endpoint URLs, response bodies or signing secrets.
CREATE FUNCTION notification.webhook_inbox() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_subject text;
BEGIN
    IF NEW.status NOT IN ('RETRY','FAILED') OR NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
    SELECT merchant_id INTO owner_subject FROM notification.webhook_subscription WHERE id = NEW.subscription_id;
    INSERT INTO notification.inbox(event_id,recipient_subject,portal,type,title,message,parcel_id,target,occurred_at)
    VALUES(gen_random_uuid(),owner_subject,'MERCHANT','WEBHOOK_' || NEW.status,
      'Webhook delivery ' || lower(NEW.status),
      CASE WHEN NEW.status = 'RETRY' THEN 'A shipment webhook will be retried.' ELSE 'A shipment webhook could not be delivered. Review your webhook settings.' END,
      NEW.parcel_id,'/merchant/dashboard/webhooks',now());
    RETURN NEW;
END $$;
CREATE TRIGGER trg_webhook_inbox AFTER UPDATE OF status ON notification.webhook_delivery
FOR EACH ROW EXECUTE FUNCTION notification.webhook_inbox();
