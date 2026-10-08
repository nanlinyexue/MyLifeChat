package fd.chat.integration;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

/**
 * LuckPerms 前缀/后缀桥接。
 *
 * 为什么自己取而不是用 MiniPlaceholders 的 &lt;prefix&gt;：
 *   MiniPlaceholders 的 audience 占位符依赖 MiniMessage 的 Pointered 上下文，
 *   在「逐收件人渲染」的场景下容易拿不到正确的上下文，表现为标签原样输出。
 *   直接向 LuckPerms 取元数据更可控、可缓存、也不依赖额外扩展 jar。
 *
 * 用反射调用，避免与 LuckPerms 产生编译期耦合（LuckPerms 是软依赖）。
 */
public final class LuckPermsBridge {

    private final ProxyServer proxy;
    private final Logger logger;
    private final boolean available;
    private final Object userManager;
    private final MiniMessage mini = MiniMessage.miniMessage();

    private final ConcurrentHashMap<UUID, CacheEntry> prefixCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CacheEntry> suffixCache = new ConcurrentHashMap<>();

    private record CacheEntry(String value, long at) {
    }

    private static final long CACHE_MS = TimeUnit.SECONDS.toMillis(30);

    public LuckPermsBridge(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
        Object manager = null;
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            manager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
        } catch (Throwable t) {
            logger.info("未检测到 LuckPerms，前缀/后缀将为空。");
        }
        this.userManager = manager;
        this.available = manager != null;
    }

    public boolean available() {
        return this.available;
    }

    /** 取前缀（已按 MiniMessage 解析为组件）。没有则返回空组件。 */
    public Component prefix(Player player) {
        return render(meta(player, "prefix"));
    }

    public Component suffix(Player player) {
        return render(meta(player, "suffix"));
    }

    public String rawPrefix(Player player) {
        return meta(player, "prefix");
    }

    public String rawSuffix(Player player) {
        return meta(player, "suffix");
    }

    private Component render(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return this.mini.deserialize(text);
        } catch (Exception ex) {
            return Component.text(text);
        }
    }

    /**
     * 读取 LuckPerms 元数据。
     * 优先用缓存的 User 对象（不阻塞），缓存命中直接返回。
     */
    private String meta(Player player, String key) {
        if (!this.available) {
            return "";
        }
        UUID uuid = player.getUniqueId();
        ConcurrentHashMap<UUID, CacheEntry> cache = key.equals("prefix") ? this.prefixCache : this.suffixCache;
        CacheEntry cached = cache.get(uuid);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CACHE_MS) {
            return cached.value();
        }

        String value = "";
        try {
            // getUserManager().getUser(uuid) 返回加载好的用户或 null
            Object user = this.userManager.getClass()
                .getMethod("getUser", UUID.class)
                .invoke(this.userManager, uuid);
            if (user == null) {
                // 未加载则在后台加载一次，避免阻塞聊天链路
                loadAsync(uuid);
                return cached == null ? "" : cached.value();
            }
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
            Object result = metaData.getClass()
                .getMethod(key.equals("prefix") ? "getPrefix" : "getSuffix")
                .invoke(metaData);
            if (result instanceof String s) {
                value = s;
            }
        } catch (Throwable t) {
            this.logger.debug("读取 LuckPerms {} 失败。", key, t);
            return cached == null ? "" : cached.value();
        }

        cache.put(uuid, new CacheEntry(value, now));
        return value;
    }

    private void loadAsync(UUID uuid) {
        try {
            this.userManager.getClass()
                .getMethod("loadUser", UUID.class)
                .invoke(this.userManager, uuid);
        } catch (Throwable ignored) {
            // 加载失败就下次再试
        }
    }

    /** 玩家权限变更后清缓存（可由外部调用）。 */
    public void invalidate(UUID uuid) {
        this.prefixCache.remove(uuid);
        this.suffixCache.remove(uuid);
    }
}
