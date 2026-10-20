package com.consense.service.advice;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class AdvisoryQuestionScriptTest {
    @Test void completePinnedUnicodeResourceLoadsWithoutLosingSupplementaryCharacters() throws Exception {
        byte[] bytes;
        try (InputStream input = new ClassPathResource("advice/unihan-script-17.0.0.txt").getInputStream()) {
            bytes = StreamUtils.copyToByteArray(input);
        }
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", value & 255));
        assertEquals("d5cb8cefb16ff2ff80de2334deb6a255640bae90b26489b6f11b14aa0bef1a71", digest.toString());
        int supplementary = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            int[] points = fields[1].codePoints().toArray();
            assertEquals("zhHans".equals(fields[0]) ? 6043 : 6497, points.length);
            for (int point : points) {
                assertEquals(fields[0], AdvisoryQuestionScript.infer(new String(Character.toChars(point))), Integer.toHexString(point));
                if (point > Character.MAX_VALUE) supplementary++;
            }
        }
        assertTrue(supplementary > 0);
    }

    @Test void sharedSelfVariantsAndContradictoryEvidenceCannotDetermineAScript() {
        assertEquals("zh", AdvisoryQuestionScript.infer("后干台"));
        assertEquals("zh", AdvisoryQuestionScript.infer("合同如何支付"));
        assertEquals("zh", AdvisoryQuestionScript.infer("请請"));
        assertEquals("zh", AdvisoryQuestionScript.infer(""));
    }
}
