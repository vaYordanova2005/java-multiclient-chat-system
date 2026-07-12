public class ServerMain {

    // Render инжектира PORT автоматично при deploy — при тях сървърът НЕ
    // трябва да борави сам с TLS сертификати (те терминират HTTPS/WSS на
    // техния edge с истински сертификат и препращат обикновен WS локално
    // в контейнера). Локално без PORT просто пада на 5000.
    private static final int PORT = Integer.parseInt(envOrDefault("PORT", "5000"));

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    public static void main(String[] args) {
        Server server = new Server(PORT);
        server.start();
    }
}