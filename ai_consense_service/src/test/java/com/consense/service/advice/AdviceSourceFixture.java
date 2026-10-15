package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.OerSourceMetadata;
import java.util.Arrays;

/** Explicit authored source identity for consumer tests; this is never parser or router acceptance. */
public final class AdviceSourceFixture {
    private AdviceSourceFixture() { }
    public static void bind(SourceDocument source,byte[] original) {
        String digest=OerSourceMetadata.originalSha256(original);source.setSourceSha256(digest);source.setDocumentScope("package");
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(digest);identity.setKind(source.getFileKey());identity.setRole(source.getReviewRole());
        identity.setSourcePurpose("standard".equals(source.getReviewRole())?"reference_standard":"project_document");identity.setIdentityStatus("authored_test_fixture");identity.setVersion("AUTHORED TEST ONLY");
        identity.setEligibleModules(Arrays.asList("drafting","vetting","advisory"));source.setOerIdentityJson(JsonUtils.write(identity));
    }
}
