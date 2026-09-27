-- Phase 1 retirement: application code no longer reads or writes these tables.
-- Keep the data intact for a stabilization window; a later migration may drop
-- the tables after backup and rollback requirements are confirmed.
COMMENT ON TABLE action_checkin IS 'DEPRECATED by V7: retained temporarily for rollback safety';
COMMENT ON TABLE action_plan IS 'DEPRECATED by V7: retained temporarily for rollback safety';
COMMENT ON TABLE relationship_event IS 'DEPRECATED by V7: retained temporarily for rollback safety';
COMMENT ON TABLE emotion_report IS 'DEPRECATED by V7: retained temporarily for rollback safety';
