package mrnavastar.invsync.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/** One-time codes pair two in-game roles on distinct servers. */
public final class BindingCodes {
    public static final String HELP = """
            InvSync 跨服存档绑定
            进服后即可领取绑定码。绑定时请核对角色名和 UUID。
            /invsync code — 在第一台服领取一次性绑定码，5 分钟有效。
            /invsync redeem <绑定码> — 在第二台服兑换并查看两个角色。
            /invsync keep here — 保留当前服角色的存档。
            /invsync keep other — 保留发码服角色的存档。
            /invsync cancel — 取消尚未确认的选择。
            确认前请整理另一侧的背包和末影箱。选择后重新登录生效。""";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PlayerStore store;
    private final Map<UUID, Choice> choices = new HashMap<>();
    private record Choice(Role issuer, String digest) {}

    public BindingCodes(PlayerStore store) throws SQLException {
        this.store = store;
        store.schema("bindings", """
                    CREATE TABLE IF NOT EXISTS InvSync_binding_codes (
                      issuer VARCHAR(36) PRIMARY KEY,
                      server_id VARCHAR(64) NOT NULL,
                      player_name VARCHAR(64) NOT NULL,
                      digest CHAR(64) NOT NULL UNIQUE,
                      expires_at BIGINT NOT NULL,
                      recipient VARCHAR(36),
                      recipient_server VARCHAR(64)
                    )
                    """, """
                    CREATE TABLE IF NOT EXISTS InvSync_bound_roles (
                      login_id CHAR(36) PRIMARY KEY, save_id CHAR(36) NOT NULL
                    )
                    """);
    }

    private boolean sightingsReady;

    // Discovery is advisory: missing grants or an offline database must not block admission.
    private synchronized void ensureSightings(Connection c) throws SQLException {
        if (sightingsReady) return;
        try (var ddl = c.createStatement()) {
            ddl.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS InvSync_seen_roles (
                      server_id VARCHAR(64) NOT NULL, login_id CHAR(36) NOT NULL,
                      player_name VARCHAR(64) NOT NULL, PRIMARY KEY(server_id, login_id)
                    """ + (store.isMySql() ? ", INDEX(player_name))" : ")"));
        }
        sightingsReady = true;
    }

    public java.util.concurrent.CompletableFuture<String> loginNotice(String server, UUID id, String playerName) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return recordAndFind(new Role(server, id, playerName));
            } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    String recordAndFind(Role role) throws SQLException {
        try (var c = store.connect()) {
            ensureSightings(c);
            String upsert = store.isMySql() ? " ON DUPLICATE KEY UPDATE player_name=?"
                    : " ON CONFLICT(server_id,login_id) DO UPDATE SET player_name=?";
            String name = role.name().toLowerCase(java.util.Locale.ROOT);
            try (var write = c.prepareStatement(
                    "INSERT INTO InvSync_seen_roles(server_id,login_id,player_name) VALUES (?,?,?)" + upsert)) {
                write.setString(1, role.server());
                write.setString(2, role.id().toString());
                write.setString(3, name);
                write.setString(4, name);
                write.executeUpdate();
            }
            try (var find = c.prepareStatement("""
                    SELECT s.server_id,s.login_id,s.player_name FROM InvSync_seen_roles s
                    WHERE s.player_name=? AND s.server_id<>? AND s.login_id<>?
                    AND NOT EXISTS (SELECT 1 FROM InvSync_bound_roles b WHERE b.login_id IN (?,s.login_id))
                    ORDER BY s.server_id,s.login_id
                    """)) {
                find.setString(1, name);
                find.setString(2, role.server());
                find.setString(3, role.id().toString());
                find.setString(4, role.id().toString());
                var peers = new StringBuilder();
                try (var rows = find.executeQuery()) {
                    while (rows.next()) {
                        if (name.equals(rows.getString(3)))
                            peers.append("\n另一服 ").append(rows.getString(1)).append("　UUID ").append(rows.getString(2));
                    }
                }
                if (peers.isEmpty()) return "";
                return "InvSync：发现同名但 UUID 不同、尚未绑定的角色。\n当前服 " + role.server()
                        + "　UUID " + role.id() + peers
                        + "\n若两个角色都是你的，在一服输入 /invsync code，退出后到另一服输入 /invsync redeem <绑定码>。"
                        + "核对后用 /invsync keep here 保留兑换服存档，或 /invsync keep other 保留发码服存档。"
                        + "\n如果不认识另一角色，请联系管理员，不要绑定。";
            }
        }
    }

    public UUID saveId(UUID loginId) throws SQLException {
        try (var c = store.connect()) { return saveId(c, loginId); }
    }

    UUID saveId(Connection c, UUID loginId) throws SQLException {
        try (var q = c.prepareStatement(
                "SELECT save_id FROM InvSync_bound_roles WHERE login_id=?")) {
            q.setString(1, loginId.toString());
            try (var r = q.executeQuery()) {
                return r.next() ? UUID.fromString(r.getString(1)) : loginId;
            }
        }
    }

    boolean hasOccupiedAlias(Connection c, UUID role) throws SQLException {
        try (var q = c.prepareStatement("""
                SELECT 1 FROM InvSync_bound_roles b JOIN InvSync_sessions s ON s.id=b.login_id
                WHERE b.save_id=? AND s.token IS NOT NULL LIMIT 1
                """)) {
            q.setString(1, role.toString());
            try (var rows = q.executeQuery()) { return rows.next(); }
        }
    }

    public void leave(UUID id) {
        choices.remove(id);
    }

    public String command(Role role, String[] args) throws SQLException {
        if (args.length == 1 && args[0].equals("code"))
            return "一次性绑定码（5 分钟内有效）：" + issue(role);
        if (args.length == 2 && args[0].equals("redeem")) {
            var issuer = redeem(role, args[1]);
            if (issuer.isEmpty()) return "绑定码无效、已使用，或两端角色不符合条件。";
            choices.put(role.id(), new Choice(issuer.get(), digest(args[1])));
            return "发码端 " + issuer.get().server() + " " + issuer.get().id()
                    + "；当前端 " + role.server() + " " + role.id()
                    + "。先整理另一边的背包和末影箱。输入 /invsync keep here 保留当前端，"
                    + "或 /invsync keep other 保留发码端。不属于你的角色请取消并联系管理员。";
        }
        if (args.length == 1 && args[0].equals("cancel")) {
            leave(role.id());
            return "已取消本次选择。";
        }
        if (args.length == 2 && args[0].equals("keep")
                && (args[1].equals("here") || args[1].equals("other"))) {
            Choice choice = choices.get(role.id());
            if (choice == null) return "请先兑换另一端的绑定码。";
            UUID primary = args[1].equals("here") ? role.id() : choice.issuer().id();
            try (var c = store.connect()) {
                c.setAutoCommit(false);
                try (var consume = c.prepareStatement("""
                        DELETE FROM InvSync_binding_codes WHERE digest=? AND issuer=? AND recipient=?
                        AND recipient_server=? AND expires_at>?
                        """); var insert = c.prepareStatement(
                        "INSERT INTO InvSync_bound_roles(login_id,save_id) VALUES (?,?),(?,?)")) {
                    if (store.isMySql()) {
                        MySqlSessions.lockRole(c, role.id());
                        MySqlSessions.lockRole(c, choice.issuer().id());
                        try (var q = c.prepareStatement("SELECT token FROM InvSync_sessions WHERE id=?")) {
                            q.setString(1, choice.issuer().id().toString());
                            try (var rows = q.executeQuery()) {
                                if (rows.next() && rows.getString(1) != null) {
                                    c.rollback();
                                    return "请先在发码端退出并等待存档同步，再确认选择。";
                                }
                            }
                        }
                    }
                    consume.setString(1, choice.digest());
                    consume.setString(2, choice.issuer().id().toString());
                    consume.setString(3, role.id().toString());
                    consume.setString(4, role.server());
                    consume.setLong(5, Instant.now().getEpochSecond());
                    if (consume.executeUpdate() != 1) {
                        c.rollback();
                        return "申请已过期或已更换，请重新领取绑定码。";
                    }
                    insert.setString(1, role.id().toString());
                    insert.setString(2, primary.toString());
                    insert.setString(3, choice.issuer().id().toString());
                    insert.setString(4, primary.toString());
                    insert.executeUpdate();
                    if (store.isMySql() && !primary.equals(role.id())) store.requireMappingRefresh(role.id());
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
            }
            choices.remove(role.id());
            return "已绑定到 " + primary + "。两端退出后重新登录生效，另一份存档保留。";
        }
        return HELP;
    }

    public record Role(String server, UUID id, String name) {
        public Role {
            if (server == null || !server.matches("[a-zA-Z0-9_-]{1,64}") || id == null
                    || name == null || name.isBlank() || name.length() > 64) {
                throw new IllegalArgumentException("Invalid authenticated role");
            }
        }
    }

    public String issue(Role role) throws SQLException {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try (Connection connection = store.connect()) {
            connection.setAutoCommit(false);
            try (var remove = connection.prepareStatement(
                    "DELETE FROM InvSync_binding_codes WHERE issuer=? OR expires_at<=?");
                 var insert = connection.prepareStatement("""
                         INSERT INTO InvSync_binding_codes (issuer,server_id,player_name,digest,expires_at)
                         VALUES (?,?,?,?,?)
                         """)) {
                long now = Instant.now().getEpochSecond();
                remove.setString(1, role.id().toString());
                remove.setLong(2, now);
                remove.executeUpdate();
                insert.setString(1, role.id().toString());
                insert.setString(2, role.server());
                insert.setString(3, role.name());
                insert.setString(4, digest(code));
                insert.setLong(5, now + 300);
                insert.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
        return code;
    }

    /** Atomically records the second proof. Redeeming never selects or overwrites a save. */
    public Optional<Role> redeem(Role role, String code) throws SQLException {
        if (code == null || !code.matches("[A-Za-z0-9_-]{22}")) return Optional.empty();
        try (var connection = store.connect()) {
            connection.setAutoCommit(false);
            try (var take = connection.prepareStatement("""
                    UPDATE InvSync_binding_codes SET recipient=?, recipient_server=?
                    WHERE digest=? AND expires_at>? AND recipient IS NULL
                    AND issuer<>? AND server_id<>? AND LOWER(player_name)=LOWER(?)
                    """);
                 var find = connection.prepareStatement(
                         "SELECT server_id,issuer,player_name FROM InvSync_binding_codes WHERE digest=?")) {
                String hash = digest(code);
                take.setString(1, role.id().toString());
                take.setString(2, role.server());
                take.setString(3, hash);
                take.setLong(4, Instant.now().getEpochSecond());
                take.setString(5, role.id().toString());
                take.setString(6, role.server());
                take.setString(7, role.name());
                if (take.executeUpdate() != 1) {
                    connection.rollback();
                    return Optional.empty();
                }
                find.setString(1, hash);
                try (var row = find.executeQuery()) {
                    if (!row.next()) throw new SQLException("Binding request disappeared");
                    var issuer = new Role(row.getString(1), UUID.fromString(row.getString(2)), row.getString(3));
                    if (!issuer.name().equalsIgnoreCase(role.name())) {
                        connection.rollback();
                        return Optional.empty();
                    }
                    connection.commit();
                    return Optional.of(issuer);
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static String digest(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
