CREATE TABLE blocked_pdq_hashes_revision (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    revision BIGINT NOT NULL DEFAULT 0
);

INSERT INTO blocked_pdq_hashes_revision (singleton, revision)
VALUES (TRUE, 0);

CREATE FUNCTION increment_blocked_pdq_hashes_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE blocked_pdq_hashes_revision
    SET revision = revision + 1
    WHERE singleton = TRUE;
    RETURN NULL;
END;
$$;

CREATE TRIGGER blocked_pdq_hashes_revision_trigger
AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON blocked_pdq_hashes
FOR EACH STATEMENT
EXECUTE FUNCTION increment_blocked_pdq_hashes_revision();
