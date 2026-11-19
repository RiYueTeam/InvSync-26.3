package mrnavastar.invsync;

import mrnavastar.invsync.sync.PlayerStore;
import mrnavastar.invsync.sync.SyncManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;

public class InvSync implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("InvSync");
    public static SyncManager sync;

    @Override
    public void onInitialize() {
        try {
            Config config = Config.load();
            var database = new PlayerStore.Settings(
                    config.DATABASE_TYPE, config.DATABASE_NAME, config.SQLITE_DIRECTORY,
                    config.MYSQL_ADDRESS, config.MYSQL_PORT, config.MYSQL_USERNAME, config.MYSQL_PASSWORD,
                    new PlayerStore.TlsSettings(config.MYSQL_SSL_MODE, config.MYSQL_TRUST_STORE,
                            config.MYSQL_TRUST_STORE_TYPE, config.MYSQL_TRUST_STORE_PASSWORD),
                    config.MYSQL_CONNECT_TIMEOUT_MS, config.MYSQL_SOCKET_TIMEOUT_MS);
            sync = new SyncManager(config, new PlayerStore(database));
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not initialize InvSync", e);
        }
    }
}
