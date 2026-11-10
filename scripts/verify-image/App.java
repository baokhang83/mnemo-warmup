import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

/** A tiny HTTP app for scripts/verify-image.sh: serves /health, prints its PID, optionally exits at once. */
public class App {
    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("port", 8080);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        System.out.println("pid=" + ProcessHandle.current().pid());
        System.out.println("listening");
        if (System.getProperty("exit.after.start") != null) {
            server.stop(0);
        }
    }
}
