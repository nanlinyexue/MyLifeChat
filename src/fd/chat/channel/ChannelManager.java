package fd.chat.channel;

import fd.chat.config.YamlConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * 频道注册表：从配置构建频道，并负责「按前缀切分消息」。
 *
 * 切分规则：取「前缀最长」的已启用频道。
 * 这样如果将来把 global 配成 "!"、guild 配成 "!!"，也不会互相吃掉。
 */
public final class ChannelManager {

    private final Logger logger;
    private volatile Map<String, Channel> channels = Map.of();
    /** 按前缀长度倒序，便于优先匹配长前缀 */
    private volatile List<Channel> byPrefix = List.of();
    private volatile Channel defaultChannel;
    /** 未设置中文名时玩家默认落到哪个频道 */
    private volatile Channel fallbackChannel;

    public ChannelManager(YamlConfig config, Logger logger) {
        this.logger = logger;
        reload(config);
    }

    public void reload(YamlConfig config) {
        Map<String, Channel> map = new LinkedHashMap<>();
        boolean globalFree = config.bool("channels.global.free", true);

        for (String key : List.of(Channel.GLOBAL, Channel.SERVER, Channel.GUILD, Channel.LOCAL)) {
            String base = "channels." + key;
            boolean enabled = config.bool(base + ".enabled", true);
            String prefix = config.str(base + ".prefix", "");
            String tag = config.str(base + ".tag", "");
            long cooldownMs = config.l(base + ".cooldown-seconds", 0) * 1000L;
            int radius = config.i(base + ".radius", -1);
            double toll = config.d(base + ".toll", 0);
            boolean free = config.bool(base + ".free", true);
            String note = config.str(base + ".disabled-message", "");
            String paidTag = config.str(base + ".paid-tag", "");
            String displayName = config.str(base + ".channel-name", tag);

            // 全局频道：free=false 时视为收费
            if (key.equals(Channel.GLOBAL) && !globalFree) {
                free = false;
                if (toll <= 0) {
                    toll = Math.max(0, config.d("channels.global.toll", 0));
                }
            }

            boolean cross = !key.equals(Channel.SERVER) && !key.equals(Channel.LOCAL);

            map.put(key, new Channel(key, enabled, prefix, tag, cooldownMs, cross, radius, toll, free, note, paidTag, displayName));
        }

        // 私聊频道不参与前缀切分，仅占位以便统一处理
        map.put(Channel.PRIVATE, new Channel(
            Channel.PRIVATE,
            config.bool("channels.private.enabled", true),
            "",
            "",
            0,
            true,
            -1,
            0,
            true,
            "",
            "",
            ""
        ));

        this.channels = Map.copyOf(map);

        List<Channel> prefixed = new ArrayList<>();
        for (Channel c : map.values()) {
            if (c.enabled() && !c.prefix().isEmpty() && !c.key().equals(Channel.PRIVATE)) {
                prefixed.add(c);
            }
        }
        prefixed.sort(Comparator.comparingInt((Channel c) -> c.prefix().length()).reversed());
        this.byPrefix = List.copyOf(prefixed);

        // 默认说话频道
        boolean defaultIsGlobal = config.bool("channels.global.default", true);
        if (!globalFree) {
            // 「开启非全服」时强制默认落到本服频道
            defaultIsGlobal = false;
        }
        Channel global = map.get(Channel.GLOBAL);
        Channel server = map.get(Channel.SERVER);
        this.defaultChannel = (defaultIsGlobal && global.enabled()) ? global : server;
        this.fallbackChannel = server;

        this.logger.info("频道已加载：默认={}，前缀频道={}",
            this.defaultChannel.key(),
            prefixed.stream().map(c -> c.prefix() + "→" + c.key()).toList());
    }

    public Optional<Channel> get(String key) {
        return Optional.ofNullable(this.channels.get(key));
    }

    public Channel defaultChannel() {
        return this.defaultChannel;
    }

    public Channel fallbackChannel() {
        return this.fallbackChannel;
    }

    public List<Channel> all() {
        return List.copyOf(this.channels.values());
    }

    /** 一条消息 + 它命中的频道。 */
    public record Parsed(Channel channel, String content, String usedPrefix, boolean explicit) {
    }

    /**
     * 把玩家原始输入切分成「频道 + 正文」。
     * 没有命中任何前缀时使用玩家自己设定的默认频道。
     */
    public Parsed parse(String rawInput, String playerDefaultChannel) {
        String input = rawInput == null ? "" : rawInput;

        for (Channel c : this.byPrefix) {
            if (input.startsWith(c.prefix())) {
                String content = input.substring(c.prefix().length()).trim();
                return new Parsed(c, content, c.prefix(), true);
            }
        }

        // "auto" 表示「跟随服务器默认」。
        // 玩家从未主动切过频道时用 auto，这样管理员改动默认频道能立刻对所有人生效。
        String preference = playerDefaultChannel == null ? "auto" : playerDefaultChannel.trim();
        Channel def;
        if (preference.isEmpty() || preference.equalsIgnoreCase("auto")) {
            def = this.defaultChannel;
        } else {
            // 玩家显式选过频道：尊重其选择，但不许落到已禁用或已收费的频道
            Channel chosen = this.channels.get(preference);
            if (chosen == null || !chosen.enabled() || chosen.key().equals(Channel.PRIVATE)) {
                def = this.defaultChannel;
            } else if (chosen.tolled()) {
                // 之前免费、现在收费的频道不再作为默认，避免玩家莫名被扣费
                def = this.fallbackChannel;
            } else {
                def = chosen;
            }
        }
        if (!def.enabled()) {
            def = this.fallbackChannel;
        }
        return new Parsed(def, input.trim(), "", false);
    }
}
