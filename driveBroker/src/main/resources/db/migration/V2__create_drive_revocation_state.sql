CREATE TABLE google_drive_revocations (
    google_subject TEXT PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
