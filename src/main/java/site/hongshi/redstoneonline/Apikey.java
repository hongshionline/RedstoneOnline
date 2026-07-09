package site.hongshi.redstoneonline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

public class Apikey {
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String makeApikey() {
        //创建apikey
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public static void saveApikey(Path cacheDir, String key) {
        //保存apikey
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve("apikey.txt"), key, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static String loadApikey(Path cacheDir) {
        //获取apikey
        try {
            Path file = cacheDir.resolve("apikey.txt");
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
