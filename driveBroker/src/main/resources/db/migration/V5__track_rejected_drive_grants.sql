ALTER TABLE google_drive_sessions ADD COLUMN refresh_token_hash BYTEA;

CREATE INDEX google_drive_sessions_grant_idx
    ON google_drive_sessions (google_subject, oauth_client_id, refresh_token_hash);
