package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.ModerationResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
public class ModerationController implements ModerationApi {
    private final UsernameModerationService usernameModeration;
    private final ContentModerationService contentModeration;
    private final ModerationRequestValidator requestValidator;

    @Autowired
    public ModerationController(
            UsernameModerationService usernameModeration,
            ContentModerationService contentModeration,
            ModerationRequestValidator requestValidator) {
        this.usernameModeration = usernameModeration;
        this.contentModeration = contentModeration;
        this.requestValidator = requestValidator;
    }

    /** Compatibility seam for direct Java callers and the controller characterization tests. */
    ModerationController(
            AnalyzerClients clients,
            ModerationProperties properties,
            FinancialPrivacyScanner financialPrivacyScanner,
            ReloadingBlockedTerms blockedTerms,
            ReloadingRestrictedPoliticalEntities restrictedPoliticalEntities,
            AiWorkCoordinator aiWorkCoordinator) {
        this(compatibilityServices(
                clients,
                properties,
                financialPrivacyScanner,
                blockedTerms,
                restrictedPoliticalEntities,
                aiWorkCoordinator));
    }

    private ModerationController(CompatibilityServices services) {
        this(
                services.usernameModeration(),
                services.contentModeration(),
                services.requestValidator());
    }

    private static CompatibilityServices compatibilityServices(
            AnalyzerClients clients,
            ModerationProperties properties,
            FinancialPrivacyScanner financialPrivacyScanner,
            ReloadingBlockedTerms blockedTerms,
            ReloadingRestrictedPoliticalEntities restrictedPoliticalEntities,
            AiWorkCoordinator aiWorkCoordinator) {
        AiConfigurationProvenance aiConfigurations =
                new AiConfigurationProvenance(properties);
        ModerationAnalysisService analyses =
                new ModerationAnalysisService(clients, aiWorkCoordinator, aiConfigurations);
        DecisionAuditService decisionAudits = new DecisionAuditService(
                clients,
                properties,
                financialPrivacyScanner,
                aiConfigurations);
        return new CompatibilityServices(
                new UsernameModerationService(
                        clients,
                        properties,
                        financialPrivacyScanner,
                        blockedTerms,
                        restrictedPoliticalEntities,
                        analyses,
                        decisionAudits),
                new ContentModerationService(
                        properties,
                        financialPrivacyScanner,
                        blockedTerms,
                        restrictedPoliticalEntities,
                        analyses,
                        decisionAudits),
                new ModerationRequestValidator(properties));
    }

    @Override
    public ModerationResponse moderate(
            String contentId,
            String contentType,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            MultipartFile image,
            String suppliedRequestId,
            HttpServletResponse servletResponse)
            throws IOException {
        long startedAt = System.nanoTime();
        String requestId = requestValidator.requestId(suppliedRequestId);
        servletResponse.setHeader("X-Request-ID", requestId);
        ContentType type = requestValidator.contentType(contentType);
        requestValidator.validateInputs(
                type, text, parentPostText, authorUsername, quotedText, image);
        if (type == ContentType.USERNAME) {
            return usernameModeration.moderate(
                    new UsernameModerationService.Input(
                            contentId, text, requestId, startedAt));
        }

        ContentModerationService.ImageInput applicationImage =
                requestValidator.image(image);
        return contentModeration.moderate(new ContentModerationService.Input(
                contentId,
                type,
                text,
                parentPostText,
                authorUsername,
                quotedText,
                applicationImage,
                requestId,
                startedAt));
    }

    /** Backward-compatible direct-call overload used by existing Java clients and tests. */
    public ModerationResponse moderate(
            String contentId,
            String contentType,
            String text,
            MultipartFile image,
            String suppliedRequestId,
            HttpServletResponse servletResponse)
            throws IOException {
        return moderate(
                contentId,
                contentType,
                text,
                "",
                "",
                "",
                image,
                suppliedRequestId,
                servletResponse);
    }

    static String imageAnalysisText(String originalText, Map<String, Object> media) {
        return MediaEvidenceValidator.imageAnalysisText(originalText, media);
    }

    static boolean validMediaEnvelope(Map<String, Object> media) {
        return MediaEvidenceValidator.validMediaEnvelope(media);
    }

    static String responseOcrText(Map<String, Object> media) {
        return MediaEvidenceValidator.responseOcrText(media);
    }

    private record CompatibilityServices(
            UsernameModerationService usernameModeration,
            ContentModerationService contentModeration,
            ModerationRequestValidator requestValidator) {}
}
