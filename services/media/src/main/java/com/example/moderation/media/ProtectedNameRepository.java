package com.example.moderation.media;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProtectedNameRepository {
    private final JdbcClient jdbc;

    ProtectedNameRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts seed entries that are not present yet.
     *
     * <p>An existing row is never rewritten. The seed is initial data, so an operator who
     * deactivates or reclassifies an entry keeps that decision across restarts.
     *
     * @return the number of newly inserted entries
     */
    int insertMissing(List<SeedEntry> entries, String policyVersion, String skeletonVersion) {
        int inserted = 0;
        for (SeedEntry entry : entries) {
            inserted += jdbc.sql("""
                            INSERT INTO moderation_protected_names (
                                value,
                                skeleton,
                                name_type,
                                severity,
                                status,
                                policy_version,
                                skeleton_profile_version
                            ) VALUES (
                                :value,
                                :skeleton,
                                :nameType,
                                :severity,
                                'ACTIVE',
                                :policyVersion,
                                :skeletonVersion
                            )
                            ON CONFLICT (skeleton, name_type) DO NOTHING
                            """)
                    .param("value", entry.value())
                    .param("skeleton", entry.skeleton())
                    .param("nameType", entry.nameType().name())
                    .param("severity", entry.severity().name())
                    .param("policyVersion", policyVersion)
                    .param("skeletonVersion", skeletonVersion)
                    .update();
        }
        return inserted;
    }

    List<ProtectedName> findActive() {
        return jdbc.sql("""
                        SELECT id, value, skeleton, name_type, severity
                        FROM moderation_protected_names
                        WHERE status = 'ACTIVE'
                        ORDER BY skeleton, name_type
                        """)
                .query((resultSet, rowNumber) -> new ProtectedName(
                        resultSet.getLong("id"),
                        resultSet.getString("value"),
                        resultSet.getString("skeleton"),
                        ProtectedName.NameType.valueOf(
                                resultSet.getString("name_type").toUpperCase(Locale.ROOT)),
                        ProtectedName.Severity.valueOf(
                                resultSet.getString("severity").toUpperCase(Locale.ROOT))))
                .list();
    }

    /** One parsed seed line with its skeleton already computed by {@link HandleSkeleton}. */
    record SeedEntry(
            String value,
            String skeleton,
            ProtectedName.NameType nameType,
            ProtectedName.Severity severity) {}
}
