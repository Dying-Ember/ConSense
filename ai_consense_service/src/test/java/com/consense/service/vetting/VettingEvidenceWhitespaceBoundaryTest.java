package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Shared consumer quote boundary only: no parser, registry, database, OCR or model execution. */
class VettingEvidenceWhitespaceBoundaryTest {
    private static final String SHA = String.join("", Collections.nCopies(64, "a"));
    private static final String TEXT = "The Contractor shall maintain the insured works.";

    @Test void unicodeWhitespaceOnlyCannotLocateAnySourceOrNativeOccurrence() {
        for (String quote : Arrays.asList(repeat("\u00a0"), repeat("\u2007"), repeat("\u202f"),
                repeat("\u3000"), repeat("\t\u00a0\n\u3000"))) {
            assertEquals("", VettingCorpus.normalize(quote));
            for (boolean canonical : Arrays.asList(false, true)) {
                Chunk chunk = source(canonical);
                FindingEvidence found = VettingCorpus.evidence(chunk, "source", quote);
                assertFalse(found.isLocated(), "Whitespace-only quote must never match the empty normalized needle");
                assertTrue(found.getNativePositions() == null || found.getNativePositions().isEmpty());
                assertSame(chunk.getCanonicalEvidence(), found.getCanonicalEvidence(), "Keep source pointer metadata for audit without granting quote location");
                assertEquals(quote, found.getQuote(), "Original model quotation remains literal");
            }
        }
    }

    @Test void unicodePaddingCannotMakeAShortSourceWordMeetTheExistingQuoteMinimum() {
        for (String pad : Arrays.asList(repeat("\u00a0"), repeat("\u3000"), repeat("\u202f"))) {
            String quote = pad + "maintain" + pad;
            assertEquals("maintain", VettingCorpus.normalize(quote));
            assertFalse(VettingCorpus.evidence(source(false), "source", quote).isLocated(),
                    "Padding cannot promote an eight-character quote above the existing twelve-character standard");
        }
        assertFalse(VettingCorpus.evidence(source(false), "source", "maintain").isLocated());
    }

    @Test void equivalentSourceWhitespaceAndTheExistingTwelveCharacterBoundaryStillLocate() {
        Chunk chunk = source(false);
        String quote = "\u00a0The\u2007Contractor\u3000shall\tmaintain\u202fthe\ninsured works.\u00a0";
        FindingEvidence found = VettingCorpus.evidence(chunk, "source", quote);
        assertTrue(found.isLocated());
        assertEquals(Collections.singletonList(chunk.getParts().get(0).getSourcePosition()), found.getNativePositions());
        assertEquals("body/1", found.getAnchor());
        assertEquals(quote, found.getQuote());
        Chunk exact = source(false);
        exact.setContent("abcdefghijkl");
        exact.getParts().get(0).setText(exact.getContent());
        exact.getParts().get(0).setEndOffset(exact.getContent().length());
        assertTrue(VettingCorpus.evidence(exact, "source", "abcdefghijkl").isLocated());
        assertFalse(VettingCorpus.evidence(exact, "source", "abcdefghijk").isLocated());
    }

    @Test void canonicalRuleRebindingCannotBindAWhitespaceOnlyQuote() throws Exception {
        OerBusinessEvidenceTest.Wire wire = new OerBusinessEvidenceTest.Wire();
        OerBusinessEvidence.Session session = wire.load();
        Chunk chunk = session.chunks().get(0);
        FindingEvidence quote = new FindingEvidence();
        quote.setDocumentId(chunk.getDocumentId());
        quote.setSourceHash(chunk.getCanonicalEvidence().getJavaParsedSourceHash());
        quote.setAnchor(chunk.getParts().get(0).getAnchor());
        quote.setQuote(repeat("\u00a0"));
        quote.setLocated(true);
        wire.service.bindRuleEvidence(session, Collections.singletonList(quote));
        assertFalse(quote.isLocated(), "Rule rebinding also must not match an empty normalized needle");
        assertNull(quote.getCanonicalEvidence(), "No canonical location was established by this rule quote");
    }

    private static String repeat(String value) { return String.join("", Collections.nCopies(12, value)); }

    private static Chunk source(boolean canonical) {
        Chunk chunk = new Chunk();
        chunk.setId("authored-source-chunk");
        chunk.setDocumentId("42");
        chunk.setFileKey("SCC");
        chunk.setFileName("authored-quote.docx");
        chunk.setRole("tender");
        chunk.setSourceHash(SHA);
        chunk.setOriginalSourceSha256(SHA);
        chunk.setContent(TEXT);
        chunk.setAnchor("body/1");
        Part part = new Part();
        part.setText(TEXT);
        part.setAnchor("body/1");
        part.setBlockId("authored-source-paragraph");
        part.setStartOffset(0);
        part.setEndOffset(TEXT.length());
        part.setSourcePosition(JsonUtils.parse("{\"kind\":\"docx_native\",\"sourceSha256\":\"" + SHA
                + "\",\"partName\":\"word/document.xml\",\"partSha256\":\"" + SHA
                + "\",\"nodePath\":\"/{urn:authored-word}document[1]/{urn:authored-word}p[1]\"}"));
        chunk.setParts(Collections.singletonList(part));
        if (canonical) {
            EvidencePointer pointer = new EvidencePointer();
            pointer.setDocumentId(42L);
            pointer.setJavaParsedSourceHash(SHA);
            pointer.setOriginalSourceSha256(SHA);
            chunk.setCanonicalEvidence(pointer);
        }
        return chunk;
    }
}
