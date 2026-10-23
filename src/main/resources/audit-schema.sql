-- Operational tracing belongs in JSON logs. Persist only observed booking-write outcomes.
CREATE TABLE IF NOT EXISTS reservation_audit (
 audit_id VARCHAR(36) PRIMARY KEY,
 trace_id VARCHAR(32) NOT NULL,
 conversation_id VARCHAR(36) NOT NULL,
 turn_id VARCHAR(36) NOT NULL,
 tool_call_id VARCHAR(36),
 reservation_id VARCHAR(80) NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS reservation_audit_trace ON reservation_audit(trace_id,created_at);
CREATE INDEX IF NOT EXISTS reservation_audit_business ON reservation_audit(reservation_id,created_at);
CREATE INDEX IF NOT EXISTS reservation_audit_conversation ON reservation_audit(conversation_id,created_at);
