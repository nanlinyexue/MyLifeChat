package fd.chat.render;

import fd.chat.channel.Channel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import io.github.miniplaceholders.api.MiniPlaceholders;

/**
 * MiniMessage 渲染器。
 *
 * 关闭了玩家可控的装饰性标签（只有管理员能在配置里使用全部标签），
 * 这样普通玩家的消息内容不会被渲染成彩色/可点击，避免被拿来做钓鱼。
 *
 * @提及实现方式：先把消息里的 @名字 用一个占位标签 &lt;fdm:hash&gt; 替换掉，
 * 再统一交给 MiniMessage 解析。这样：
 *   - 不需要手工做样式继承
 *   - 玩家消息里的其它 MiniMessage 标签不会被误解析（因为整条消息是作为纯文本传入的）
 */
public final class Renderer {

    private final MiniMessage mini;
    private final String mentionPrefix;
    private final String highlightOpen;
    private final String highlightClose;
    private final String mentionHover;
    private final String highlightColorName;
    /** 把「玩家输入的名字」解析成确定目标；由 MyLifeChatPlugin 注入。 */
    private java.util.function.Function<String, MentionTarget> resolver;

    public Renderer(String mentionPrefix, String highlightColor, String mentionHover) {
        // 只允许一部分安全标签用于「配置里的格式串」。
        // 注意：必须把 MiniPlaceholders 的解析器一并并入 builder，
        // 否则未注册的标签（<prefix>、<fd_server> 等）会被当作未知标签原样保留。
        TagResolver.Builder tags = TagResolver.builder()
                .resolver(StandardTags.color())
                .resolver(StandardTags.decorations())
                .resolver(StandardTags.reset())
                .resolver(StandardTags.gradient())
                .resolver(StandardTags.rainbow())
                .resolver(StandardTags.hoverEvent())
                .resolver(StandardTags.clickEvent())
                .resolver(StandardTags.keybind())
                .resolver(StandardTags.translatable())
                .resolver(StandardTags.font())
                .resolver(StandardTags.newline());

        // MiniPlaceholders（LuckPerms 前缀、fdtags 位置标签等）
        try {
            var mp = MiniPlaceholders.audienceGlobalPlaceholders();
            tags.resolver(mp);
            System.out.println("[fdchat-debug] MiniPlaceholders 解析器已并入: " + mp.getClass().getName());
        } catch (Throwable t) {
            System.out.println("[fdchat-debug] MiniPlaceholders 不可用: " + t);
        }

        this.mini = MiniMessage.builder().tags(tags.build()).build();

        this.mentionPrefix = mentionPrefix == null || mentionPrefix.isEmpty() ? "@" : mentionPrefix;
        this.highlightColorName = highlightColor == null ? "yellow" : highlightColor;
        this.highlightOpen = "<" + highlightColor + "><bold>";
        this.highlightClose = "</bold></" + highlightColor + ">";
        this.mentionHover = mentionHover == null ? "" : mentionHover;
    }

    public void resolver(java.util.function.Function<String, MentionTarget> resolver) {
        this.resolver = resolver;
    }

    public MiniMessage mini() {
        return this.mini;
    }

    /**
     * 构造「频道名 / 名字 / 时间 / 位置」这组上下文标签。
     *
     * 这些标签对应旧 Bungee 插件 chat_FD 的展示元素：
     *   channelname（§e§l[世界]）、gamemode、position、time、nickname、username、prefix、
     *   以及配套的 hover / click 事件。
     */
    public static TagResolver contextTags(MiniMessage mini,
                                          String channelName, String gamemode, String position,
                                          String time, String nickname, String username,
                                          Component prefix, Component message) {
        return TagResolver.builder()
            // 以下三项来自配置文件，允许写 MiniMessage 颜色（如 <yellow><bold>[世界]</bold></yellow>）
            .resolver(TagResolver.resolver("channelname", Tag.inserting(safeParse(mini, channelName))))
            .resolver(TagResolver.resolver("gamemode", Tag.inserting(safeParse(mini, gamemode))))
            .resolver(TagResolver.resolver("position", Tag.inserting(safeParse(mini, position))))
            // 以下为玩家数据/运行时数据，一律纯文本，防止注入
            .resolver(TagResolver.resolver("time", Tag.inserting(Component.text(time))))
            .resolver(TagResolver.resolver("nickname", Tag.inserting(Component.text(nickname))))
            .resolver(TagResolver.resolver("username", Tag.inserting(Component.text(username))))
            .resolver(TagResolver.resolver("prefix", Tag.inserting(prefix)))
            .resolver(TagResolver.resolver("message", Tag.inserting(message)))
            .build();
    }

    /** 解析配置里的展示串；失败则退化为纯文本，绝不抛异常打断聊天。 */
    private static Component safeParse(MiniMessage mini, String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return mini.deserialize(text);
        } catch (Exception ex) {
            return Component.text(text);
        }
    }

    /** 解析配置里的格式串（信任来源，无 audience 上下文）。 */
    public Component parse(String miniMessageText, TagResolver... resolvers) {
        return this.mini.deserialize(miniMessageText, resolvers);
    }

    /**
     * 带 audience 上下文的解析。
     *
     * 关键：MiniPlaceholders 的 audience 占位符（<prefix>、<fd_server> 等）
     * 只有在 MiniMessage 拿到 Pointered(audience) 时才会被解析。
     * 不传 audience 的话这些标签会原样留在输出里。
     */
    public Component parseFor(net.kyori.adventure.audience.Audience audience,
                              String miniMessageText, TagResolver... resolvers) {
        if (audience instanceof net.kyori.adventure.pointer.Pointered pointered) {
            return this.mini.deserialize(miniMessageText, pointered, resolvers);
        }
        return this.mini.deserialize(miniMessageText, resolvers);
    }

    /** 纯文本转组件（不解析任何标签）——玩家消息内容专用。 */
    public Component plain(String text) {
        return Component.text(text == null ? "" : text);
    }

    /**
     * 在消息正文里找出并高亮 @提及。
     *
     * @param rawContent 玩家输入的正文（纯文本）
     * @param knownNames 可供匹配的名字集合（中文名 + 账号名）
     * @return 处理后的 MiniMessage 串，以及本条消息实际提及到的名字
     */
    /**
     * @提及的目标信息。
     *
     * @param display  显示名（优先中文名，无中文名则回退账号名）
     * @param username 账号名（悬停时展示）
     * @param nickname 中文名，可能为 null
     */
    public record MentionTarget(String display, String username, String nickname) {
    }

    public MentionResult markMentions(String rawContent, Set<String> knownNames, int maxMentions) {
        return this.markMentions(rawContent, knownNames, maxMentions, "@");
    }

    /**
     * 找出消息里的 @提及。
     *
     * @param rawContent 玩家原始输入
     * @param knownNames 可被提及的名字集合（中文名 + 账号名）
     * @param maxMentions 单条最多提及数
     * @param prefix     提及前缀，默认 "@"
     */
    public MentionResult markMentions(String rawContent, Set<String> knownNames, int maxMentions,
                                      String prefix) {
        if (rawContent == null || rawContent.isEmpty() || knownNames.isEmpty() || maxMentions <= 0) {
            return new MentionResult(List.of(), List.of());
        }

        List<String> sorted = new ArrayList<>(knownNames);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));

        List<Segment> segments = new ArrayList<>();
        List<String> mentioned = new ArrayList<>();
        java.util.Set<String> seenTargets = new java.util.HashSet<>();
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        int n = rawContent.length();

        while (i < n) {
            boolean matched = false;
            if (rawContent.startsWith(prefix, i)) {
                int after = i + prefix.length();
                for (String name : sorted) {
                    if (!rawContent.regionMatches(true, after, name, 0, name.length())) {
                        continue;
                    }
                    int endIdx = after + name.length();
                    // 名字后必须是边界，避免 @张三丰 误匹配 @张三
                    if (endIdx < n && isWordChar(rawContent.charAt(endIdx))) {
                        continue;
                    }
                    MentionTarget target = this.resolver == null
                        ? new MentionTarget(name, name, null)
                        : this.resolver.apply(name);
                    String dedupe = target.username().toLowerCase(java.util.Locale.ROOT);
                    if (seenTargets.contains(dedupe) || mentioned.size() >= maxMentions) {
                        i = endIdx;
                        matched = true;
                        break;
                    }
                    seenTargets.add(dedupe);
                    mentioned.add(target.display());

                    if (buffer.length() > 0) {
                        segments.add(new Segment(buffer.toString(), null));
                        buffer.setLength(0);
                    }
                    segments.add(new Segment(null, target));
                    i = endIdx;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                buffer.append(rawContent.charAt(i));
                i++;
            }
        }
        if (buffer.length() > 0) {
            segments.add(new Segment(buffer.toString(), null));
        }

        return new MentionResult(segments, mentioned);
    }

    /** 消息片段：要么是纯文本，要么是一个提及目标。 */
    public record Segment(String text, MentionTarget mention) {
    }

    /**
     * 在已解析的组件里插入 @提及 节点。
     *
     * @param parsed       已经解析好的消息正文（其中占位符以字面量 &lt;fdmN&gt; 存在）
     * @param tokens       token -&gt; 目标
     * @param format       形如 {@code "%msg%"} 的模板，%msg% 会被替换成处理后的正文
     *
     * 之所以按「占位符切分再逐段解析」而不是用 replaceText：
     * 实测 replaceText 在多 token 场景下只会替换第一个，行为不可靠。
     */
    /**
     * 把格式串里的 %msg% 替换成「正文 + 提及节点」。
     *
     * 做法：把正文按提及切成若干段，每段各自解析成组件，再拼起来放进 %msg% 位置。
     * 不使用字符串哨兵 / replaceText —— 实测 MiniMessage 会剥掉 NUL 与私有区字符，
     * 而 replaceText 在多 token 场景只会替换第一个，两者都不可靠。
     */
    public Component applyMentionsInFormat(List<Segment> segments, String format,
                                           TagResolver contextResolvers) {
        Component body = Component.empty();
        for (Segment seg : segments) {
            if (seg.mention() != null) {
                MentionTarget t = seg.mention();
                String hoverText = this.mentionHover
                    .replace("%name%", t.display())
                    .replace("%username%", t.username());
                body = body.append(this.buildMentionNode(t.display(), hoverText));
            } else {
                // 每段单独解析，保证玩家自己的颜色代码只在段内生效、不跨提及渗透
                body = body.append(this.mini.deserialize(seg.text()));
            }
        }
        // 把正文组件放进 <message> 的位置，其余标签用 contextResolvers 解析
        String bodyHolder = "\uE000MSG\uE001";
        String template = format.contains("<message>")
            ? format.replace("<message>", bodyHolder)
            : format + bodyHolder;
        Component out = this.mini.deserialize(template, contextResolvers);
        return this.substituteBody(out, bodyHolder, body);
    }

    /**
     * 把组件树里出现占位文本的节点替换成正文组件。
     *
     * 之所以最后做这一步：正文里含 @提及 的独立节点，不能先塞进字符串再解析，
     * 否则提及节点的样式/事件会被解析过程破坏。
     */
    private Component substituteBody(Component component, String holder, Component body) {
        java.util.List<Component> newChildren = new java.util.ArrayList<>();
        for (Component child : component.children()) {
            newChildren.add(substituteBody(child, holder, body));
        }
        if (!(component instanceof net.kyori.adventure.text.TextComponent tc)
            || !tc.content().contains(holder)) {
            return component.children(newChildren);
        }
        String content = tc.content();
        java.util.List<Component> parts = new java.util.ArrayList<>();
        int idx;
        String rest = content;
        while ((idx = rest.indexOf(holder)) >= 0) {
            if (idx > 0) {
                parts.add(Component.text(rest.substring(0, idx)).style(component.style()));
            }
            parts.add(body);
            rest = rest.substring(idx + holder.length());
        }
        if (!rest.isEmpty()) {
            parts.add(Component.text(rest).style(component.style()));
        }
        parts.addAll(newChildren);
        Component result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = result.append(parts.get(i));
        }
        return result;
    }

    /** 支持命名色与 #RRGGBB。 */
    private static net.kyori.adventure.text.format.TextColor resolveColor(String name) {
        if (name == null || name.isBlank()) {
            return net.kyori.adventure.text.format.NamedTextColor.AQUA;
        }
        String value = name.trim();
        if (value.startsWith("#")) {
            try {
                return net.kyori.adventure.text.format.TextColor.fromHexString(value);
            } catch (Exception ignored) {
                return net.kyori.adventure.text.format.NamedTextColor.AQUA;
            }
        }
        net.kyori.adventure.text.format.NamedTextColor named = net.kyori.adventure.text.format.NamedTextColor.NAMES
            .value(value.toLowerCase(java.util.Locale.ROOT));
        return named == null ? net.kyori.adventure.text.format.NamedTextColor.AQUA : named;
    }

    private Component buildMentionNode(String displayName, String hoverText) {
        // 按要求：显示中文名（无则账号名），&b（aqua）且不加粗
        Component node = Component.text(this.mentionPrefix + displayName)
            .color(resolveColor(this.highlightColorName))
            .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD,
                net.kyori.adventure.text.format.TextDecoration.State.FALSE)
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/t " + displayName + " "));
        if (hoverText != null && !hoverText.isEmpty()) {
            try {
                Component hover = this.mini.deserialize(hoverText);
                node = node.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover));
            } catch (Exception ignored) {
                // 悬停文案配置有误时不影响正文
            }
        }
        return node;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 转义 MiniMessage 的特殊字符，防止玩家注入标签。 */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** 把 %key% 形式的占位符替换掉（用于私聊等简单模板）。 */
    public static String applyVars(String template, java.util.Map<String, String> vars) {
        String out = template;
        for (java.util.Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", e.getValue());
        }
        return out;
    }

    public record MentionResult(List<Segment> segments, List<String> mentioned) {
    }

    /** 匹配「看起来像 @某人」的片段，用于在不知道名单时的兜底识别。 */
    private static final Pattern LOOSE_MENTION = Pattern.compile("@([\\p{IsHan}A-Za-z0-9_]{2,16})");

    public Set<String> looseMentionNames(String content) {
        Set<String> out = new LinkedHashSet<>();
        if (content == null) {
            return out;
        }
        Matcher m = LOOSE_MENTION.matcher(content.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
