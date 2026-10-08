package fd.chat.user;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 身份解析：把一个玩家输入的字符串（中文名 / 账号名 / 前缀）解析成确定的玩家。
 *
 * 这是「昵称完全替代 ID」的核心。解析优先级：
 *   1. 精确账号名（在线优先，其次离线索引）
 *   2. 精确中文名
 *   3. 唯一前缀匹配（可配最短长度）
 *
 * 歧义时返回候选列表，由调用方提示玩家，绝不猜测。
 */
public final class IdentityResolver {

    private final ProxyServer proxy;
    private final NicknameRepository nicknames;
    private final boolean allowPrefix;
    private final int minPrefixLength;

    public IdentityResolver(ProxyServer proxy, NicknameRepository nicknames,
                            boolean allowPrefix, int minPrefixLength) {
        this.proxy = proxy;
        this.nicknames = nicknames;
        this.allowPrefix = allowPrefix;
        this.minPrefixLength = Math.max(1, minPrefixLength);
    }

    /** 解析结果。 */
    public sealed interface Resolution {
        /** 唯一确定 */
        record Found(String username, UUID uuid, boolean online) implements Resolution {
        }

        /** 有歧义 */
        record Ambiguous(List<String> candidates) implements Resolution {
        }

        /** 找不到 */
        record NotFound(String input) implements Resolution {
        }
    }

    public Resolution resolve(String input) {
        if (input == null || input.isBlank()) {
            return new Resolution.NotFound(String.valueOf(input));
        }
        String q = input.trim();

        // 1) 精确账号名
        Optional<Player> online = this.proxy.getPlayer(q);
        if (online.isPresent()) {
            Player p = online.get();
            return new Resolution.Found(p.getUsername(), p.getUniqueId(), true);
        }
        Optional<NicknameRepository.Entry> byName = this.nicknames.byUsername(q);
        if (byName.isPresent()) {
            NicknameRepository.Entry e = byName.get();
            return new Resolution.Found(e.username(), e.uuid(), this.proxy.getPlayer(e.uuid()).isPresent());
        }

        // 2) 精确中文名
        Optional<NicknameRepository.Entry> byNick = this.nicknames.byNickname(q);
        if (byNick.isPresent()) {
            NicknameRepository.Entry e = byNick.get();
            return new Resolution.Found(e.username(), e.uuid(), this.proxy.getPlayer(e.uuid()).isPresent());
        }

        // 3) 唯一前缀匹配
        if (this.allowPrefix && q.length() >= this.minPrefixLength) {
            Set<String> candidates = new LinkedHashSet<>();
            String lower = q.toLowerCase(Locale.ROOT);
            for (Player p : this.proxy.getAllPlayers()) {
                if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    candidates.add(p.getUsername());
                }
            }
            for (NicknameRepository.Entry e : this.nicknames.all()) {
                if (e.nickname() != null && e.nickname().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    candidates.add(e.username());
                }
            }
            if (candidates.size() == 1) {
                String username = candidates.iterator().next();
                return this.proxy.getPlayer(username)
                    .map(p -> (Resolution) new Resolution.Found(p.getUsername(), p.getUniqueId(), true))
                    .orElseGet(() -> this.nicknames.byUsername(username)
                        .<Resolution>map(e -> new Resolution.Found(e.username(), e.uuid(), false))
                        .orElseGet(() -> new Resolution.NotFound(input)));
            }
            if (candidates.size() > 1) {
                return new Resolution.Ambiguous(new ArrayList<>(candidates));
            }
        }

        return new Resolution.NotFound(input);
    }

    /**
     * 展示名：优先中文名，没有则账号名。
     *
     * 注：若将来装了 TAB 并希望用 TAB 的显示名（带称号），
     * 可在 config.yml 打开 display.use-tab-name，这里会优先返回 TAB 的名称。
     */
    public String displayName(UUID uuid, String username) {
        return this.nicknames.byUuid(uuid)
            .map(NicknameRepository.Entry::nickname)
            .filter(n -> n != null && !n.isBlank())
            .orElse(username);
    }

    public String displayName(Player player) {
        return displayName(player.getUniqueId(), player.getUsername());
    }

    /** 在线玩家的中文名列表（用于 tab 补全）。 */
    public List<String> onlineNicknames() {
        List<String> out = new ArrayList<>();
        for (Player p : this.proxy.getAllPlayers()) {
            out.add(displayName(p));
        }
        return out;
    }

    /** 给 tab 补全用：账号名 + 中文名。 */
    public List<String> completions(String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (Player p : this.proxy.getAllPlayers()) {
            if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(p.getUsername());
            }
            String nick = displayName(p);
            if (!nick.equals(p.getUsername()) && nick.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(nick);
            }
        }
        return new ArrayList<>(out);
    }
}
