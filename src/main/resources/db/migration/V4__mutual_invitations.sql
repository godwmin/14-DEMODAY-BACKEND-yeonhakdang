CREATE TABLE member_invitation (
 token UUID PRIMARY KEY,
 member_id UUID NOT NULL UNIQUE REFERENCES member_account(id) ON DELETE CASCADE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE member_friendship (
 member_a UUID NOT NULL REFERENCES member_account(id) ON DELETE CASCADE,
 member_b UUID NOT NULL REFERENCES member_account(id) ON DELETE CASCADE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(member_a,member_b),
 CHECK(member_a <> member_b)
);
CREATE INDEX friendship_member_b_idx ON member_friendship(member_b);
