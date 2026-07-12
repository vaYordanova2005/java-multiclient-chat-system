import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// Централно място за DB connection — credentials идват от environment
// variables (DB_URL, DB_USER, DB_PASSWORD), не са hardcode-нати в кода.
// PowerShell: $env:DB_URL / $env:DB_USER / $env:DB_PASSWORD преди старт.
public class Database {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/chatdb";
    private static final String DEFAULT_USER = "chatapp_user";

    public static Connection getConnection() throws SQLException {
        String url = envOrDefault("DB_URL", DEFAULT_URL);
        String user = envOrDefault("DB_USER", DEFAULT_USER);
        String password = System.getenv("DB_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new SQLException(
                "DB_PASSWORD environment variable не е зададена. Задай я преди да пуснеш сървъра: "
                + "PowerShell -> $env:DB_PASSWORD = \"твоята-парола\""
            );
        }

        return DriverManager.getConnection(url, user, password);
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
