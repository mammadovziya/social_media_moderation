package com.example.moderation.media;

import com.example.moderation.media.ModerationReferenceAsset.DecisionBasis;
import com.example.moderation.media.ModerationReferenceAsset.Severity;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PdqHashRepository {
    private final JdbcClient jdbc;

    public PdqHashRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String contentId, String hash, int quality) {
        jdbc.sql("""
                        INSERT INTO pdq_hashes (content_id, hash_value, quality)
                        VALUES (:contentId, :hash, :quality)
                        ON CONFLICT (content_id, hash_value) DO NOTHING
                        """)
                .param("contentId", contentId)
                .param("hash", hash)
                .param("quality", quality)
                .update();
    }

    public void saveEvidence(MediaEvidence evidence) {
        jdbc.sql("""
                        INSERT INTO moderation_media_evidence_events (
                            content_id,
                            sha256,
                            byte_length,
                            detected_format,
                            pdq_hash,
                            pdq_quality,
                            masked_pdq_hash,
                            masked_pdq_quality,
                            masked_region_count,
                            ocr_status,
                            ocr_digest,
                            ocr_confidence,
                            ocr_confidence_accepted,
                            ocr_truncated,
                            ocr_engine,
                            candidate_count,
                            pdq_implementation_commit
                        ) VALUES (
                            :contentId,
                            :sha256,
                            :byteLength,
                            :detectedFormat,
                            :pdqHash,
                            :pdqQuality,
                            :maskedPdqHash,
                            :maskedPdqQuality,
                            :maskedRegionCount,
                            :ocrStatus,
                            :ocrDigest,
                            :ocrConfidence,
                            :ocrConfidenceAccepted,
                            :ocrTruncated,
                            :ocrEngine,
                            :candidateCount,
                            :pdqImplementationCommit
                        )
                        """)
                .param("contentId", evidence.contentId())
                .param("sha256", evidence.sha256())
                .param("byteLength", evidence.byteLength())
                .param("detectedFormat", evidence.detectedFormat())
                .param("pdqHash", evidence.pdqHash())
                .param("pdqQuality", evidence.pdqQuality())
                .param("maskedPdqHash", evidence.maskedPdqHash())
                .param("maskedPdqQuality", evidence.maskedPdqQuality())
                .param("maskedRegionCount", evidence.maskedRegionCount())
                .param("ocrStatus", evidence.ocrStatus())
                .param("ocrDigest", evidence.ocrDigest())
                .param("ocrConfidence", evidence.ocrConfidence())
                .param("ocrConfidenceAccepted", evidence.ocrConfidenceAccepted())
                .param("ocrTruncated", evidence.ocrTruncated())
                .param("ocrEngine", evidence.ocrEngine())
                .param("candidateCount", evidence.candidateCount())
                .param("pdqImplementationCommit", evidence.pdqImplementationCommit())
                .update();
    }

    public long referenceAssetsRevision() {
        return jdbc.sql("""
                        SELECT reference_revision.revision + legacy_revision.revision
                        FROM moderation_reference_assets_revision reference_revision
                        CROSS JOIN blocked_pdq_hashes_revision legacy_revision
                        WHERE reference_revision.singleton = TRUE
                          AND legacy_revision.singleton = TRUE
                        """)
                .query(Long.class)
                .single();
    }

    public ReferenceAssetsSnapshot loadReferenceAssetsSnapshot() {
        List<ReferenceAssetRow> rows = jdbc.sql("""
                        WITH combined_revision AS (
                            SELECT reference_revision.revision + legacy_revision.revision AS revision
                            FROM moderation_reference_assets_revision reference_revision
                            CROSS JOIN blocked_pdq_hashes_revision legacy_revision
                            WHERE reference_revision.singleton = TRUE
                              AND legacy_revision.singleton = TRUE
                        ), active_assets AS (
                            SELECT
                                id,
                                external_id,
                                decision_basis,
                                violation_category,
                                severity,
                                policy_version,
                                sha256,
                                pdq_hash,
                                masked_pdq_hash,
                                ocr_digest,
                                FALSE AS legacy
                            FROM moderation_reference_assets
                            WHERE status = 'ACTIVE'
                            UNION ALL
                            SELECT
                                NULL::BIGINT AS id,
                                'legacy-pdq:' || hash_value AS external_id,
                                'COMPOSITION_DEPENDENT' AS decision_basis,
                                reason AS violation_category,
                                'HIGH' AS severity,
                                'legacy-v1' AS policy_version,
                                NULL::CHAR(64) AS sha256,
                                hash_value AS pdq_hash,
                                NULL::CHAR(64) AS masked_pdq_hash,
                                NULL::CHAR(64) AS ocr_digest,
                                TRUE AS legacy
                            FROM blocked_pdq_hashes
                        )
                        SELECT revision.revision, assets.*
                        FROM combined_revision revision
                        LEFT JOIN active_assets assets ON TRUE
                        """)
                .query((resultSet, rowNumber) -> new ReferenceAssetRow(
                        resultSet.getLong("revision"),
                        resultSet.getObject("id", Long.class),
                        resultSet.getString("external_id"),
                        resultSet.getString("decision_basis"),
                        resultSet.getString("violation_category"),
                        resultSet.getString("severity"),
                        resultSet.getString("policy_version"),
                        resultSet.getString("sha256"),
                        resultSet.getString("pdq_hash"),
                        resultSet.getString("masked_pdq_hash"),
                        resultSet.getString("ocr_digest"),
                        resultSet.getBoolean("legacy")))
                .list();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Reference asset revision row is missing");
        }

        long revision = rows.getFirst().revision();
        List<ModerationReferenceAsset> assets = rows.stream()
                .peek(row -> {
                    if (row.revision() != revision) {
                        throw new IllegalStateException(
                                "Reference asset snapshot has mixed revisions");
                    }
                })
                .filter(row -> row.externalId() != null)
                .map(ReferenceAssetRow::toAsset)
                .toList();
        return new ReferenceAssetsSnapshot(revision, assets);
    }

    public VisualReferenceSnapshot loadVisualReferenceSnapshot(String descriptorVersion) {
        List<VisualReferenceRow> rows = jdbc.sql("""
                        WITH combined_revision AS (
                            SELECT reference_revision.revision + legacy_revision.revision AS revision
                            FROM moderation_reference_assets_revision reference_revision
                            CROSS JOIN blocked_pdq_hashes_revision legacy_revision
                            WHERE reference_revision.singleton = TRUE
                              AND legacy_revision.singleton = TRUE
                        ), active_descriptors AS (
                            SELECT
                                asset.id,
                                asset.external_id,
                                asset.decision_basis,
                                asset.violation_category,
                                asset.severity,
                                asset.policy_version,
                                asset.sha256,
                                asset.pdq_hash,
                                asset.masked_pdq_hash,
                                asset.ocr_digest,
                                descriptor.descriptor_version,
                                descriptor.descriptor_schema_version,
                                descriptor.channel,
                                descriptor.algorithm,
                                descriptor.algorithm_version,
                                descriptor.implementation,
                                descriptor.implementation_version,
                                descriptor.canonicalization_version,
                                descriptor.descriptor_type,
                                descriptor.max_features,
                                descriptor.source_sha256,
                                descriptor.descriptor_sha256,
                                descriptor.working_width,
                                descriptor.working_height,
                                descriptor.keypoint_count,
                                descriptor.descriptor_size,
                                descriptor.descriptor_bytes,
                                descriptor.keypoints::TEXT AS keypoints_json,
                                descriptor.exclusion_mask_version,
                                descriptor.exclusion_mask_sha256
                            FROM moderation_reference_assets asset
                            JOIN moderation_reference_visual_descriptors descriptor
                              ON descriptor.reference_asset_id = asset.id
                            WHERE asset.status = 'ACTIVE'
                              AND descriptor.descriptor_version = :descriptorVersion
                              AND asset.sha256 = descriptor.source_sha256
                        )
                        SELECT revision.revision, descriptors.*
                        FROM combined_revision revision
                        LEFT JOIN active_descriptors descriptors ON TRUE
                        """)
                .param("descriptorVersion", descriptorVersion)
                .query((resultSet, rowNumber) -> new VisualReferenceRow(
                        resultSet.getLong("revision"),
                        resultSet.getObject("id", Long.class),
                        resultSet.getString("external_id"),
                        resultSet.getString("decision_basis"),
                        resultSet.getString("violation_category"),
                        resultSet.getString("severity"),
                        resultSet.getString("policy_version"),
                        resultSet.getString("sha256"),
                        resultSet.getString("pdq_hash"),
                        resultSet.getString("masked_pdq_hash"),
                        resultSet.getString("ocr_digest"),
                        resultSet.getString("descriptor_version"),
                        resultSet.getString("descriptor_schema_version"),
                        resultSet.getString("channel"),
                        resultSet.getString("algorithm"),
                        resultSet.getString("algorithm_version"),
                        resultSet.getString("implementation"),
                        resultSet.getString("implementation_version"),
                        resultSet.getString("canonicalization_version"),
                        resultSet.getString("descriptor_type"),
                        resultSet.getInt("max_features"),
                        resultSet.getString("source_sha256"),
                        resultSet.getString("descriptor_sha256"),
                        resultSet.getInt("working_width"),
                        resultSet.getInt("working_height"),
                        resultSet.getInt("keypoint_count"),
                        resultSet.getInt("descriptor_size"),
                        resultSet.getBytes("descriptor_bytes"),
                        resultSet.getString("keypoints_json"),
                        resultSet.getString("exclusion_mask_version"),
                        resultSet.getString("exclusion_mask_sha256")))
                .list();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Reference asset revision row is missing");
        }
        long revision = rows.getFirst().revision();
        List<VisualReferenceDescriptor> descriptors = rows.stream()
                .peek(row -> {
                    if (row.revision() != revision) {
                        throw new IllegalStateException(
                                "Visual reference snapshot has mixed revisions");
                    }
                })
                .filter(row -> row.databaseId() != null)
                .map(VisualReferenceRow::toDescriptor)
                .toList();
        return new VisualReferenceSnapshot(revision, descriptors);
    }

    public long observedHashCount() {
        return jdbc.sql("SELECT COUNT(*) FROM pdq_hashes")
                .query(Long.class)
                .single();
    }

    public record ReferenceAssetsSnapshot(
            long revision, List<ModerationReferenceAsset> assets) {
        public ReferenceAssetsSnapshot {
            assets = List.copyOf(assets);
        }
    }

    public record VisualReferenceSnapshot(
            long revision, List<VisualReferenceDescriptor> descriptors) {
        public VisualReferenceSnapshot {
            descriptors = List.copyOf(descriptors);
        }

        long encodedDescriptorBytes() {
            return descriptors.stream()
                    .mapToLong(descriptor -> Base64.getEncoder()
                            .encodeToString(descriptor.descriptorBytes())
                            .length())
                    .sum();
        }
    }

    private record ReferenceAssetRow(
            long revision,
            Long id,
            String externalId,
            String decisionBasis,
            String violationCategory,
            String severity,
            String policyVersion,
            String sha256,
            String pdqHash,
            String maskedPdqHash,
            String ocrDigest,
            boolean legacy) {

        ModerationReferenceAsset toAsset() {
            return new ModerationReferenceAsset(
                    id,
                    externalId,
                    DecisionBasis.valueOf(decisionBasis),
                    violationCategory,
                    Severity.valueOf(severity),
                    policyVersion,
                    sha256,
                    pdqHash,
                    maskedPdqHash,
                    ocrDigest,
                    legacy);
        }
    }

    private record VisualReferenceRow(
            long revision,
            Long databaseId,
            String externalId,
            String decisionBasis,
            String violationCategory,
            String severity,
            String policyVersion,
            String sha256,
            String pdqHash,
            String maskedPdqHash,
            String ocrDigest,
            String descriptorVersion,
            String schemaVersion,
            String channel,
            String algorithm,
            String algorithmVersion,
            String implementation,
            String implementationVersion,
            String canonicalizationVersion,
            String descriptorType,
            int maxFeatures,
            String sourceSha256,
            String descriptorSha256,
            int workingWidth,
            int workingHeight,
            int keypointCount,
            int descriptorSize,
            byte[] descriptorBytes,
            String keypointsJson,
            String exclusionMaskVersion,
            String exclusionMaskSha256) {

        VisualReferenceDescriptor toDescriptor() {
            ModerationReferenceAsset asset = new ModerationReferenceAsset(
                    databaseId,
                    externalId,
                    DecisionBasis.valueOf(decisionBasis),
                    violationCategory,
                    Severity.valueOf(severity),
                    policyVersion,
                    sha256,
                    pdqHash,
                    maskedPdqHash,
                    ocrDigest,
                    false);
            return new VisualReferenceDescriptor(
                    asset,
                    descriptorVersion,
                    schemaVersion,
                    channel,
                    algorithm,
                    algorithmVersion,
                    implementation,
                    implementationVersion,
                    canonicalizationVersion,
                    descriptorType,
                    maxFeatures,
                    sourceSha256,
                    descriptorSha256,
                    workingWidth,
                    workingHeight,
                    keypointCount,
                    descriptorSize,
                    descriptorBytes,
                    keypointsJson,
                    exclusionMaskVersion,
                    exclusionMaskSha256);
        }
    }
}
