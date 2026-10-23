package com.consense.config;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.h2.engine.SysProperties;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class H2LocalFileAccessTest {
    @Test void tcpLobsSpoolToDiskAndSurviveDatabaseReopen() throws Exception {
        Path directory = Files.createTempDirectory(Paths.get("target").toAbsolutePath(), "h2-lob-probe-");
        // H2 caches system properties; isolate startup from other database tests.
        String java = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path log = directory.resolve("probe.log");
        Process process = new ProcessBuilder(java, "-Xmx512m", "-Dh2.bindAddress=127.0.0.1",
                "-Djava.io.tmpdir=" + directory, "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bounded TCP LOB probe timed out");
            assertEquals(0, process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static class Probe {
      public static void main(String[] args) throws Exception {
        H2LocalFileAccess.register();
        assertEquals("rwfile:h2.temp", SysProperties.PREFIX_TEMP_FILE);
        assertEquals(1_048_576, SysProperties.LOB_CLIENT_MAX_SIZE_MEMORY);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 240000; i++) text.append("source原文\n");
        String body = text.toString();
        byte[] binary = new byte[1_200_000];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) (i % 251);
        Server server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        String url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/rwfile:"
                + Paths.get(args[0]).resolve("lob-roundtrip").toAbsolutePath().toString().replace('\\', '/');
        try {
            try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE probe(id INT PRIMARY KEY, body CLOB, original BLOB)");
                try (PreparedStatement p = c.prepareStatement("INSERT INTO probe VALUES(1, ?, ?)")) {
                    p.setCharacterStream(1, new StringReader(body), body.length());
                    p.setBinaryStream(2, new ByteArrayInputStream(binary), binary.length);
                    assertEquals(1, p.executeUpdate());
                }
            }
            try (Connection c = DriverManager.getConnection(url + ";IFEXISTS=TRUE", "sa", "");
                 Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT body, original FROM probe WHERE id=1")) {
                assertTrue(r.next());
                assertEquals(body, r.getString(1));
                assertArrayEquals(binary, r.getBytes(2));
                assertFalse(r.next());
            }
        } finally { server.stop(); }
      }
    }
}
