import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Synthetic tmpfs fixture only; the controller must bind this process before GO. */
public final class H2TcpReachabilityProbe {
    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("synthetic probe mismatch");
    }

    public static void main(String[] args) {
        try {
            require(args.length == 0);
            System.out.println("CLIENT_READY");
            System.out.flush();
            byte[] go = new byte[3];
            for (int i = 0; i < go.length; i++) {
                int value = System.in.read();
                if (value < 0) throw new IOException("handshake closed");
                go[i] = (byte) value;
            }
            require("GO\n".equals(new String(go, StandardCharsets.US_ASCII)));
            DriverManager.setLoginTimeout(5);
            try (Connection c = DriverManager.getConnection(
                    "jdbc:h2:tcp://127.0.0.1:28682/./socket_probe", "sa", "synthetic-only-password")) {
                require(c.getMetaData().getDatabaseProductVersion().matches("2\\.1\\.214(?:\\s.*)?"));
                try (Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE probe_marker (id INT PRIMARY KEY, marker VARCHAR(64) NOT NULL)");
                    c.setAutoCommit(false);
                    require(s.executeUpdate("INSERT INTO probe_marker VALUES (1, 'tcp-probe-marker')") == 1);
                    System.out.println("COMMIT_START");
                    System.out.flush();
                    c.commit();
                    System.out.println("COMMIT_RETURNED");
                    try (ResultSet r = s.executeQuery("SELECT marker FROM probe_marker WHERE id=1")) {
                        require(r.next() && "tcp-probe-marker".equals(r.getString(1)) && !r.next());
                    }
                }
            }
            System.out.println("CLIENT_COMPLETE");
        } catch (Exception e) {
            String state = e instanceof SQLException ? ((SQLException) e).getSQLState() : null;
            System.err.println("CLIENT_FAILED " + e.getClass().getSimpleName() + " sqlstate=" +
                    (state != null && state.matches("[A-Z0-9]{5}") ? state : "unavailable"));
            System.exit(1);
        }
    }
}
