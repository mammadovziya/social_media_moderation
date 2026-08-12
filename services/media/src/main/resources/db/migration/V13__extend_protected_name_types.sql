-- Widen the protected-name taxonomy.
--
-- One flat "block this name" list does not work, because the entity kinds behave differently. A
-- state holding or payment network carries an identity a member may never claim, and it may match
-- inside a longer handle. A listed issuer or a political party does not: members legitimately
-- follow and discuss them, so only the whole name is refused. The type column is what separates
-- those two behaviours, so it has to carry the distinction.

ALTER TABLE moderation_protected_names
    DROP CONSTRAINT moderation_protected_names_type;

ALTER TABLE moderation_protected_names
    ADD CONSTRAINT moderation_protected_names_type
        CHECK (name_type IN (
            'OWN_BRAND',
            'OWN_PRODUCT',
            'BANK',
            'BROKER',
            'REGULATOR',
            'STATE_ENTITY',
            'PAYMENT_NETWORK',
            'ISSUER',
            'POLITICAL_PARTY',
            'STAFF_ROLE',
            'RESERVED'
        ));
