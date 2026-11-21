CREATE TABLE notification.assistant_conversation (
  id UUID PRIMARY KEY,
  owner_subject VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  active_request UUID,
  busy_until TIMESTAMPTZ
);
CREATE INDEX assistant_conversation_owner ON notification.assistant_conversation(owner_subject, created_at DESC);
CREATE TABLE notification.assistant_message (
  sequence BIGSERIAL PRIMARY KEY,
  conversation_id UUID NOT NULL REFERENCES notification.assistant_conversation(id) ON DELETE CASCADE,
  role VARCHAR(16) NOT NULL CHECK (role IN ('user','assistant')),
  body TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX assistant_message_conversation ON notification.assistant_message(conversation_id, sequence DESC);
