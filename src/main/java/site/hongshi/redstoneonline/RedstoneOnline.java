package site.hongshi.redstoneonline;

import com.mojang.logging.LogUtils;
import dev.kikugie.fletching_table.annotation.fabric.Entrypoint;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
//? if fabric
import net.fabricmc.api.ModInitializer;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?}
//? if neoforge {
/*import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
*///?}


//? if neoforge {
/*@Mod("@MODID@")
*///?} else {
@Entrypoint
//?}
public class RedstoneOnline /*? if fabric {*/ implements ModInitializer /*?}*/ {
    public static final Logger LOGGER = LogUtils.getLogger();

    public static String apikey = "";

    public static final List<Server> servers = Arrays.asList(
        new Server("上海", "122.51.108.96")
    );
    public static int chooseServer = 0;

    private static int commandSelectionResult = -1;

    /** 由 /rs 命令回调，设置选择的服务器索引 */
    public static void setCommandResult(int idx) {
        commandSelectionResult = idx;
    }

    /**
     * 等待玩家通过 /rs 命令选择服务器
     * @param timeoutSeconds 超时秒数
     * @return 选择的服务器索引，超时返回 -1
     */
    public static int waitForCommand(int timeoutSeconds) {
        commandSelectionResult = -1;
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline && commandSelectionResult < 0) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        return commandSelectionResult;
    }

    public static Path getCacheDir() {
        //? if fabric {
        return FabricLoader.getInstance().getGameDir().resolve("redstoneonline");
        //?}
        //? if neoforge {
        /*return FMLPaths.GAMEDIR.get().resolve("redstoneonline");*/
        //?}
    }

    private static void registerApikey(Server server) {
        Path marker = getCacheDir().resolve("registered_" + server.address.replace('.', '_'));
        if (Files.exists(marker)) {
            LOGGER.info("[RedstoneOnline] Register {} ({}) skipped (already registered)", server.address, server.name);
            return;
        }

        try {
            URL url = new URL("http://" + server.address + ":3000/apikey");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            byte[] body = ("{\"apikey\":\"" + apikey + "\"}").getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            String resp;
            if (code >= 400) {
                resp = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } else {
                resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
            conn.disconnect();
            LOGGER.info("[RedstoneOnline] Register {} ({}): {} {}", server.address, server.name, code, resp);

            // 注册成功（200）或密钥已存在（409）都视为已注册，创建标记文件
            if (code == 200 || code == 409) {
                try {
                    Files.createDirectories(marker.getParent());
                    Files.createFile(marker);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOGGER.info("[RedstoneOnline] Register {} ({}) failed: {}", server.address, server.name, e.getMessage());
        }
    }

    //? if fabric {
    @Override
    public void onInitialize() {
        Path cacheDir = getCacheDir();
        String loaded = Apikey.loadApikey(cacheDir);
        if (loaded != null) {
            apikey = loaded;
        } else {
            apikey = Apikey.makeApikey();
            Apikey.saveApikey(cacheDir, apikey);
        }
        LOGGER.info("[RedstoneOnline] Mod initialized! API key: {}", apikey);

        for (Server server : servers) {
            registerApikey(server);
        }
    }
    //?}

    //? if neoforge {
    /*public RedstoneOnline() {
        Path cacheDir = getCacheDir();
        String loaded = Apikey.loadApikey(cacheDir);
        if (loaded != null) {
            apikey = loaded;
        } else {
            apikey = Apikey.makeApikey();
            Apikey.saveApikey(cacheDir, apikey);
        }
        LOGGER.info("[RedstoneOnline] Mod initialized! API key: {}", apikey);

        for (Server server : servers) {
            registerApikey(server);
        }
    }
	*///?}
}
