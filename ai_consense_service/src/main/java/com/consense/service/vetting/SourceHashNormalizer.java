package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Memoizes only deterministic normalization digests; every call fingerprints its supplied content. */
final class SourceHashNormalizer {
    private static final String STYLE_MARKER = "\"paragraphStyleName\"";
    private final int maximumEntries;
    private final Map<String,String> memo = new LinkedHashMap<>(16, 0.75f, true);

    SourceHashNormalizer(int maximumEntries) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        this.maximumEntries = maximumEntries;
    }

    String hash(String text, String structure) {
        String rawDigest = joinedHash(text, structure);
        if (!structure.contains(STYLE_MARKER)) return rawDigest;
        // UTF-8 replaces malformed UTF-16 with '?'. Do not memoize distinct inputs
        // that have the same lossy encoding, or ambiguous text/structure boundaries.
        String key = wellFormed(text) && wellFormed(structure)
                ? rawDigest + ":" + text.length() + ":" + structure.length() : null;
        if (key != null) synchronized (memo) {
            String existing = memo.get(key);
            if (existing != null) return existing;
        }
        JsonNode tree = JsonUtils.parse(structure);
        boolean enriched = false;
        if (tree.isArray()) for (JsonNode block : tree) {
            if (block.isObject() && block.has("paragraphStyleName")) {
                ((ObjectNode) block).remove("paragraphStyleName");
                enriched = true;
            }
        }
        String normalized = enriched ? joinedHash(text, JsonUtils.write(tree)) : rawDigest;
        // Parsing/serialization and hashing stay outside the short map lock.
        // Failures never enter the memo; neither source strings nor nodes are retained.
        if (key != null) synchronized (memo) {
            memo.put(key, normalized);
            while (memo.size() > maximumEntries) memo.remove(memo.keySet().iterator().next());
        }
        return normalized;
    }

    private static boolean wellFormed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    static String joinedHash(String text, String structure) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Utf8.update(digest,text);
            digest.update((byte)'\n');
            Utf8.update(digest,structure);
            byte[] bytes = digest.digest();
            char[] hex = new char[bytes.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                hex[2*i] = digits[(bytes[i] & 255) >>> 4];
                hex[2*i+1] = digits[bytes[i] & 15];
            }
            return new String(hex);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

}
