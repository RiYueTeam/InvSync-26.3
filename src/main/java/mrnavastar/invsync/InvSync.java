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
            sync = new SyncManager(config, new PlayerStore(config));
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not initialize InvSync", e);
        }
    }
}
