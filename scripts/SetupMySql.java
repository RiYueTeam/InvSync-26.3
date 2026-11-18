import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

public class SetupMySql {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TLS_MODE = "VERIFY_IDENTITY";

    record Settings(String host, int port, String database, String user, String fabricSource,
                    String spigotSource, String admin, Path ca) {
        Settings {
            if (!host.matches("[a-zA-Z0-9_.-]+|\\[[0-9a-fA-F:.]+]") || port < 1 || port > 65535
                    || !database.matches("[a-zA-Z0-9_-]{1,64}") || !user.matches("[a-zA-Z0-9_-]{1,32}")
                    || !validSource(fabricSource) || !validSource(spigotSource) || admin.isBlank()) {
                throw new IllegalArgumentException("Invalid MySQL address, database, user or source host.");
            }
        }

        private static boolean validSource(String source) {
            return source.length() <= 255 && source.matches("[a-zA-Z0-9.:-]+");
        }
    }

    public static void main(String[] args) {
        if (args.length == 3 && args[0].equals("--bootstrap-stdin")) {
            bootstrap(args);
            return;
        }
        Console console = System.console();
        if (console == null || args.length != 1) {
            System.err.println("请在终端运行：java scripts/SetupMySql.java <Fabric-JAR>");
            System.exit(1);
            return;
        }

        char[] adminPassword = null;
        boolean failed = false;
        try {
            Path jar = Path.of(args[0]);
            if (!Files.isRegularFile(jar)) throw new IOException("找不到 Fabric JAR。");
            String host = prompt(console, "MySQL 域名（需匹配证书）", "");
            int port = Integer.parseInt(prompt(console, "MySQL 端口", "3306"));
            String database = prompt(console, "数据库名", "InvSync");
            String user = prompt(console, "新建的同步账号", "invsync_user");
            String fabric = prompt(console, "MySQL 看到的 Fabric 来源 IP 或主机名", "");
            String spigot = prompt(console, "MySQL 看到的 Spigot 来源 IP 或主机名", fabric);
            String ca = prompt(console, "私有 CA 的 PEM 文件路径（默认信任库可信则留空）", "");
            String admin = prompt(console, "MySQL 管理账号", "root");
            Settings settings = new Settings(host, port, database, user, fabric, spigot, admin,
                    ca.isBlank() ? null : Path.of(ca.replaceAll("^\"|\"$", "")));
            adminPassword = console.readPassword("MySQL 管理账号密码（输入时隐藏）: ");
            if (adminPassword == null) throw new IOException("输入已取消。");
            Path result = setup(jar, settings, adminPassword, Path.of("."));
            System.out.println("已生成 " + result);
            System.out.println("停服后，分别将 Fabric 和 Spigot 目录里的文件复制到对应服务端根目录。");
        } catch (Exception failure) {
            System.err.println("配置失败：" + failure.getMessage());
            failed = true;
        } finally {
            if (adminPassword != null) Arrays.fill(adminPassword, '\0');
        }
        if (failed) System.exit(1);
    }

    private static void bootstrap(String[] args) {
        char[] password = null;
        boolean failed = false;
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            Settings settings = new Settings(readLine(input), Integer.parseInt(readLine(input)),
                    "InvSync", "invsync_user", readLine(input), readLine(input), "root", Path.of(readLine(input)));
            password = readLine(input).toCharArray();
            System.out.println(setup(Path.of(args[1]), settings, password, Path.of(args[2]), "localhost"));
        } catch (Exception failure) {
            System.err.println("配置失败：" + failure.getMessage());
            failed = true;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
        if (failed) System.exit(1);
    }

    private static String readLine(BufferedReader input) throws IOException {
        String value = input.readLine();
        if (value == null) throw new IOException("配置输入不完整。");
        return value;
    }

    private static String prompt(Console console, String text, String fallback) throws IOException {
        String value = console.readLine("%s%s: ", text, fallback.isEmpty() ? "" : " [" + fallback + "]");
        if (value == null) throw new IOException("输入已取消。");
        return value.isBlank() ? fallback : value.trim();
    }

    static Path setup(Path jar, Settings settings, char[] adminPassword, Path outputParent) throws Exception {
        Path connector = extractConnector(jar);
        try {
            return setup(connector, settings, adminPassword, outputParent, settings.host());
        } finally {
            Files.deleteIfExists(connector);
        }
    }

    private static Path setup(Path connector, Settings settings, char[] adminPassword, Path outputParent,
                              String adminHost) throws Exception {
        Path output = Files.createTempDirectory(outputParent.toAbsolutePath(), "invsync-config-");
        Path fabric = Files.createDirectories(output.resolve("Fabric/config"));
        Path spigot = Files.createDirectories(output.resolve("Spigot/plugins/InvSyncSpigot"));

        var properties = new Properties();
        properties.setProperty("user", settings.admin());
        properties.setProperty("password", new String(adminPassword));
        properties.setProperty("sslMode", TLS_MODE);
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "10000");
        String storePassword = settings.ca() == null ? "" : randomSecret();
        if (settings.ca() != null) {
            Path trustStore = fabric.resolve("mysql-ca.p12");
            createTrustStore(settings.ca(), trustStore, storePassword);
            Files.copy(trustStore, spigot.resolve("mysql-ca.p12"));
            properties.setProperty("trustCertificateKeyStoreUrl", trustStore.toUri().toASCIIString());
            properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
            properties.setProperty("trustCertificateKeyStorePassword", storePassword);
            properties.setProperty("fallbackToSystemTrustStore", "false");
        }

        String password = randomSecret();
        try (var loader = new URLClassLoader(new URL[]{connector.toUri().toURL()})) {
            Driver driver = Class.forName("com.mysql.cj.jdbc.Driver", true, loader)
                    .asSubclass(Driver.class).getConstructor().newInstance();
            String url = "jdbc:mysql://" + adminHost + ":" + settings.port() + "/";
            try (Connection connection = connect(driver, url, properties)) {
                provision(connection, settings, password);
            }
        } finally {
            properties.remove("password");
        }

        Files.writeString(fabric.resolve("invsync.json"), """
                {
                  "DATABASE_TYPE": "MYSQL",
                  "DATABASE_NAME": "%s",
                  "MYSQL_ADDRESS": "%s",
                  "MYSQL_PORT": "%d",
                  "MYSQL_USERNAME": "%s",
                  "MYSQL_PASSWORD": "%s",
                  "MYSQL_SSL_MODE": "%s",
                  "MYSQL_TRUST_STORE": "%s",
                  "MYSQL_TRUST_STORE_TYPE": "PKCS12",
                  "MYSQL_TRUST_STORE_PASSWORD": "%s",
                  "SYNC_PLAYER_DATA": true,
                  "SYNC_ADVANCEMENTS": true
                }
                """.formatted(settings.database(), settings.host(), settings.port(), settings.user(), password, TLS_MODE,
                settings.ca() == null ? "" : "config/mysql-ca.p12", storePassword));
        Files.writeString(spigot.resolve("config.yml"), """
                mysql:
                  host: '%s'
                  port: %d
                  database: '%s'
                  username: '%s'
                  password: '%s'
                  tls:
                    mode: %s
                    trust-store: '%s'
                    trust-store-type: PKCS12
                    trust-store-password: '%s'
                """.formatted(settings.host(), settings.port(), settings.database(), settings.user(), password, TLS_MODE,
                settings.ca() == null ? "" : "plugins/InvSyncSpigot/mysql-ca.p12", storePassword));
        return output;
    }

    private static Connection connect(Driver driver, String url, Properties settings) throws IOException {
        try {
            return driver.connect(url, settings);
        } catch (SQLException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            throw new IOException("无法通过严格 TLS 登录 MySQL。请检查域名、CA、连通性和管理账号。"
                    + " MySQL " + failure.getErrorCode() + ": " + cause.getMessage());
        }
    }

    private static void provision(Connection connection, Settings settings, String password) throws IOException {
        var sources = new LinkedHashSet<>(List.of(settings.fabricSource(), settings.spigotSource()));
        var accounts = sources.stream().map(host -> "'" + settings.user() + "'@'" + host + "'").toList();
        String accountList = String.join(", ", accounts);
        String database = "`" + settings.database() + "`";
        String phase = "建库";
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + database);
            phase = "创建同步账号（若账号已存在，请换一个同步账号名）";
            statement.executeUpdate("CREATE USER " + String.join(", ", accounts.stream()
                    .map(account -> account + " IDENTIFIED BY '" + password + "'").toList()) + " REQUIRE SSL");
            phase = "为 InvSync_base 授权";
            try {
                statement.executeUpdate("GRANT CREATE, SELECT, INSERT, UPDATE ON " + database
                        + ".InvSync_base TO " + accountList);
            } catch (SQLException failure) {
                try {
                    statement.executeUpdate("DROP USER " + accountList);
                } catch (SQLException cleanupFailure) {
                    phase += "，新建账号清理失败，请管理员检查后重试";
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException(phase + "失败。MySQL 错误 " + failure.getErrorCode()
                    + "，SQLSTATE " + failure.getSQLState());
        }
    }

    private static void createTrustStore(Path ca, Path target, String password) throws Exception {
        var factory = CertificateFactory.getInstance("X.509");
        var store = KeyStore.getInstance("PKCS12");
        store.load(null);
        try (var input = Files.newInputStream(ca)) {
            var certs = factory.generateCertificates(input);
            if (certs.isEmpty()) throw new IOException("CA 文件没有可用的 X.509 证书。");
            int index = 0;
            for (var cert : certs) store.setCertificateEntry("mysql-ca-" + ++index, cert);
        }
        try (var output = Files.newOutputStream(target)) {
            store.store(output, password.toCharArray());
        }
    }

    private static Path extractConnector(Path jar) throws IOException {
        try (var archive = new ZipFile(jar.toFile())) {
            var entry = archive.stream().filter(file -> file.getName().startsWith("META-INF/jars/")
                    && file.getName().contains("mysql-connector") && file.getName().endsWith(".jar"))
                    .findFirst().orElseThrow(() -> new IOException("指定的 Fabric JAR 不包含 MySQL 驱动。"));
            Path driver = Files.createTempFile("invsync-mysql-driver-", ".jar");
            driver.toFile().deleteOnExit();
            try (var input = archive.getInputStream(entry); var output = Files.newOutputStream(driver)) {
                input.transferTo(output);
            }
            return driver;
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}