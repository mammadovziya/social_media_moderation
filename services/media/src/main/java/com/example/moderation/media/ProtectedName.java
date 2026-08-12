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
        /** A staff, platform, or support role. */
        STAFF_ROLE,
        /** A platform word that must not be claimed by a member. */
        RESERVED;

        /** Returns true when nobody outside the operator may carry the name at all. */
        boolean exclusive() {
            return this == OWN_BRAND || this == OWN_PRODUCT || this == REGULATOR;
        }

        /** Returns true when the entry names an institution rather than a role. */
        boolean institution() {
            return this == OWN_BRAND
                    || this == OWN_PRODUCT
                    || this == REGULATOR
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
