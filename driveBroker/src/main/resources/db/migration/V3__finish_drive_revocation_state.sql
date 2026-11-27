ALTER TABLE google_drive_revocations ADD COLUMN finished_at TIMESTAMPTZ;

-- A broker restart discards in-flight revocations, so leftover markers from the old
-- schema are finished and cannot block a new sign-in. Recent sign-ins retry because
-- their token issue time predates this marker.
UPDATE google_drive_revocations SET finished_at = CURRENT_TIMESTAMP;
