import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Synthetic fixture only; admission must pin the loopback server and source bytes. */
public final class H2TcpSyntheticClient {
    private static final String URL = "jdbc:h2:tcp://127.0.0.1:28682/./fixture;IFEXISTS=TRUE";
    private static final String PASSWORD = "synthetic-only-new-password";

    public static void main(String[] args) {
        try {
            require(args.length == 1 && ("write".equals(args[0]) || "read".equals(args[0])), "invalid phase");
            try (Connection c = DriverManager.getConnection(URL, "sa", PASSWORD)) {
                verifyVersion(c);
                verifyMarker(c, 1, "synthetic-marker");
                if ("write".equals(args[0])) {
                    c.setAutoCommit(false);
                    try (Statement s = c.createStatement()) {
                        require(s.executeUpdate("INSERT INTO probe_marker VALUES (2, 'tcp-synthetic-marker')") == 1,
                                "synthetic write failed");
                    }
                    System.out.println("tcp synthetic commit starting; failure may be ambiguous");
                    c.commit();
                    System.out.println("tcp synthetic commit returned");
                }
                verifyMarker(c, 2, "tcp-synthetic-marker");
            }
            System.out.println("tcp synthetic " + args[0] + " complete");
        } catch (Exception e) {
            String state = e instanceof SQLException ? ((SQLException) e).getSQLState() : null;
            System.err.println("tcp synthetic failed: " + e.getClass().getSimpleName()
                    + " sqlstate=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "unavailable"));
            System.exit(1);
        }
    }

    private static void verifyMarker(Connection c, int id, String expected) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT marker FROM probe_marker WHERE id=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                require(r.next() && expected.equals(r.getString(1)) && !r.next(), "synthetic marker mismatch");
            }
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
