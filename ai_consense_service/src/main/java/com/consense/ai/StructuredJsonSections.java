package com.consense.ai;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/** Only closed objects directly inside the root sections array become candidates for business validation. */
public final class StructuredJsonSections {
    /** Grammar failures may be held until the bounded provider entity is recorded; business failures may not. */
    public static final class InvalidJsonException extends IllegalArgumentException {
        private InvalidJsonException(String message, Throwable cause) { super(message, cause); }
    }
    private static final int MAX_CHARS = 4 * 1024 * 1024;
    private final StringBuilder raw = new StringBuilder();
    private final JsonParser parser;
    private final ByteArrayFeeder feeder;
    private final Consumer<JsonNode> section;
    private TokenBuffer current;
    private int depth;
    private char pendingSurrogate;
    private boolean ended;

    public StructuredJsonSections(Consumer<JsonNode> onSection) {
        section = Objects.requireNonNull(onSection);
        try {
            parser = JsonUtils.mapper().getFactory().createNonBlockingByteArrayParser();
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    public void accept(String delta) {
        if (ended || delta == null || delta.length() > MAX_CHARS - raw.length())
            throw new IllegalArgumentException("Structured stream state or output bound is invalid");
        raw.append(delta);
        String input = pendingSurrogate == 0 ? delta : pendingSurrogate + delta;
        pendingSurrogate = 0;
        if (!input.isEmpty() && Character.isHighSurrogate(input.charAt(input.length() - 1))) {
            pendingSurrogate = input.charAt(input.length() - 1);
            input = input.substring(0, input.length() - 1);
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= input.length() || !Character.isLowSurrogate(input.charAt(i)))
                    throw new InvalidJsonException("Invalid Unicode in structured stream", null);
            } else if (Character.isLowSurrogate(c)) throw new InvalidJsonException("Invalid Unicode in structured stream", null);
        }
        if (input.isEmpty()) return;
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        try { feeder.feedInput(bytes, 0, bytes.length); drain(); }
        catch (IOException invalid) { throw new InvalidJsonException("Invalid incremental answer JSON", invalid); }
    }

    private void drain() throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
            if (current == null && token == JsonToken.START_OBJECT) {
                com.fasterxml.jackson.core.JsonStreamContext array = parser.getParsingContext().getParent();
                if (array != null && array.inArray() && array.getParent() != null && array.getParent().inObject()
                        && array.getParent().getParent() != null && array.getParent().getParent().inRoot()
                        && "sections".equals(array.getParent().getCurrentName())) {
                    current = new TokenBuffer(JsonUtils.mapper(), false);
                    depth = 0;
                }
            }
            if (current == null) continue;
            current.copyCurrentEvent(parser);
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++;
            if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
            if (depth == 0) {
                try (JsonParser object = current.asParser(JsonUtils.mapper())) {
                    JsonNode node = JsonUtils.mapper().readTree(object);
                    if (node == null || !node.isObject()) throw new IOException("Section must be an object");
                    section.accept(node);
                } finally { current.close(); current = null; }
            }
        }
    }

    public JsonNode finish() {
        if (ended) throw new IllegalArgumentException("Structured stream is already ended");
        if (pendingSurrogate != 0) throw new InvalidJsonException("Structured stream has incomplete Unicode", null);
        ended = true;
        try {
            feeder.endOfInput(); drain(); parser.close();
            try (JsonParser complete = JsonUtils.mapper().getFactory().createParser(raw.toString())) {
                complete.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode root = JsonUtils.mapper().readTree(complete);
                if (root == null || !root.isObject() || !root.path("sections").isArray() || complete.nextToken() != null)
                    throw new IOException("Answer must be a single object with sections");
                for (JsonNode value : root.path("sections")) if (!value.isObject()) throw new IOException("Section must be an object");
                return root;
            }
        } catch (IOException invalid) { throw new InvalidJsonException("Incomplete or invalid final answer JSON", invalid); }
    }
}
