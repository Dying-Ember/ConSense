package com.consense.ai;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Bounded UTF-8 decoding across arbitrary network splits, for SSE and NDJSON. */
public final class ProviderStreamReader {
    private ProviderStreamReader() {}

    /** Retain the possible secret prefix across delta boundaries before forwarding content. */
    public static final class SafeDelta implements Consumer<String> {
        private final String key;
        private final Consumer<String> target;
        private String pending = "";
        public SafeDelta(String secret, Consumer<String> output) { key = secret == null ? "" : secret; target = java.util.Objects.requireNonNull(output); }
        @Override public void accept(String part) {
            if (key.isEmpty()) { target.accept(part); return; }
            pending = (pending + part).replace(key, "[REDACTED]");
            int ready = Math.max(0, pending.length() - key.length() + 1);
            if (ready > 0) { target.accept(pending.substring(0, ready)); pending = pending.substring(ready); }
        }
        public void finish() { if (!pending.isEmpty()) { target.accept(pending); pending = ""; } }
    }

    public static String read(InputStream input, int cap, boolean sse, Consumer<String> event) throws IOException {
        if (cap <= 0 || event == null) throw new IllegalArgumentException("Stream bound and callback are required");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        InputStream bounded = new FilterInputStream(input) {
            private void keep(byte[] bytes, int offset, int count) throws IOException {
                if (count > cap - raw.size()) throw new IOException("Provider stream exceeded response byte bound");
                raw.write(bytes, offset, count);
            }
            @Override public int read() throws IOException {
                int value = in.read();
                if (value >= 0) keep(new byte[] {(byte) value}, 0, 1);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                int n = in.read(bytes, offset, count);
                if (n > 0) keep(bytes, offset, n);
                return n;
            }
        };
        BufferedReader reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
        StringBuilder data = new StringBuilder();
        boolean first = true;
        String line;
        while ((line = reader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Provider stream cancelled");
            if (first && line.startsWith("\uFEFF")) line = line.substring(1);
            first = false;
            if (!sse) { if (!line.trim().isEmpty()) event.accept(line); continue; }
            if (line.isEmpty()) {
                if (data.length() > 0) { event.accept(data.substring(0, data.length() - 1)); data.setLength(0); }
            } else if (line.startsWith("data:") || "data".equals(line)) {
                String value = line.length() <= 5 ? "" : line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                data.append(value).append('\n');
            }
        }
        if (data.length() > 0) event.accept(data.substring(0, data.length() - 1));
        return new String(raw.toByteArray(), StandardCharsets.UTF_8);
    }
}
