package fd.chat.command;

import fd.chat.MyLifeChatPlugin;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地兜底联想：不依赖任何 AI，纯本地计算。
 *
 * 两条路径：
 *  1. 指令名拼错 → 编辑距离找最接近的已知指令
 *  2. 参数像玩家名 → 用 IdentityResolver 做模糊匹配
 *
 * 真正的 AI（语义理解）由独立的 fdai 插件通过 FdChatApi.Provider 接管；
 * 这里只保证「没装 AI 时也有基本可用的提示」。
 */
public final class LocalSuggester {

    private final MyLifeChatPlugin plugin;
    private final Set<String> knownCommands = new LinkedHashSet<>();

    public LocalSuggester(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    /** 从配置与代理注册表刷新已知指令集。 */
    public void refresh() {
        this.knownCommands.clear();
        // 代理侧注册的指令
        try {
            this.knownCommands.addAll(this.plugin.proxy().getCommandManager().getAliases());
        } catch (Throwable ignored) {
            // 某些版本不暴露别名列表，忽略
        }
        // 内置的常用指令（覆盖后端子服的指令，Velocity 看不到）
        this.knownCommands.addAll(BUILTIN);
        // 配置里可以补充
        this.knownCommands.addAll(this.plugin.config().strList("ai.extra-commands"));
    }

    /**
     * 模块化内置指令表。
     * 这些是后端子服注册的指令，代理侧看不到，所以显式列出。
     */
    private static final List<String> BUILTIN = List.of(
        "spawn", "sethome", "delhome", "home", "homes", "tpa", "tpahere", "tpaccept", "tpdeny",
        "tp", "tphere", "back", "near", "hat", "suicide", "warps", "warp", "setwarp", "delwarp",
        "kit", "kits", "pay", "balance", "bal", "baltop", "money", "shop", "ah", "auction",
        "mail", "mailbox", "mail send", "msg", "t", "tell", "r", "reply", "ignore", "unignore",
        "lands", "res", "claim", "unclaim", "trust", "untrust", "party", "friend", "duel",
        "trade", "trademe", "ec", "enderchest", "invsee", "ptime", "pweather", "fly", "speed",
        "god", "heal", "feed", "repair", "gm", "gamemode", "time", "weather", "list", "ping",
        "help", "中文名", "名字", "nick", "频道", "ch", "spy", "fd"
    );

    public List<String> suggestCommand(String wrongCommand) {
        if (wrongCommand == null || wrongCommand.isBlank()) {
            return List.of();
        }
        int maxDistance = this.plugin.config().i("ai.max-edit-distance", 3);
        int limit = this.plugin.config().i("ai.max-suggestions", 3);
        String target = wrongCommand.toLowerCase(Locale.ROOT);

        record Scored(String command, int distance) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String candidate : this.knownCommands) {
            String c = candidate.toLowerCase(Locale.ROOT);
            // 前缀匹配优先当作 0 距离
            if (c.startsWith(target) || target.startsWith(c)) {
                scored.add(new Scored(candidate, 0));
                continue;
            }
            int d = levenshtein(target, c, maxDistance);
            if (d <= maxDistance) {
                scored.add(new Scored(candidate, d));
            }
        }
        scored.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        return scored.stream().limit(limit).map(Scored::command).toList();
    }

    /** 带阈值的编辑距离（超过阈值提前返回，避免无谓计算）。 */
    static int levenshtein(String a, String b, int max) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > max) {
            return max + 1;
        }
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[lb];
    }

    /** 玩家名的模糊候选。 */
    public List<String> suggestPlayer(String partial) {
        if (!this.plugin.config().bool("ai.include-player-names", true)) {
            return List.of();
        }
        return this.plugin.identity().completions(partial == null ? "" : partial.toLowerCase(Locale.ROOT));
    }

    public Set<String> knownCommands() {
        return Set.copyOf(this.knownCommands);
    }
}
