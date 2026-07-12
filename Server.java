import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// WebSocket сървър — Java-WebSocket библиотеката управлява I/O нишките
// сама (NIO-based), затова няма нужда от собствен accept-loop/thread-pool
// както при суровия TCP/TLS socket модел отпреди.
//
// TLS вече НЕ се управлява тук — при deploy на Render, Render терминира
// HTTPS/WSS на техния edge с истински сертификат и препраща обикновен
// WS трафик към контейнера. Локално просто ползваш ws:// (некриптирано,
// но е localhost, така че е ОК за разработка).
public class Server extends WebSocketServer {

    private final Map<WebSocket, ClientHandler> handlers = new ConcurrentHashMap<>();

    // CONNECTION LIMIT PER IP — пречи на "connection spam" атака.
    private static final int MAX_CONNECTIONS_PER_IP = 10;
    private final Map<String, Integer> connectionsPerIp = new ConcurrentHashMap<>();

    public Server(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String ip = (conn.getRemoteSocketAddress() != null)
                ? conn.getRemoteSocketAddress().getAddress().getHostAddress()
                : "unknown";

        int currentCount = connectionsPerIp.merge(ip, 1, Integer::sum);

        if (currentCount > MAX_CONNECTIONS_PER_IP) {
            System.out.println("Rejected connection from " + ip + " — too many concurrent connections");
            connectionsPerIp.merge(ip, -1, Integer::sum);
            conn.close();
            return;
        }

        System.out.println("A new client has connected");

        ClientHandler handler = new ClientHandler(conn, () ->
                connectionsPerIp.merge(ip, -1, Integer::sum)
        );
        handlers.put(conn, handler);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        ClientHandler handler = handlers.get(conn);
        if (handler != null) handler.handleIncoming(message);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientHandler handler = handlers.remove(conn);
        if (handler != null) handler.onSocketClosed();
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // Тихо игнорираме — реалният disconnect cleanup минава през onClose,
        // което библиотеката вика веднага след onError за фатални грешки.
    }

    @Override
    public void onStart() {
        System.out.println("[WS] Chat server listening on port " + getPort());
    }
}