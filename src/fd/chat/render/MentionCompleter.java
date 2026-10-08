package fd.chat.render;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 聊天框内的 @ 补全。
 *
 * 原理（Velocity 原生支持，无需协议层注入）：
 *   玩家在聊天框按 Tab 时，Velocity 会向子服请求补全，然后触发
 *   {@link TabCompleteEvent}，其 {@code getSuggestions()} 是【可变列表】。
 *   我们往里面塞在线玩家的中文名/账号名即可。
 *
 * 支持两种情形：
 *   1. "@" 作为第一个字符（如 {@code @蚊}）—— partialMessage 以 @ 开头
 *   2. 句中插入 mention（如 {@code 你好 @蚊}）—— 取最后一个 @ 之后的内容作为前缀
 *
 * 补全项跨子服：列出所有子服的在线玩家，且中文名优先。
 */
public final class MentionCompleter {

    private final MyLifeChatPlugin plugin;

    public MentionCompleter(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        if (!this.plugin.config().bool("mention.enabled", true)
            || !this.plugin.config().bool("mention.tab-complete", true)) {
            return;
        }
        if (this.plugin.identity() == null) {
            return;
        }

        String partial = event.getPartialMessage();
        if (partial == null || partial.isEmpty()) {
            return;
        }
        // 只处理以 @ 开头的聊天输入；指令（以 / 开头）交给各自的补全
        if (partial.startsWith("/")) {
            return;
        }

        String prefixToken = this.plugin.config().str("mention.prefix", "@");
        if (prefixToken.isEmpty()) {
            return;
        }

        // 取最后一个 @ 之后的内容作为前缀
        int at = partial.lastIndexOf(prefixToken);
        if (at < 0) {
            return;   // 没在 @ 某人，不干预
        }
        // 句中 mention 时，@ 前面必须是空格或行首，避免把邮箱 a@b 当提及
        if (at > 0 && !Character.isWhitespace(partial.charAt(at - 1))) {
            return;
        }

        String typed = partial.substring(at + prefixToken.length());
        // 已输入空格说明名字写完了，不再补全
        if (typed.indexOf(' ') >= 0) {
            return;
        }

        Player player = event.getPlayer();
        Set<String> candidates = new LinkedHashSet<>();

        boolean withUsernames = this.plugin.config().bool("mention.tab-complete-usernames", true);

        // 中文名优先；没设中文名的人用账号名兜底（否则自己不会出现在名单里）
        for (Player other : this.plugin.proxy().getAllPlayers()) {
            String nickname = this.plugin.nicknames().byUuid(other.getUniqueId())
                .map(fd.chat.user.NicknameRepository.Entry::nickname)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
            if (nickname != null) {
                if (matches(nickname, typed)) {
                    candidates.add(prefixToken + nickname);
                }
            } else if (withUsernames && matches(other.getUsername(), typed)) {
                candidates.add(prefixToken + other.getUsername());
            }
        }
        // 再补账号名（即使已设中文名，账号名也仍可用作提及目标）
        if (withUsernames) {
            for (Player other : this.plugin.proxy().getAllPlayers()) {
                if (matches(other.getUsername(), typed)) {
                    candidates.add(prefixToken + other.getUsername());
                }
            }
        }

        if (candidates.isEmpty()) {
            // 没有匹配也给出全量名单，方便玩家浏览
            if (this.plugin.config().bool("mention.tab-complete-list-all", false)) {
                for (Player other : this.plugin.proxy().getAllPlayers()) {
                    candidates.add(prefixToken + this.plugin.identity()
                        .displayName(other.getUniqueId(), other.getUsername()));
                }
            }
            if (candidates.isEmpty()) {
                return;
            }
        }

        int limit = this.plugin.config().i("mention.tab-complete-limit", 30);
        List<String> out = new ArrayList<>(candidates);
        if (out.size() > limit) {
            out = out.subList(0, limit);
        }

        // 清掉子服返回的无关建议（聊天框里子服往往返回空或玩家名），再塞入我们的
        List<String> suggestions = event.getSuggestions();
        suggestions.removeIf(s -> !s.startsWith(prefixToken));
        for (String s : out) {
            if (!suggestions.contains(s)) {
                suggestions.add(s);
            }
        }

        if (this.plugin.config().bool("settings.debug", false)) {
            this.plugin.logger().info("[TabComplete] {} input='{}' prefix='{}' hits={}",
                player.getUsername(), partial, typed, out.size());
        }
    }

    private static boolean matches(String name, String typed) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (typed == null || typed.isEmpty()) {
            return true; // 只打了个 @，列出全部
        }
        return name.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT));
    }
}
