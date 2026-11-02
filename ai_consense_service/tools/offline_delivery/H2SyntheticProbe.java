import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Embedded, disposable fixture only; the admission gate must provide a fresh private tmpfs. */
public final class H2SyntheticProbe {
    private static final Path ROOT = Paths.get("/runtime/probe");
    private static final Path DB_DIR = ROOT.resolve("db");
    private static final Path DB_FILE = DB_DIR.resolve("fixture.mv.db");
    private static final Path READY = DB_DIR.resolve("phase1.complete");
    private static final String URL = "jdbc:h2:file:/runtime/probe/db/fixture;DB_CLOSE_ON_EXIT=FALSE";
    private static final String NEW_PASSWORD = "synthetic-only-new-password";
    private static final String WRONG_PASSWORD = "synthetic-only-wrong-password";

    public static void main(String[] args) {
        System.out.println("synthetic fixture only; no inference about real database credentials");
        System.out.println("H2 DDL may commit implicitly; commit does not establish transactional rollback");
        try {
            require(args.length == 1 && ("phase1".equals(args[0]) || "phase2".equals(args[0])), "invalid phase");
            require(Files.isDirectory(ROOT, LinkOption.NOFOLLOW_LINKS), "fixture root unavailable");
            if ("phase1".equals(args[0])) phase1(); else phase2();
            System.out.println(args[0] + " complete");
        } catch (Exception e) {
            String state = e instanceof SQLException ? ((SQLException) e).getSQLState() : null;
            System.err.println("probe failed: " + e.getClass().getSimpleName()
                    + " sqlstate=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "unavailable"));
            System.exit(1);
        }
    }

    private static void phase1() throws Exception {
        Files.createDirectory(DB_DIR);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(DB_DIR)) {
            require(!entries.iterator().hasNext(), "fixture directory must be empty");
        }
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            verifyVersion(c);
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE probe_marker (id INT PRIMARY KEY, marker VARCHAR(32) NOT NULL)");
                s.executeUpdate("INSERT INTO probe_marker VALUES (1, 'synthetic-marker')");
            }
            System.out.println("phase1 preparing password statement");
            try (PreparedStatement s = c.prepareStatement("ALTER USER SA SET PASSWORD ?")) {
                System.out.println("phase1 password statement prepared");
                s.setString(1, NEW_PASSWORD);
                System.out.println("phase1 executing password statement");
                s.execute();
            }
            System.out.println("phase1 password statement executed; DDL may have committed implicitly");
            c.commit();
            System.out.println("phase1 commit returned");
        }
        Files.write(READY, "phase1 closed\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void phase2() throws Exception {
        require(Files.isDirectory(DB_DIR, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(READY, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(DB_FILE, LinkOption.NOFOLLOW_LINKS), "phase1 fixture unavailable");
        String existing = URL + ";IFEXISTS=TRUE";
        requireAuthenticationFailure(existing, "", "old password");
        requireAuthenticationFailure(existing, WRONG_PASSWORD, "wrong password");
        try (Connection c = DriverManager.getConnection(existing, "sa", NEW_PASSWORD)) {
            verifyVersion(c);
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT marker FROM probe_marker WHERE id=1")) {
                require(r.next() && "synthetic-marker".equals(r.getString(1)) && !r.next(), "marker mismatch");
            }
        }
        System.out.println("phase2 new synthetic credential and marker verified");
    }

    private static void requireAuthenticationFailure(String url, String password, String label) throws Exception {
        try (Connection ignored = DriverManager.getConnection(url, "sa", password)) {
            throw new IllegalStateException(label + " unexpectedly accepted");
        } catch (SQLException e) {
            if (!"28000".equals(e.getSQLState()) || e.getErrorCode() != 28000) throw e;
            System.out.println("phase2 " + label + " rejected sqlstate=28000 errorcode=28000");
        }
    }

    private static void verifyVersion(Connection c) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        String database = meta.getDatabaseProductVersion();
        String driver = meta.getDriverVersion();
        require(database != null && database.matches("2\\.1\\.214(?:\\s.*)?")
                && driver != null && driver.matches("2\\.1\\.214(?:\\s.*)?"), "H2 version mismatch");
        System.out.println("java.version=" + System.getProperty("java.version") + " h2.database=" + database
                + " h2.driver=" + driver);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
