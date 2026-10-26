package com.consense.service.sources;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The transport must not need a whole-response UTF-16 workspace before its final String. */
class CanonicalReplyAllocationTest {
    @TempDir Path temporary;

    @Test void large_ascii_reply_decodes_inside_a_heap_that_excludes_the_extra_utf16_copy() throws Exception {
        String java = new File(System.getProperty("java.home"), "bin/java.exe").getPath();
        if (!new File(java).isFile()) java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path output = temporary.resolve("reply-allocation-probe.log");
        Process child = new ProcessBuilder(java, "-Xms32m", "-Xmx224m", "-cp", classpath,
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Constrained-heap reply probe timed out");
            String log = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            System.out.print(log);
            assertEquals(0, child.exitValue(), log);
            assertTrue(log.contains("REPLY_ALLOCATION_OK bytes=67108864 chars=67108864"), log);
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor();
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            byte[] bytes = new byte[64 * 1024 * 1024];
            Arrays.fill(bytes, (byte) 'x');
            CanonicalEvidenceBackend.Reply reply = new CanonicalEvidenceBackend.Reply(
                    bytes, "application/json", null, null, null);
            System.out.println("REPLY_ALLOCATION_START bytes=" + bytes.length
                    + " maxHeap=" + Runtime.getRuntime().maxMemory()
                    + " java=" + System.getProperty("java.version"));
            String decoded = reply.json();
            if (decoded.length() != bytes.length || decoded.charAt(0) != 'x'
                    || decoded.charAt(decoded.length() - 1) != 'x' || reply.bytes[0] != 'x') {
                throw new AssertionError("Transport changed the decoded reply");
            }
            System.out.println("REPLY_ALLOCATION_OK bytes=" + reply.bytes.length + " chars=" + decoded.length());
        }
    }
}
