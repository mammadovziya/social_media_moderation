package com.example.moderation.gateway;

import static com.example.moderation.gateway.ModerationDependencyValidator.aiUsage;
import static com.example.moderation.gateway.ModerationDependencyValidator.systemFailureEnvelope;
import static com.example.moderation.gateway.ModerationDependencyValidator.throwOnDependencyFailureOrUnresolvedDecision;
import static com.example.moderation.gateway.ModerationDependencyValidator.withSystemFailure;
import static com.example.moderation.gateway.ModerationDecisionSupport.applyLocalRestrictedPoliticalEntity;
import static com.example.moderation.gateway.ModerationDecisionSupport.effectivePolicySignals;
import static com.example.moderation.gateway.ModerationDecisionSupport.effectiveRestrictedPoliticalEntity;
import static com.example.moderation.gateway.ModerationDecisionSupport.effectiveSafety;
import static com.example.moderation.gateway.ModerationDecisionSupport.effectiveSafetyAction;
import static com.example.moderation.gateway.ModerationDecisionSupport.elapsedMillis;
import static com.example.moderation.gateway.ModerationDecisionSupport.financialPrivacy;
import static com.example.moderation.gateway.ModerationDecisionSupport.impersonationForHandle;
import static com.example.moderation.gateway.ModerationDecisionSupport.localFinancialRisk;
import static com.example.moderation.gateway.ModerationDecisionSupport.localRestrictedPoliticalEntity;
import static com.example.moderation.gateway.ModerationDecisionSupport.positiveIntegralLong;
import static com.example.moderation.gateway.ModerationDecisionSupport.safetyFor;
import static com.example.moderation.gateway.ModerationDecisionSupport.successfulTextAdjudication;

import com.example.moderation.gateway.api.AiUsage;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.FinalReason;
import com.example.moderation.gateway.api.FinancialPrivacy;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
import com.example.moderation.gateway.api.Violation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Runs the USERNAME moderation use case after HTTP input validation and routing. */
@Service
final class UsernameModerationService {
    private static final Logger log = LoggerFactory.getLogger(UsernameModerationService.class);
    private static final String UNAVAILABLE = "unavailable";
    private static final String PROTECTED_NAME_REGISTRY_VERSION =
            "protected-name-registry-v1";
    private static final Set<String> HANDLE_EVIDENCE_KEYS = Set.of(
            "status",
            "skeleton",
            "skeletonProfileVersion",
            "skeletonProfileSha256",
            "registryVersion",
            "registryDigest",
            "registryActiveCount",
            "protectedMatch",
            "cachedVerdict",
            "rateLimited");
    private static final Set<String> PROTECTED_MATCH_NAME_TYPES = Set.of(
            "OWN_BRAND",
            "OWN_PRODUCT",
            "BANK",
            "BROKER",
            "REGULATOR",
            "STATE_ENTITY",
            "PAYMENT_NETWORK",
            "ISSUER",
            "POLITICAL_PARTY",
            "STAFF_ROLE",
            "RESERVED");
    private static final Set<String> PROTECTED_MATCH_KINDS =
            Set.of("EXACT", "NEAR", "BRAND_ROLE", "BRAND");

    private final AnalyzerClients clients;
    private final ModerationProperties properties;
    private final FinancialPrivacyScanner financialPrivacyScanner;
    private final ReloadingBlockedTerms blockedTerms;
    private final ReloadingRestrictedPoliticalEntities restrictedPoliticalEntities;
    private final ModerationAnalysisService analyses;
    private final DecisionAuditService decisionAudits;

    UsernameModerationService(
            AnalyzerClients clients,
            ModerationProperties properties,
            FinancialPrivacyScanner financialPrivacyScanner,
            ReloadingBlockedTerms blockedTerms,
            ReloadingRestrictedPoliticalEntities restrictedPoliticalEntities,
            ModerationAnalysisService analyses,
            DecisionAuditService decisionAudits) {
        this.clients = clients;
        this.properties = properties;
        this.financialPrivacyScanner = financialPrivacyScanner;
        this.blockedTerms = blockedTerms;
        this.restrictedPoliticalEntities = restrictedPoliticalEntities;
        this.analyses = analyses;
        this.decisionAudits = decisionAudits;
    }

    /**
     * Moderates a machine identity. Deterministic structure, protected-name, local policy, and
     * privacy layers decide before paid model work; the model handles semantic ambiguity.
     */
    ModerationResponse moderate(Input input) {
        String contentId = input.contentId();
        String handle = input.handle();
        String requestId = input.requestId();
        long startedAt = input.startedAt();

        HandlePolicy.Result structure = HandlePolicy.evaluate(handle);
        if (!structure.valid()) {
            // A structurally impossible handle is rejected input, not a moderation judgement.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, structure.reason().message());
        }
        String normalized = structure.normalized();
        ReloadingBlockedTerms.Snapshot blockedTermsSnapshot = blockedTerms.snapshot();
        ReloadingRestrictedPoliticalEntities.Snapshot politicalRegistrySnapshot =
                restrictedPoliticalEntities.snapshot();
        requireLocalPolicyHealthy();
        RestrictedPoliticalEntity localRestrictedPoliticalEntity =
                politicalRegistrySnapshot.entity(normalized);
        Map<String, Object> localEvidenceBuilder = new LinkedHashMap<>();
        localEvidenceBuilder.put("skeleton", structure.skeleton());
        localEvidenceBuilder.put(
                "restrictedPoliticalRegistryDigest",
                politicalRegistrySnapshot.semanticSha256());
        localEvidenceBuilder.put("blockedTermsDigest", blockedTermsSnapshot.semanticSha256());
        if (localRestrictedPoliticalEntity != RestrictedPoliticalEntity.NONE) {
            localEvidenceBuilder.put(
                    "localRestrictedPoliticalEntity",
                    localRestrictedPoliticalEntity.name());
        }
        Map<String, Object> localEvidence = Map.copyOf(localEvidenceBuilder);

        // Handles have no word boundaries, so literal and folded local checks run together.
        Violation blockedTermViolation = ReloadingBlockedTerms.strongestViolation(
                blockedTermsSnapshot.violation(normalized),
                blockedTermsSnapshot.handleViolation(normalized));
        if (blockedTermViolation != Violation.NONE) {
            return finish(
                    contentId,
                    requestId,
                    normalized,
                    localEvidence,
                    new DecisionPolicy.Result(Decision.BLOCK, blockedTermViolation),
                    UsernameDecisionAuditPayload.DecidingLayer.BLOCKED_TERM,
                    null,
                    FinancialPrivacy.NONE,
                    null,
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    false,
                    startedAt);
        }
        FinancialPrivacy localFinancialPrivacy = financialPrivacy(
                financialPrivacyScanner.scan(normalized));
        if (localFinancialPrivacy == FinancialPrivacy.CLEAR) {
            return finish(
                    contentId,
                    requestId,
                    normalized,
                    localEvidence,
                    new DecisionPolicy.Result(
                            Decision.BLOCK,
                            Violation.FINANCIAL_PRIVACY,
                            FinalReason.FINANCIAL_PRIVACY),
                    UsernameDecisionAuditPayload.DecidingLayer.FINANCIAL_PRIVACY,
                    null,
                    localFinancialPrivacy,
                    null,
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    false,
                    startedAt);
        }
        if (PolicySignals.isConfirmedRestrictedPoliticalEntity(
                localRestrictedPoliticalEntity)) {
            return finish(
                    contentId,
                    requestId,
                    normalized,
                    localEvidence,
                    new DecisionPolicy.Result(
                            Decision.BLOCK,
                            Violation.POLITICAL_CONTENT,
                            FinalReason.POLITICAL_CONTENT),
                    UsernameDecisionAuditPayload.DecidingLayer.RESTRICTED_POLITICAL_ENTITY,
                    null,
                    localFinancialPrivacy,
                    null,
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    false,
                    startedAt);
        }

        Map<String, Object> handleEvidence = handleEvidence(normalized, requestId);
        Map<String, Object> combinedEvidence = new LinkedHashMap<>(handleEvidence);
        combinedEvidence.putAll(localEvidence);
        Map<String, Object> evidence = Map.copyOf(combinedEvidence);
        if (!"ok".equals(evidence.get("status"))) {
            return finish(
                    contentId,
                    requestId,
                    normalized,
                    evidence,
                    new DecisionPolicy.Result(
                            Decision.UNKNOWN,
                            Violation.ANALYZER_ERROR,
                            FinalReason.ANALYZER_ERROR),
                    UsernameDecisionAuditPayload.DecidingLayer.ANALYZER_UNAVAILABLE,
                    null,
                    FinancialPrivacy.NONE,
                    analyses.unavailableAi(),
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    false,
                    startedAt);
        }

        Map<String, Object> protectedMatch = DecisionPolicy.nestedMap(evidence, "protectedMatch");
        boolean protectedClear =
                "CLEAR".equals(protectedMatch.get("severity")) && !protectedMatch.isEmpty();
        boolean protectedPossible = "POSSIBLE".equals(protectedMatch.get("severity"));
        if (protectedClear) {
            return finish(
                    contentId,
                    requestId,
                    normalized,
                    evidence,
                    new DecisionPolicy.Result(
                            Decision.BLOCK,
                            Violation.IMPERSONATION,
                            FinalReason.IMPERSONATION),
                    UsernameDecisionAuditPayload.DecidingLayer.PROTECTED_NAME,
                    null,
                    localFinancialPrivacy,
                    null,
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    false,
                    startedAt);
        }
        Map<String, Object> cachedVerdict = DecisionPolicy.nestedMap(evidence, "cachedVerdict");
        // A legacy cache verdict never resolves a current registry near-match.
        boolean fromLegacyCache =
                !protectedPossible && reusableLegacyVerdict(cachedVerdict);
        Map<String, Object> ai = fromLegacyCache
                ? cachedVerdict
                : analyses.analyzeText(
                        contentId,
                        ContentType.USERNAME,
                        normalized,
                        "",
                        "",
                        "",
                        requestId,
                        protectedPossible);
        boolean fromCache = fromLegacyCache || AiWorkCoordinator.isCacheHit(ai);
        if (!fromCache) {
            cacheVerdict(normalized, ai, requestId);
        }

        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai,
                ContentType.USERNAME,
                Violation.NONE,
                localFinancialPrivacy,
                properties.moderationScoreBlockThreshold());
        // The adjudicator sees registry uncertainty that the first classifier cannot observe.
        if (protectedPossible
                && DecisionPolicy.providerModerationViolation(
                                DecisionPolicy.nestedMap(ai, "moderation"),
                                DecisionPolicy.nestedMap(ai, "classification"),
                                properties.moderationScoreBlockThreshold())
                        == Violation.NONE) {
            DecisionPolicy.Result adjudicated = DecisionPolicy.textAdjudicatedResult(
                    DecisionPolicy.nestedMap(ai, "adjudication"), ContentType.USERNAME);
            if (adjudicated.decision() != Decision.UNKNOWN) {
                result = adjudicated;
            }
        }
        boolean textAdjudicationSucceeded = successfulTextAdjudication(
                ai,
                ContentType.USERNAME,
                result,
                protectedPossible,
                properties.moderationScoreBlockThreshold());
        if (!(textAdjudicationSucceeded
                && localRestrictedPoliticalEntity == RestrictedPoliticalEntity.POSSIBLE)) {
            result = applyLocalRestrictedPoliticalEntity(
                    result, localRestrictedPoliticalEntity);
        }
        PolicySignals signals = effectivePolicySignals(
                DecisionPolicy.nestedMap(ai, "classification"),
                DecisionPolicy.nestedMap(ai, "adjudication"),
                ContentType.USERNAME,
                localFinancialPrivacy,
                false,
                textAdjudicationSucceeded);

        UsernameDecisionAuditPayload.DecidingLayer layer =
                result.violation() == Violation.ANALYZER_ERROR
                        ? UsernameDecisionAuditPayload.DecidingLayer.ANALYZER_UNAVAILABLE
                        : textAdjudicationSucceeded
                                ? UsernameDecisionAuditPayload.DecidingLayer.ADJUDICATOR
                                : UsernameDecisionAuditPayload.DecidingLayer.CLASSIFIER;
        // A registry near-match that could not be adjudicated cannot become ALLOW.
        if (protectedPossible
                && result.decision() == Decision.ALLOW
                && !textAdjudicationSucceeded) {
            result = new DecisionPolicy.Result(
                    Decision.UNKNOWN,
                    Violation.IMPERSONATION,
                    FinalReason.IMPERSONATION);
            layer = UsernameDecisionAuditPayload.DecidingLayer.PROTECTED_NAME;
            signals = null;
        }

        return finish(
                contentId,
                requestId,
                normalized,
                evidence,
                result,
                layer,
                signals,
                localFinancialPrivacy,
                ai,
                fromCache
                        ? UsernameDecisionAuditPayload.VerdictSource.CACHE
                        : UsernameDecisionAuditPayload.VerdictSource.LIVE,
                textAdjudicationSucceeded,
                startedAt);
    }

    private boolean reusableLegacyVerdict(Map<String, Object> cachedVerdict) {
        return !cachedVerdict.isEmpty()
                && ConfigurationBoundAiWorkCoordinator.cacheable(cachedVerdict)
                && analyses.configurationMatches(cachedVerdict);
    }

    private Map<String, Object> handleEvidence(String handle, String requestId) {
        try {
            Map<String, Object> evidence = clients.evaluateHandle(
                    handle,
                    properties.expectedClassificationModel(),
                    properties.expectedClassificationPromptBundleSha256(),
                    properties.expectedClassificationProfileSha256());
            if (!validHandleEvidence(evidence, handle)) {
                return withSystemFailure(
                        Map.of("status", "error"),
                        ModerationSystemException.Kind.INVALID_RESPONSE);
            }
            if (evidence.containsKey("cachedVerdict")
                    && !ConfigurationBoundAiWorkCoordinator.cacheable(
                            DecisionPolicy.nestedMap(evidence, "cachedVerdict"))) {
                Map<String, Object> withoutInvalidCache = new LinkedHashMap<>(evidence);
                withoutInvalidCache.remove("cachedVerdict");
                return Map.copyOf(withoutInvalidCache);
            }
            return evidence;
        } catch (RuntimeException exception) {
            log.error(
                    "handle evaluator unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return systemFailureEnvelope(exception);
        }
    }

    private static boolean validHandleEvidence(
            Map<String, Object> evidence, String normalizedHandle) {
        if (evidence == null
                || !HANDLE_EVIDENCE_KEYS.containsAll(evidence.keySet())
                || !"ok".equals(evidence.get("status"))
                || !HandleSkeleton.of(normalizedHandle).equals(evidence.get("skeleton"))
                || !HandleSkeleton.PROFILE_VERSION.equals(
                        evidence.get("skeletonProfileVersion"))
                || !HandleSkeleton.PROFILE_SHA256.equals(
                        evidence.get("skeletonProfileSha256"))
                || !PROTECTED_NAME_REGISTRY_VERSION.equals(evidence.get("registryVersion"))
                || !(evidence.get("registryDigest") instanceof String registryDigest)
                || !registryDigest.matches("[0-9a-f]{64}")
                || !nonNegativeInteger(evidence.get("registryActiveCount"))
                || (evidence.containsKey("rateLimited")
                        && !(evidence.get("rateLimited") instanceof Boolean))) {
            return false;
        }
        if (!evidence.containsKey("protectedMatch")) {
            return true;
        }
        Map<String, Object> match = DecisionPolicy.nestedMap(evidence, "protectedMatch");
        return match.keySet().equals(
                        Set.of("protectedNameId", "nameType", "matchKind", "severity"))
                && match.get("protectedNameId") instanceof Number id
                && positiveIntegralLong(id)
                && PROTECTED_MATCH_NAME_TYPES.contains(match.get("nameType"))
                && PROTECTED_MATCH_KINDS.contains(match.get("matchKind"))
                && Set.of("CLEAR", "POSSIBLE").contains(match.get("severity"));
    }

    private static boolean nonNegativeInteger(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        double decimal = number.doubleValue();
        return Double.isFinite(decimal)
                && decimal >= 0
                && decimal == Math.rint(decimal)
                && decimal <= Integer.MAX_VALUE;
    }

    /** Cache persistence is an optimization and never changes an otherwise valid decision. */
    private void cacheVerdict(String handle, Map<String, Object> ai, String requestId) {
        if (!ConfigurationBoundAiWorkCoordinator.cacheable(ai)) {
            return;
        }
        try {
            clients.recordHandleVerdict(
                    handle,
                    properties.expectedClassificationModel(),
                    properties.expectedClassificationPromptBundleSha256(),
                    properties.expectedClassificationProfileSha256(),
                    ConfigurationBoundAiWorkCoordinator.withoutUsage(ai));
        } catch (RuntimeException exception) {
            log.warn(
                    "handle verdict cache unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
        }
    }

    /** Audits, logs, and renders one handle decision. Every terminal path passes through here. */
    private ModerationResponse finish(
            String contentId,
            String requestId,
            String handle,
            Map<String, Object> evidence,
            DecisionPolicy.Result result,
            UsernameDecisionAuditPayload.DecidingLayer layer,
            PolicySignals signals,
            FinancialPrivacy localFinancialPrivacy,
            Map<String, Object> ai,
            UsernameDecisionAuditPayload.VerdictSource verdictSource,
            boolean textAdjudicationSucceeded,
            long startedAt) {
        int auditLatencyMs = elapsedMillis(startedAt);
        AiUsage usage = ai == null
                        || verdictSource == UsernameDecisionAuditPayload.VerdictSource.CACHE
                ? AiUsage.noCalls()
                : aiUsage(ai);
        ModerationResponse response = new ModerationResponse(
                contentId,
                ContentType.USERNAME,
                result.decision(),
                result.violation(),
                null,
                null,
                result.reason(),
                null,
                effectiveSafetyAction(result, signals),
                signals == null ? safetyFor(result) : effectiveSafety(result, signals),
                null,
                signals == null ? localFinancialRisk(result) : signals.financialRisk(),
                signals == null ? localFinancialPrivacy : signals.financialPrivacy(),
                signals == null ? impersonationForHandle(result) : signals.impersonation(),
                null,
                effectiveRestrictedPoliticalEntity(
                        signals,
                        textAdjudicationSucceeded
                                        && localRestrictedPoliticalEntity(evidence)
                                                == RestrictedPoliticalEntity.POSSIBLE
                                ? RestrictedPoliticalEntity.NONE
                                : localRestrictedPoliticalEntity(evidence)),
                null,
                null,
                null,
                usage,
                DecisionPolicy.POLICY_VERSION);

        decisionAudits.persistUsername(new UsernameDecisionAuditFactory.Input(
                requestId,
                contentId,
                handle,
                evidence,
                response,
                layer,
                ai,
                usage,
                verdictSource,
                auditLatencyMs));

        int latencyMs = elapsedMillis(startedAt);
        log.info(
                "handle decision requestId={} contentId={} decision={} violation={} "
                        + "decidingLayer={} verdictSource={} registryDigest={} "
                        + "inputTokens={} outputTokens={} totalTokens={} estimatedCostUsd={} "
                        + "policyVersion={} latencyMs={}",
                requestId,
                contentId,
                result.decision(),
                result.violation(),
                layer,
                verdictSource,
                evidence.getOrDefault("registryDigest", UNAVAILABLE),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.estimatedCostUsd(),
                DecisionPolicy.POLICY_VERSION,
                latencyMs);

        // The audit is durable before a dependency failure or UNKNOWN is surfaced.
        throwOnDependencyFailureOrUnresolvedDecision(
                result, evidence, ai, ContentType.USERNAME, false, true);
        return response;
    }

    private void requireLocalPolicyHealthy() {
        if (!blockedTerms.reloadHealthy() || !restrictedPoliticalEntities.reloadHealthy()) {
            throw new ModerationSystemException(ModerationSystemException.Kind.UNAVAILABLE);
        }
    }

    record Input(String contentId, String handle, String requestId, long startedAt) {}
}
