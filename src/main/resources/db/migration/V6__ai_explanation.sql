-- Cache derived explanations only; never store the prompt, birth date, name or API key.
CREATE TABLE ai_explanation (
 cache_key VARCHAR(64) PRIMARY KEY,
 state VARCHAR(20) NOT NULL,
 paragraphs TEXT,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE ai_generation_budget (
 id INTEGER PRIMARY KEY,
 budget_day VARCHAR(10) NOT NULL,
 used INTEGER NOT NULL
);
INSERT INTO ai_generation_budget VALUES (1,'',0);
CREATE TABLE ai_generation_quota (
 owner_hash VARCHAR(64) PRIMARY KEY,
 bucket VARCHAR(13) NOT NULL,
 used INTEGER NOT NULL
);
