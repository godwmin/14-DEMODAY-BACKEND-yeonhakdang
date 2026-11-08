CREATE TABLE account_analysis_profile (
 member_id UUID PRIMARY KEY REFERENCES member_account(id) ON DELETE CASCADE,
 profile TEXT NOT NULL,
 revision BIGINT NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
