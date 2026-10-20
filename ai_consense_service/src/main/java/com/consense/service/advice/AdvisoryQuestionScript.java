package com.consense.service.advice;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/** Conservative orthographic evidence; never converts question or source text. */
final class AdvisoryQuestionScript {
    private static final BitSet[] EVIDENCE = load();
    private AdvisoryQuestionScript() {}

    static String infer(String text) {
        int evidence = 0;
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            if (EVIDENCE[0].get(point)) evidence |= 1;
            if (EVIDENCE[1].get(point)) evidence |= 2;
            if (evidence == 3) return "zh";
        }
        return evidence == 1 ? "zhHans" : evidence == 2 ? "zhHant" : "zh";
    }

    private static BitSet[] load() {
        BitSet[] result = {new BitSet(), new BitSet()};
        try (InputStream input = AdvisoryQuestionScript.class.getResourceAsStream("/advice/unihan-script-17.0.0.txt")) {
            if (input == null) throw new IllegalStateException("Missing versioned Advisory script evidence");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 2 || !("zhHans".equals(fields[0]) || "zhHant".equals(fields[0])))
                        throw new IllegalStateException("Invalid Advisory script evidence record");
                    BitSet target = result["zhHans".equals(fields[0]) ? 0 : 1];
                    fields[1].codePoints().forEach(target::set);
                }
            }
            if (result[0].isEmpty() || result[1].isEmpty() || result[0].intersects(result[1]))
                throw new IllegalStateException("Ambiguous Advisory script evidence sets");
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load versioned Advisory script evidence", error);
        }
    }
}
