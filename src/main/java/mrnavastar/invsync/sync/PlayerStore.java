package mrnavastar.invsync.sync;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

public final class PlayerStore {
    private final boolean mysql;
    private final String url;
    private final Properties properties = new Properties();
    private final Path lockDirectory;
    private final Driver driver;
    private final MySqlSessions shared;
    private final Path journalDirectory;

    public record Settings(String type, String name, String sqliteDirectory, String address, String port,
                           String username, String password, TlsSettings tls) {}

    public record TlsSettings(String mode, String trustStore, String trustStoreType, String trustStorePassword) {
        private void configure(Properties properties) throws IOException {
            String sslMode = mode.trim().toUpperCase(Locale.ROOT);
            switch (sslMode) {
                case "DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY" ->
                        properties.setProperty("sslMode", sslMode);
                default -> throw new IllegalArgumentException("Invalid MySQL sslMode: " + mode);
            }
            if (trustStore.isBlank()) return;
            if (!sslMode.equals("VERIFY_CA") && !sslMode.equals("VERIFY_IDENTITY")) {
                throw new IllegalArgumentException("A MySQL trust store requires VERIFY_CA or VERIFY_IDENTITY");
            }

            Path path = Path.of(trustStore).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IOException("MySQL trust store is not a readable file: " + path);
            }
            String type = trustStoreType.trim().toUpperCase(Locale.ROOT);
            if (!type.equals("PKCS12") && !type.equals("JKS")) {
                throw new IllegalArgumentException("MySQL trust store type must be PKCS12 or JKS");
            }
            properties.setProperty("trustCertificateKeyStoreUrl", path.toUri().toASCIIString());
            properties.setProperty("trustCertificateKeyStoreType", type);
            properties.setProperty("trustCertificateKeyStorePassword", trustStorePassword);
            properties.setProperty("fallbackToSystemTrustStore", "false");
        }
    }

    public PlayerStore(Settings config) throws IOException, SQLException {
        mysql = switch (config.type()) {
            case "MYSQL" -> true;
            case "SQLITE" -> false;
            default -> throw new IllegalArgumentException("DATABASE_TYPE must be SQLITE or MYSQL");
        };

        if (!config.name().matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid InvSync database name");
        }

        if (mysql) {
            String host = config.address().trim();
            if (!host.matches("[a-zA-Z0-9_.-]+|\\[[0-9a-fA-F:.]+]")) {
                throw new IllegalArgumentException("MySQL address must be a hostname or IP address");
            }
            String port = config.port().trim();
            if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
                throw new IllegalArgumentException("MySQL port must be between 1 and 65535");
            }
            url = "jdbc:mysql://" + host + ":" + port + "/" + config.name();
            config.tls().configure(properties);
            properties.setProperty("user", config.username());
            properties.setProperty("password", config.password());
            properties.setProperty("connectTimeout", "3000");
            properties.setProperty("socketTimeout", "3000");
            lockDirectory = null;
            driver = new com.mysql.cj.jdbc.Driver();
        } else {
            Path dir = Files.createDirectories(Path.of(config.sqliteDirectory())).toRealPath();
            url = "jdbc:sqlite:" + dir.resolve(config.name() + ".db");
            lockDirectory = Files.createDirectories(dir.resolve(config.name() + ".locks"));
            properties.setProperty("busy_timeout", "3000");
            try {
                driver = Class.forName("org.sqlite.JDBC").asSubclass(Driver.class).getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new SQLException("SQLite JDBC driver unavailable", e);
            }
        }

        journalDirectory = mysql ? Files.createDirectories(Path.of("invsync-pending", config.name(),
                UUID.nameUUIDFromBytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())) : null;
        schema("base", """
                    CREATE TABLE IF NOT EXISTS InvSync_base (
                        ID VARCHAR(36) PRIMARY KEY,
                        playerData LONGTEXT,
                        advancements LONGTEXT,
                        stats LONGTEXT,
                        dataInUse INT DEFAULT 0
                    )
                    """);
        shared = mysql ? new MySqlSessions(this, journalDirectory) : null;
    }

    void schema(String name, String... ddl) throws SQLException {
        Path marker = mysql ? journalDirectory.resolve(name + ".initialized") : null;
        try (Connection c = connect(); var sql = c.createStatement()) {
            for (String query : ddl) sql.executeUpdate(query);
            if (marker != null) Files.writeString(marker, "");
        } catch (SQLException e) {
            if (marker == null || !Files.isRegularFile(marker) || e.getSQLState() == null
                    || !e.getSQLState().startsWith("08")) throw e;
        } catch (IOException e) {
            throw new SQLException("Could not record schema initialization", e);
        }
    }

    boolean isMySql() { return mysql; }

    public void stop() { if (shared != null) shared.stop(); }

    Connection connect() throws SQLException {
        return driver.connect(url, properties);
    }

    public Session acquire(UUID login, BindingCodes bindings) throws SQLException, IOException {
        if (mysql) return shared.acquire(login, bindings);
        String id = (bindings == null ? login : bindings.saveId(login)).toString();
        Connection connection = connect();
        FileChannel channel = null;
        boolean acquired = false;
        try {
            channel = FileChannel.open(lockDirectory.resolve(id), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                acquired = channel.tryLock() != null;
            } catch (OverlappingFileLockException ignored) {
                acquired = false;
            }
            if (acquired) return new JdbcSession(connection, channel, id, false);
            return null;
        } finally {
            if (!acquired) {
                try (Connection unused = connection; FileChannel file = channel) {
                    // Closing releases the database connection and any file resources.
                }
            }
        }
    }

    public record Snapshot(String playerData, String advancements, String stats) {}

    public interface Session extends AutoCloseable {
        String id();
        Snapshot load() throws SQLException;
        void save(Snapshot snapshot) throws SQLException, IOException;
        default void finish() throws SQLException, IOException { close(); }
        void close() throws SQLException, IOException;
    }

    record JdbcSession(Connection connection, FileChannel lock, String id, boolean mysql) implements Session {
        public Snapshot load() throws SQLException {
            try (var statement = connection.prepareStatement(
                    "SELECT playerData, advancements, stats FROM InvSync_base WHERE ID = ?")) {
                statement.setString(1, id);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) return new Snapshot(null, null, null);
                    return new Snapshot(result.getString(1), result.getString(2), result.getString(3));
                }
            }
        }

        public void save(Snapshot data) throws SQLException {
            String update = mysql ? """
                    ON DUPLICATE KEY UPDATE playerData = COALESCE(VALUES(playerData), playerData),
                    advancements = COALESCE(VALUES(advancements), advancements), stats = COALESCE(VALUES(stats), stats)
                    """ : """
                    ON CONFLICT(ID) DO UPDATE SET playerData = COALESCE(excluded.playerData, playerData),
                    advancements = COALESCE(excluded.advancements, advancements), stats = COALESCE(excluded.stats, stats)
                    """;
            try (var statement = connection.prepareStatement("""
                    INSERT INTO InvSync_base (ID, playerData, advancements, stats, dataInUse)
                    VALUES (?, ?, ?, ?, 0)
                    """ + update)) {
                statement.setString(1, id);
                statement.setString(2, data.playerData());
                statement.setString(3, data.advancements());
                statement.setString(4, data.stats());
                statement.executeUpdate();
            }
        }

        @Override
        public void close() throws IOException, SQLException {
            try (FileChannel file = lock; Connection database = connection) {
                // The MySQL advisory lock belongs to the connection.
            }
        }
    }
}
