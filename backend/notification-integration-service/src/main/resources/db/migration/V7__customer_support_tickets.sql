CREATE TABLE notification.customer_support_ticket (
  id UUID PRIMARY KEY,
  owner_subject VARCHAR(255) NOT NULL,
  subject VARCHAR(200) NOT NULL,
  category VARCHAR(16) NOT NULL CHECK (category IN ('delivery','billing','account','general')),
  tracking_ref VARCHAR(32),
  message TEXT NOT NULL CHECK (length(message) BETWEEN 10 AND 4000),
  status VARCHAR(16) NOT NULL DEFAULT 'Open' CHECK (status IN ('Open','In progress','Resolved')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX customer_support_ticket_owner ON notification.customer_support_ticket(owner_subject,created_at DESC);
