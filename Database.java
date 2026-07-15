import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;

// Централно място за DB connections. Ползва HikariCP connection pool —
// преди всяко DAO обаждане отваряше НОВА TCP+TLS връзка (нямаше значение
// на localhost, но към отдалечен Supabase това означава пълен network
// round-trip + TLS handshake на ВСЯКО запитване, а някои методи (напр.
// broadcastOnlineUsers) правят по 2 заявки ЗА ВСЕКИ online потребител
// накуп — довеждаше до connection timeout-и под трафик). Сега пазим малък
// пул от вече отворени връзки и ги преизползваме.
//
// Credentials идват от environment variables, не са hardcode-нати:
//   DB_URL      — напр. jdbc:postgresql://host:6543/postgres?prepareThreshold=0&sslmode=require
//   DB_USER
//   DB_PASSWORD — ЗАДЪЛЖИТЕЛНО да се зададе, няма default
public class Database {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/chatdb";
    private static final String DEFAULT_USER = "chatapp_user";

    private static volatile HikariDataSource dataSource;

    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    private static HikariDataSource getDataSource() throws SQLException {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            synchronized (Database.class) {
                ds = dataSource;
                if (ds == null) {
                    ds = buildDataSource();
                    dataSource = ds;
                }
            }
        }
        return ds;
    }

    private static HikariDataSource buildDataSource() throws SQLException {
        String url = envOrDefault("DB_URL", DEFAULT_URL);
        String user = envOrDefault("DB_USER", DEFAULT_USER);
        String password = System.getenv("DB_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new SQLException(
                "DB_PASSWORD environment variable не е зададена. Задай я преди да пуснеш сървъра: "
                + "PowerShell -> $env:DB_PASSWORD = \"твоята-парола\""
            );
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);

        // Малък пул — достатъчен за chat приложение с умерен трафик, и
        // остава добре под Supabase pooler-ните лимити (виж "Pool size" в
        // Supabase dashboard-а, ако някога трябва да го увеличиш).
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);  // 10s — по-бърз fail вместо дълго висене
        config.setIdleTimeout(300_000);       // 5 мин преди да затвори неизползвана връзка
        config.setMaxLifetime(1_800_000);     // 30 мин — рециклира връзки периодично

        return new HikariDataSource(config);
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}