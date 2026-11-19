package mrnavastar.invsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    public String SERVER_ID = "fabric";
    public String DATABASE_TYPE = "SQLITE";
    public String DATABASE_NAME = "InvSync";
    public String SQLITE_DIRECTORY = "config/invsync";
    public String MYSQL_ADDRESS = "127.0.0.1";
    public String MYSQL_PORT = "3306";
    public int MYSQL_CONNECT_TIMEOUT_MS = 3000;
    public int MYSQL_SOCKET_TIMEOUT_MS = 3000;
    public String MYSQL_USERNAME = "username";
    public String MYSQL_PASSWORD = "password";
    public String MYSQL_SSL_MODE = "PREFERRED";
    public String MYSQL_TRUST_STORE = "";
    public String MYSQL_TRUST_STORE_TYPE = "PKCS12";
    public String MYSQL_TRUST_STORE_PASSWORD = "";
    public boolean SYNC_PLAYER_DATA = true;
    public boolean SYNC_ADVANCEMENTS = true;
    public boolean SYNC_STATS = true;

    static Config load() throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("invsync.json");
        if (Files.notExists(path)) {
            Files.createDirectories(path.getParent());
            Config config = new Config();
            Files.writeString(path, gson.toJson(config));
            return config;
        }

        try (var reader = Files.newBufferedReader(path)) {
            Config config = gson.fromJson(reader, Config.class);
            if (config == null) throw new IOException("Empty InvSync config: " + path);
            return config;
        }
    }
}
