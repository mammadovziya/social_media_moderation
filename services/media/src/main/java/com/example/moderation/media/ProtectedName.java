package com.example.moderation.media;

/** One entry of the protected-name registry. */
public record ProtectedName(
        long id, String value, String skeleton, NameType nameType, Severity severity) {

    /** What kind of identity the entry protects. */
    public enum NameType {
        /** The operating bank's own brand. */
        OWN_BRAND,
        /** A product or campaign of the operating bank. */
        OWN_PRODUCT,
        /** Another bank. */
        BANK,
        /** A broker, exchange, or investment firm. */
        BROKER,
        /** A regulator or public authority. */
        REGULATOR,
        /** A state body, state-owned company, or state holding. */
        STATE_ENTITY,
        /** A card scheme, payment network, or money-transfer operator. */
        PAYMENT_NETWORK,
        /**
         * A listed company or other traded issuer.
         *
         * <p>Deliberately not an institution: members legitimately discuss and follow issuers, so
         * {@code tesla_investor} and {@code amazon_fan} must remain ordinary handles. Only the
         * name itself, or something one edit from it, is refused.
         */
        ISSUER,
        /**
         * A registered political party.
         *
         * <p>Also not an institution, for the same reason: commentary about a party is not a claim
         * to be it. Parties are listed uniformly, never selectively.
         */
        POLITICAL_PARTY,
        /** A staff, platform, or support role. */
        STAFF_ROLE,
        /** A platform word that must not be claimed by a member. */
        RESERVED;

        /** Returns true when nobody outside the operator may carry the name at all. */
        boolean exclusive() {
            return this == OWN_BRAND || this == OWN_PRODUCT || this == REGULATOR;
        }

        /**
         * Returns true when the entry names an institution rather than a role.
         *
         * <p>An institution name may match by containment, so a handle that carries it alongside a
         * staff role is refused. Types outside this set match only as a whole name, because their
         * appearance inside a longer handle is ordinary commentary rather than an identity claim.
         */
        boolean institution() {
            return this == OWN_BRAND
                    || this == OWN_PRODUCT
                    || this == REGULATOR
                    || this == STATE_ENTITY
                    || this == PAYMENT_NETWORK
                    || this == BANK
                    || this == BROKER;
        }
    }

    /** How strong the deterministic conclusion is when the entry matches. */
    public enum Severity {
        /** Terminal: the match alone is enough to block. */
        CLEAR,
        /** Unresolved: the match needs review, and the decision is UNKNOWN. */
        POSSIBLE
    }
}
