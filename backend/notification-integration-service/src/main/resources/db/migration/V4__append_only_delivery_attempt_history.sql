-- Existing notification_attempt/webhook_delivery remain the API's logical-delivery summaries.
-- Capture each completed attempt in the same transaction as its summary update.
CREATE TABLE notification.delivery_attempt_history (
    delivery_kind VARCHAR(16) NOT NULL CHECK (delivery_kind IN ('NOTIFICATION', 'WEBHOOK')),
    delivery_id UUID NOT NULL,
    event_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    outcome VARCHAR(16) NOT NULL,
    response_class VARCHAR(16) NOT NULL,
    http_status INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    next_retry_at TIMESTAMPTZ,
    terminal BOOLEAN NOT NULL,
    PRIMARY KEY (delivery_kind, delivery_id, attempt_number)
);

CREATE FUNCTION notification.capture_delivery_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    kind TEXT;
    response INTEGER;
BEGIN
    IF NEW.attempts <= OLD.attempts THEN RETURN NEW; END IF;
    IF NEW.attempts <> OLD.attempts + 1 THEN
        RAISE EXCEPTION 'Attempt numbers must increase by one';
    END IF;
    kind := CASE WHEN TG_TABLE_NAME = 'webhook_delivery' THEN 'WEBHOOK' ELSE 'NOTIFICATION' END;
    IF kind = 'WEBHOOK' THEN response := NEW.response_status; END IF;
    INSERT INTO notification.delivery_attempt_history
        (delivery_kind, delivery_id, event_id, attempt_number, outcome, response_class, http_status,
         started_at, finished_at, next_retry_at, terminal)
    VALUES (kind, NEW.id, NEW.event_id, NEW.attempts, NEW.status,
        CASE WHEN response IS NOT NULL THEN (response / 100)::TEXT || 'xx'
             WHEN NEW.status IN ('SENT', 'SUCCEEDED') THEN 'SUCCESS'
             ELSE 'DELIVERY_ERROR' END,
        response, transaction_timestamp(), clock_timestamp(), NEW.next_attempt_at,
        NEW.status IN ('SENT', 'SUCCEEDED', 'FAILED'));
    RETURN NEW;
END; $$;

CREATE TRIGGER notification_attempt_history AFTER UPDATE ON notification.notification_attempt
    FOR EACH ROW EXECUTE FUNCTION notification.capture_delivery_attempt();
CREATE TRIGGER webhook_attempt_history AFTER UPDATE ON notification.webhook_delivery
    FOR EACH ROW EXECUTE FUNCTION notification.capture_delivery_attempt();

CREATE FUNCTION notification.reject_attempt_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Delivery attempt history is append-only'; END; $$;
CREATE TRIGGER append_only_attempt_history BEFORE UPDATE OR DELETE ON notification.delivery_attempt_history
    FOR EACH ROW EXECUTE FUNCTION notification.reject_attempt_history_mutation();
