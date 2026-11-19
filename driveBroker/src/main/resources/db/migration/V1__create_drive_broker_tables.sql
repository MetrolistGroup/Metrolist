CREATE TABLE google_drive_accounts (
    google_subject TEXT NOT NULL,
    oauth_client_id TEXT NOT NULL,
    refresh_token_iv BYTEA NOT NULL,
    refresh_token_ciphertext BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (google_subject, oauth_client_id)
);

CREATE TABLE google_drive_sessions (
    token_hash BYTEA PRIMARY KEY,
    google_subject TEXT NOT NULL,
    oauth_client_id TEXT NOT NULL,
    platform TEXT NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'DESKTOP')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (google_subject, oauth_client_id)
        REFERENCES google_drive_accounts (google_subject, oauth_client_id)
        ON DELETE CASCADE
);

CREATE INDEX google_drive_sessions_account_idx
    ON google_drive_sessions (google_subject, oauth_client_id);
