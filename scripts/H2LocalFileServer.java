import java.sql.*;
import java.util.Arrays;
import com.consense.config.H2LocalFileAccess;

/** Local H2 filesystem adapter for this Windows JDK's false NIO access check. */
public class H2LocalFileServer {
  public static void main(String[] args)throws Exception {
    H2LocalFileAccess.register();
    if(args[0].equals("--probe")){
      String url="jdbc:h2:rwfile:"+args[1];
      try(Connection c=DriverManager.getConnection(url,"sa","");Statement s=c.createStatement()){
        s.execute("CREATE MEMORY TABLE probe(id INT PRIMARY KEY, body VARCHAR)");s.execute("INSERT INTO probe VALUES(1, 'unchanged')");
      }
      try(Connection c=DriverManager.getConnection(url+";IFEXISTS=TRUE","sa","");Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT body FROM probe WHERE id=1")){
        if(!r.next()||!"unchanged".equals(r.getString(1))||c.isReadOnly())throw new IllegalStateException("Reopen failed");
        s.execute("INSERT INTO probe VALUES(2,'read-write reopen')");
      }
      System.out.println("SMALL_H2_FILE_CREATE_CLOSE_REOPEN_READ_WRITE_PASS");return;
    }
    if(args[0].equals("--shell"))org.h2.tools.Shell.main(Arrays.copyOfRange(args,1,args.length));
    else org.h2.tools.Server.main(args);
  }
}
