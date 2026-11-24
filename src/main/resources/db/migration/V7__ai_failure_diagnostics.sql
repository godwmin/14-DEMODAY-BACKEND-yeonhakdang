-- Only fixed, application-defined codes are persisted; never provider bodies or keys.
ALTER TABLE ai_explanation ADD COLUMN failure_code VARCHAR(50);
