package fd.chat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import fd.chat.api.FdChatApi;
import fd.chat.channel.Channel;
import fd.chat.channel.ChannelManager;
import fd.chat.moderation.AdFilter;
import fd.chat.moderation.SpamFilter;
import fd.chat.user.NicknameRepository;
import fd.chat.user.UserManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.github.miniplaceholders.api.MiniPlaceholders;

/**
 * 聊天分发器：频道切分 → 校验（禁言/广告/刷屏/收费）→ 渲染 → 投递。
 *
 * 投递完全在代理层完成，不经过任何后端子服，因此：
 *   - 不需要 Redis
 *   - 任何子服的玩家都能看到全局消息
 *   - 后端不需要装任何聊天插件
 */
public final class ChatDispatcher {

    private final MyLifeChatPlugin plugin;
    /** 每个玩家的频道切换冷却计时 */
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    ChatDispatcher(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 只做「是否该拦截」的判定，不投递任何消息。
     *
     * 给已签名会话使用：Velocity 不允许我们取消或改写签名消息（会踢人），
     * 所以代理侧只能判定出违规，再决定是否改写成空内容。
     *
     * @return null 表示放行；否则返回违规原因（写进警告文案）
     */
    String precheck(Player sender, String rawMessage) {
        UserManager users = this.plugin.users();
        UUID uuid = sender.getUniqueId();

        // AI 提问前缀不算违规
        String aiPrefix = this.plugin.config().str("ai.question-prefix", "??");
        if (!aiPrefix.isEmpty() && rawMessage.startsWith(aiPrefix)) {
            return null;
        }
        // 以 / 开头的是指令，不在此判定
        if (rawMessage.startsWith("/")) {
            return null;
        }

        ChannelManager.Parsed parsed = this.plugin.channels().parse(rawMessage, users.channel(uuid));
        Channel channel = parsed.channel();
        String content = parsed.content();
        if (content.isEmpty()) {
            return null;
        }

        if (users.isMuted(uuid)) {
            long remain = users.mutedRemainingSeconds(uuid);
            return "你已被禁言" + (remain > 0 ? "（剩余 " + formatDuration(remain) + "）" : "");
        }

        boolean bypass = sender.hasPermission(
            this.plugin.config().str("moderation.bypass-permission", "mylife.moderation.bypass"));

        // 反广告
        if (!bypass && this.plugin.config().bool("moderation.anti-advertisement", true)) {
            AdFilter.Verdict verdict = this.plugin.adFilter().check(content);
            if (verdict.hit() && this.plugin.adFilter().action() == AdFilter.Action.BLOCK) {
                notifyStaff(sender, verdict.ruleName(), content);
                audit(sender, "广告/" + verdict.ruleName(), content);
                return "广告内容（" + verdict.ruleName() + "）";
            }
        }

        // 反刷屏
        if (!bypass && this.plugin.config().bool("moderation.anti-spam", true)) {
            SpamFilter.Verdict verdict = this.plugin.spamFilter().check(uuid, content);
            if (verdict.blocked()) {
                audit(sender, "刷屏/" + verdict.reason(), content);
                return "刷屏（" + verdict.reason() + "）";
            }
        }

        // 冷却
        if (channel.cooldownMs() > 0 && onCooldown(uuid, channel)) {
            long remainMs = remainingCooldown(uuid, channel);
            return "发言过快（剩余 " + String.format("%.1f", remainMs / 1000.0) + " 秒）";
        }

        return null;
    }

    void dispatch(Player sender, String rawMessage) {
        UserManager users = this.plugin.users();
        UUID uuid = sender.getUniqueId();

        // ---- AI 提问入口：以 ?? 开头不当作聊天，转给已注册的联想/AI 来源 ----
        String aiPrefix = this.plugin.config().str("ai.question-prefix", "??");
        if (!aiPrefix.isEmpty() && rawMessage.startsWith(aiPrefix)) {
            handleAiQuestion(sender, rawMessage.substring(aiPrefix.length()).trim());
            return;
        }

        ChannelManager.Parsed parsed = this.plugin.channels()
            .parse(rawMessage, users.channel(uuid));
        Channel channel = parsed.channel();

        if (this.plugin.config().bool("settings.debug", false)) {
            this.plugin.logger().info("[Debug] {} raw='{}' prefix='{}' channel={} toll={} pref={}",
                sender.getUsername(), rawMessage, parsed.usedPrefix(), channel.key(),
                channel.tolled() ? channel.toll() : 0, users.channel(uuid));
        }

        // 频道未开放
        if (!channel.enabled()) {
            String note = channel.disabledNote();
            sender.sendMessage(this.plugin.renderer().parse(
                note == null || note.isBlank() ? "<red>该频道尚未开放。" : note));
            return;
        }

        String content = parsed.content();
        if (content.isEmpty()) {
            return;
        }

        // ---- 禁言 ----
        if (users.isMuted(uuid)) {
            long remain = users.mutedRemainingSeconds(uuid);
            String reason = users.muteReason(uuid);
            String msg = remain <= 0
                ? "<red>你已被禁言。"
                : "<red>你已被禁言，剩余 <yellow>" + formatDuration(remain) + "</yellow>。"
                  + (reason == null || reason.isBlank() ? "" : " <gray>原因：" + reason);
            sender.sendMessage(this.plugin.renderer().parse(msg));
            return;
        }

        // ---- 未设置中文名：不强制、不拦截，仅在本局首次发言时提示一次 ----
        if (!hasNickname(uuid) && !this.plugin.nicknamePrompts().contains(uuid)) {
            boolean require = this.plugin.config().bool("nickname.require-for-chat", false);
            if (require) {
                // 仍然以「提示一次」的方式处理，避免玩家被完全挡住
                sender.sendMessage(this.plugin.renderer().parse(this.plugin.config()
                    .str("nickname.required-message",
                        "<yellow>还没有中文名，聊天里会显示你的账号名。\n"
                        + "输入 <white>/中文名 <你的名字></white> 设置一个吧（首次免费）。")));
            } else {
                sender.sendMessage(this.plugin.renderer().parse(this.plugin.config()
                    .str("nickname.prompt-message",
                        "<gray>提示：你还没有设置中文名，聊天里会显示账号名。\n"
                        + "<gray>输入 <yellow>/中文名 <名字></yellow> 即可设置（首次免费）。")));
            }
            this.plugin.nicknamePrompts().mark(uuid, sender.getUsername());
        }

        // ---- 冷却 ----
        if (channel.cooldownMs() > 0 && onCooldown(uuid, channel)) {
            long remainMs = remainingCooldown(uuid, channel);
            sender.sendMessage(this.plugin.renderer().parse(
                "<red>发言太快了，请等待 <yellow>" + String.format("%.1f", remainMs / 1000.0) + "s</red>。"));
            return;
        }

        boolean bypass = sender.hasPermission(
            this.plugin.config().str("moderation.bypass-permission", "mylife.moderation.bypass"));

        // ---- 反广告 ----
        if (!bypass && this.plugin.config().bool("moderation.anti-advertisement", true)) {
            AdFilter.Verdict verdict = this.plugin.adFilter().check(content);
            if (verdict.hit()) {
                AdFilter.Action action = this.plugin.adFilter().action();
                notifyStaff(sender, verdict.ruleName(), content);
                if (action == AdFilter.Action.BLOCK) {
                    sender.sendMessage(this.plugin.renderer().parse(this.plugin.adFilter().blockMessage()));
                    audit(sender, "广告/" + verdict.ruleName(), content);
                    return;
                }
                if (action == AdFilter.Action.REPLACE) {
                    content = verdict.cleaned();
                }
            }
        }

        // ---- 反刷屏 ----
        if (!bypass && this.plugin.config().bool("moderation.anti-spam", true)) {
            SpamFilter.Verdict verdict = this.plugin.spamFilter().check(uuid, content);
            if (verdict.blocked()) {
                this.plugin.spamFilter();
                String msg = this.plugin.spamFilter().strikes(uuid) >= this.plugin.spamFilter().warnThreshold()
                    ? this.plugin.spamFilter().warnMessage()
                    : this.plugin.spamFilter().blockMessage();
                sender.sendMessage(this.plugin.renderer().parse(msg));
                audit(sender, "刷屏/" + verdict.reason(), content);
                if (verdict.shouldMute()) {
                    users.mute(uuid, sender.getUsername(),
                        this.plugin.spamFilter().muteSeconds(), "刷屏");
                    sender.sendMessage(this.plugin.renderer().parse(
                        "<red>你因刷屏被禁言 <yellow>" + this.plugin.spamFilter().muteSeconds() + "</yellow> 秒。"));
                }
                return;
            }
        }

        // ---- 自定义拦截器（AI 插件挂载点）----
        String replaced = runInterceptors(sender, content, channel);
        if (replaced == null) {
            return; // 已被拦截，提示由拦截器自行发送
        }
        content = replaced;

        // ---- 收费 ----
        double toll = channel.tolled() ? channel.toll() : 0;
        if (toll > 0 && !bypass) {
            if (!this.plugin.economy().enabled()) {
                sender.sendMessage(this.plugin.renderer().parse("<red>经济系统未启用，无法在此频道发言。"));
                return;
            }
            double balance = this.plugin.economy().balance(uuid);
            if (balance < toll) {
                sender.sendMessage(this.plugin.renderer().parse(
                    "<red>余额不足：本频道每条 <yellow>" + this.plugin.economy().format(toll)
                    + "</yellow>，你还差 <yellow>"
                    + this.plugin.economy().format(toll - balance) + "</yellow>。"));
                return;
            }
            if (!this.plugin.economy().withdraw(uuid, toll)) {
                sender.sendMessage(this.plugin.renderer().parse("<red>扣费失败，请稍后重试。"));
                return;
            }
        }

        String senderServerName = sender.getCurrentServer()
            .map(c -> c.getServerInfo().getName()).orElse("");

        // ---- 收集收件人 ----
        List<Audience> recipients = collectRecipients(sender, channel);
        boolean onlyConsole = recipients.stream().noneMatch(Player.class::isInstance);

        if (toll > 0 && onlyConsole && channel.key().equals(Channel.LOCAL)) {
            // 本地频道没人听到 → 退款
            this.plugin.economy().deposit(uuid, toll);
            if (this.plugin.config().bool("channels.local.notify-empty", true)) {
                sender.sendMessage(this.plugin.renderer().parse("<gray>附近没有其他玩家。"));
            }
            return;
        }

        // ---- 渲染与投递 ----
        Set<String> knownNames = collectKnownNames();
        var mention = this.plugin.renderer().markMentions(content, knownNames,
            this.plugin.config().i("mention.max-per-message", 5));
        if (this.plugin.config().bool("settings.debug", false)) {
            this.plugin.logger().info("[Debug] mention: hit={} tokens={}",
                mention.mentioned(), mention.segments().size());
        }
        // 正文只保留「玩家自己敲的原始文本」用于长度/审计；渲染时按片段拼装
        String safeContent = content;

        String format = this.plugin.config().str("format.chat",
            "<channel_tag><nickname><dark_gray>:</dark_gray> <message>");
        String nickname = this.plugin.identity().displayName(uuid, sender.getUsername());
        // 付费喊话时用更醒目的标签，便于玩家识别与屏蔽
        boolean paid = toll > 0;
        String channelTag = channel.displayTag(paid);

        if (toll > 0) {
            sender.sendMessage(this.plugin.renderer().parse(
                "<gray>已扣除 <yellow>" + this.plugin.economy().format(toll) + "</yellow>，余额 <yellow>"
                + this.plugin.economy().format(this.plugin.economy().balance(uuid)) + "</yellow>。"));
        }

        for (Audience audience : recipients) {
            Component rendered = renderFor(audience, format, channel, channelTag,
                nickname, sender.getUsername(), mention.segments(), senderServerName,
                sender);
            audience.sendMessage(rendered);
        }

        // 控制台
        String consoleFormat = this.plugin.config().str("format.console", "[<channel>] <username>: <message>");
        this.plugin.proxy().getConsoleCommandSource().sendMessage(
            this.plugin.renderer().parse(consoleFormat,
                TagResolver.resolver("channel", Tag.inserting(Component.text(channel.key()))),
                TagResolver.resolver("username", Tag.inserting(Component.text(sender.getUsername()))),
                TagResolver.resolver("message", Tag.inserting(Component.text(content)))));

        // ---- @提及提示音 ----
        playMentionSounds(sender, mention.mentioned(), recipients);

        setCooldown(uuid, channel);
    }

    // ------------------------------------------------------------------

    /**
     * AI 提问：玩家输入 ?? 开头的内容时触发。
     *
     * fdchat 本身不做 AI，只负责：
     *   1. 向所有已注册的 Provider 征集回答（AI 插件实现 FdChatApi.Provider）
     *   2. 没有任何 Provider 时，给出本地兜底提示
     *
     * AI 插件只要调用 FdChatApiRegistry.get().suggestions().register(...) 就能接管这里。
     */
    private void handleAiQuestion(Player sender, String question) {
        if (question.isEmpty()) {
            sender.sendMessage(this.plugin.renderer().parse(
                "<gray>用法：<yellow>??<gray> 你的问题，例如 <yellow>??怎么去主城</yellow>"));
            return;
        }

        List<String> answers = this.plugin.collectSuggestions(sender, question, 5);
        if (!answers.isEmpty()) {
            sender.sendMessage(this.plugin.renderer().parse("<gray>— <yellow>可能的答案</yellow> —"));
            for (String answer : answers) {
                sender.sendMessage(this.plugin.renderer().parse("<gray>· <white>" + answer));
            }
            return;
        }

        sender.sendMessage(this.plugin.renderer().parse(
            "<gray>暂无可用的 AI 服务。\n"
            + "<gray>· 输入 <yellow>/help</yellow> 查看服务器指令\n"
            + "<gray>· 如果是指令记错了，直接输入你想用的指令，我们会给出相近建议"));
        this.plugin.logger().debug("Player {} asked AI but no provider: {}", sender.getUsername(), question);
    }

    /**
     * 渲染消息正文。
     *
     * 颜色与字体分两档权限，便于单独售卖：
     *   mylife.chat.color   → &0-&f / &r
     *   mylife.chat.format  → &l &o &n &m &k
     * 无权限时对应代码原样显示，且绝不解析 MiniMessage，防止注入 hover/click。
     */
    /**
     * 给每个文本片段套用「颜色 / 字体」权限，再转成 MiniMessage 串。
     * 提及片段原样保留（它们是独立节点，不该被玩家颜色代码影响）。
     */
    private java.util.List<fd.chat.render.Renderer.Segment> decorateSegments(
        Player sender, java.util.List<fd.chat.render.Renderer.Segment> segments) {
        java.util.List<fd.chat.render.Renderer.Segment> out = new java.util.ArrayList<>();
        for (fd.chat.render.Renderer.Segment seg : segments) {
            if (seg.mention() != null) {
                out.add(seg);
                continue;
            }
            out.add(new fd.chat.render.Renderer.Segment(
                this.renderMessageBody(sender, seg.text()), null));
        }
        return out;
    }

    private String renderMessageBody(Player sender, String content) {
        boolean color = sender.hasPermission(
            this.plugin.config().str("chat.permission.color", "mylife.chat.color"))
            || sender.hasPermission(
            this.plugin.config().str("chat.permission.exempt", "mylife.chat.exempt"));
        boolean format = sender.hasPermission(
            this.plugin.config().str("chat.permission.format", "mylife.chat.format"))
            || sender.hasPermission(
            this.plugin.config().str("chat.permission.exempt", "mylife.chat.exempt"));

        Component body = fd.chat.render.LegacyColors.render(content, color, format,
            net.kyori.adventure.text.format.NamedTextColor.WHITE);
        // 转回 MiniMessage 串，交由后续统一解析（提及哨兵也在其中）
        return this.plugin.renderer().mini().serialize(body);
    }

    private Component renderFor(Audience audience, String format, Channel channel, String channelTag,
                                String nickname, String username,
                                java.util.List<fd.chat.render.Renderer.Segment> segments,
                                String serverName, Player sender) {
        // LuckPerms 前缀/后缀（未装则为空，与旧 chat_FD 的 prefix 字段等价）
        // 前缀来自 LuckPerms，是 MiniMessage 串（可含渐变/颜色），必须解析后再插入；
        // 之前当纯文本插入，导致 <gradient:...> 原样显示。
        Component prefixComponent = Component.empty();
        if (audience instanceof Player recipient && this.plugin.luckPerms() != null) {
            prefixComponent = this.plugin.luckPerms().prefix(recipient);
        }

        // 旧 chat_FD 的展示要素：频道名、游戏模式/位置（每服写死）、时间
        String channelName = channel.displayName() == null ? "" : channel.displayName();
        String gamemode = serverLocation(serverName, "gamemode");
        String position = serverLocation(serverName, "position");
        String time = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        if (this.plugin.config().bool("settings.debug", false)) {
            this.plugin.logger().info("[FormatDebug] server='{}' channel='{}' gamemode='{}' position='{}'",
                serverName, channelName, gamemode, position);
        }

        TagResolver contextResolvers = TagResolver.builder()
            .resolver(TagResolver.resolver("channel_tag",
                Tag.inserting(this.plugin.renderer().parse(channelTag))))
            .resolver(TagResolver.resolver("channel", Tag.inserting(Component.text(channel.key()))))
            .resolver(fd.chat.render.Renderer.contextTags(
                this.plugin.renderer().mini(),
                channelName, gamemode, position, time, nickname, username, prefixComponent,
                Component.empty()))
            .build();

        return this.plugin.renderer().applyMentionsInFormat(
            this.decorateSegments(sender, segments), format, contextResolvers);
    }

    private List<Audience> collectRecipients(Player sender, Channel channel) {
        List<Audience> out = new ArrayList<>();
        String senderServer = sender.getCurrentServer()
            .map(c -> c.getServerInfo().getName()).orElse("");

        for (Player player : this.plugin.proxy().getAllPlayers()) {
            if (channel.serverScoped()) {
                String server = player.getCurrentServer()
                    .map(c -> c.getServerInfo().getName()).orElse("");
                if (!server.equals(senderServer)) {
                    continue;
                }
            }
            // 忽略关系：仅对私聊与本服生效，全局频道不屏蔽（避免刷屏者利用忽略逃逸）
            if (!channel.key().equals(Channel.GLOBAL)
                && this.plugin.users().ignoring(player.getUniqueId(), sender.getUniqueId())) {
                continue;
            }
            out.add(player);
        }
        return out;
    }

    /** 运行所有已注册的拦截器；返回 null 表示消息被拦截。 */
    private String runInterceptors(Player sender, String content, Channel channel) {
        String current = content;
        String server = sender.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        for (ApiImpl.InterceptorEntry entry : this.plugin.sortedInterceptors()) {
            try {
                FdChatApi.Context ctx = new SimpleContext(sender, current, channel.key(), server);
                FdChatApi.Verdict verdict = entry.interceptor().intercept(ctx);
                if (verdict == null) {
                    continue;
                }
                if (verdict.block()) {
                    if (verdict.blockReason() != null && !verdict.blockReason().isBlank()) {
                        sender.sendMessage(this.plugin.renderer().parse(verdict.blockReason()));
                    }
                    return null;
                }
                if (verdict.replacement() != null) {
                    current = verdict.replacement();
                }
            } catch (Exception ex) {
                this.plugin.logger().warn("Chat interceptor failed, skipped.", ex);
            }
        }
        return current;
    }

    private record SimpleContext(Player player, String content, String channelKey, String serverName)
        implements FdChatApi.Context {

        @Override
        public CommandSource sender() {
            return this.player;
        }

        @Override
        public String senderUsername() {
            return this.player.getUsername();
        }
    }

    /**
     * 可用于 @提及 的名字集合。
     *
     * 包含全部账号名与全部已注册中文名（不只是在线玩家）——
     * 这样某人在线与否不影响提及的高亮与可点击性，显示更一致。
     */
    private Set<String> collectKnownNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Player p : this.plugin.proxy().getAllPlayers()) {
            names.add(p.getUsername());
        }
        for (NicknameRepository.Entry entry : this.plugin.nicknames().all()) {
            if (entry.nickname() != null && !entry.nickname().isBlank()) {
                names.add(entry.nickname());
            }
            if (entry.username() != null && !entry.username().isBlank()) {
                names.add(entry.username());
            }
        }
        return names;
    }

    /**
     * 被 @ 的人：播提示音 + 可选文字提醒。
     *
     * 默认音效是「升级」（minecraft:entity.player.levelup），按要求配好。
     * 需要 fdchat.mention.notify 权限才会收到（便于做成售卖项），
     * 玩家也可用 /提及 自行关闭。
     */
    private void playMentionSounds(Player sender, List<String> mentioned, List<Audience> recipients) {
        if (!this.plugin.config().bool("mention.enabled", true) || mentioned.isEmpty()) {
            return;
        }

        String soundPerm = this.plugin.config().str("mention.notify-permission", "mylife.mention.notify");
        boolean playSound = this.plugin.config().bool("mention.play-sound", true);
        boolean notifyText = this.plugin.config().bool("mention.notify-text", true);
        String notifyFormat = this.plugin.config().str("mention.notify-format",
            "<aqua>有人在聊天里 @ 了你</aqua> <gray>（<white>%channel%</white> · <white>%server%</white>）</gray>");

        Sound sound = null;
        if (this.plugin.config().bool("settings.debug", false)) {
            this.plugin.logger().info("[Sound] start: playSound={} notifyText={} mentions={} recipients={}",
                playSound, notifyText, mentioned, recipients.size());
        }
        if (playSound) {
            try {
                sound = Sound.sound(
                    Key.key(this.plugin.config().str("mention.sound", "minecraft:entity.player.levelup")),
                    Sound.Source.valueOf(this.plugin.config()
                        .str("mention.sound-source", "master").toUpperCase()),
                    (float) this.plugin.config().d("mention.volume", 0.7),
                    (float) this.plugin.config().d("mention.pitch", 1.0)
                );
            } catch (Exception ex) {
                sound = null;
            }
        }

        for (Audience audience : recipients) {
            if (!(audience instanceof Player target)) {
                continue;
            }
            String display = this.plugin.identity().displayName(target.getUniqueId(), target.getUsername());
            if (!mentioned.contains(display) && !mentioned.contains(target.getUsername())) {
                continue;
            }
            if (!this.plugin.config().bool("mention.notify-self", false)
                && target.getUniqueId().equals(sender.getUniqueId())) {
                continue;
            }
            if (!this.plugin.users().mentionSound(target.getUniqueId())) {
                continue; // 玩家自己关掉了
            }
            // 权限留空 = 不检查（LuckPerms 未安装时也能正常提醒）
            if (!soundPerm.isBlank() && !target.hasPermission(soundPerm)) {
                if (this.plugin.config().bool("settings.debug", false)) {
                    this.plugin.logger().info("[Sound] {} lacks permission {}, skipped", target.getUsername(), soundPerm);
                }
                continue;
            }
            if (sound != null) {
                // 关键：不能直接用 Velocity 的 playSound —— 它对 1.19.3 以下客户端会静默跳过。
                // 改由后端（Paper）用 Player.playSound 播放，那里没有版本限制。
                if (this.plugin.backendBridge() != null) {
                    this.plugin.backendBridge().playSound(target, sound.name().asString(),
                        sound.volume(), sound.pitch());
                } else {
                    target.playSound(sound);
                }
                if (this.plugin.config().bool("settings.debug", false)) {
                    this.plugin.logger().info("[Sound] requested backend to play {} for {} (vol={} pitch={})",
                        target.getUsername(), sound.name().asString(),
                        sound.volume(), sound.pitch());
                }
            } else if (this.plugin.config().bool("settings.debug", false)) {
                this.plugin.logger().warn("[Sound] sound is null, skipped (check mention.sound)");
            }
            if (notifyText) {
                String server = target.getCurrentServer()
                    .map(c -> c.getServerInfo().getName()).orElse("");
                target.sendMessage(this.plugin.renderer().parse(notifyFormat
                    .replace("%player%", fd.chat.render.Renderer.escape(sender.getUsername()))
                    .replace("%channel%", "")
                    .replace("%server%", server)));
            }
        }
    }

    private void notifyStaff(Player sender, String rule, String content) {
        if (!this.plugin.config().bool("moderation.log-blocked", true)) {
            return;
        }
        // 用字符串替换而不是 TagResolver —— 通知模板是普通 %key% 形式
        String format = this.plugin.adFilter().notifyFormat();
        String rendered = format
            .replace("%player%", fd.chat.render.Renderer.escape(sender.getUsername()))
            .replace("%rule%", fd.chat.render.Renderer.escape(rule))
            .replace("%message%", fd.chat.render.Renderer.escape(content));
        for (Player player : this.plugin.proxy().getAllPlayers()) {
            if (player.hasPermission(
                this.plugin.config().str("moderation.notify-permission", "mylife.moderation.notify"))) {
                player.sendMessage(this.plugin.renderer().parse(rendered));
            }
        }
    }

    private void audit(Player sender, String category, String content) {
        if (this.plugin.config().bool("moderation.log-blocked", true)) {
            this.plugin.logger().info("[Moderation] {} triggered {}: {}", sender.getUsername(), category, content);
        }
    }

    private boolean hasNickname(UUID uuid) {
        return this.plugin.nicknames().byUuid(uuid)
            .map(NicknameRepository.Entry::nickname)
            .filter(n -> n != null && !n.isBlank())
            .isPresent();
    }

    // ---------------- 冷却 ----------------

    private boolean onCooldown(UUID uuid, Channel channel) {
        return remainingCooldown(uuid, channel) > 0;
    }

    private long remainingCooldown(UUID uuid, Channel channel) {
        Map<String, Long> map = this.cooldowns.get(uuid);
        if (map == null) {
            return 0;
        }
        Long until = map.get(channel.key());
        if (until == null) {
            return 0;
        }
        long remain = until - System.currentTimeMillis();
        return Math.max(remain, 0);
    }

    private void setCooldown(UUID uuid, Channel channel) {
        if (channel.cooldownMs() <= 0) {
            return;
        }
        this.cooldowns.computeIfAbsent(uuid, k -> new HashMap<>())
            .put(channel.key(), System.currentTimeMillis() + channel.cooldownMs());
    }

    /**
     * 读取某子服的「游戏模式 / 位置」展示串。
     *
     * 对应旧 chat_FD 的 Chat.GameMode / Chat.Position —— 原来写在各后端的
     * plugins/FloatDream/config.yml 里，现在集中到 Velocity 的 locations.<子服名> 下，
     * 改完 /fd reload 即时生效，不用再动后端。
     */
    private String serverLocation(String serverName, String key) {
        if (serverName == null || serverName.isEmpty()) {
            return "";
        }
        String value = this.plugin.config().str("locations." + serverName + "." + key, "");
        return value == null ? "" : value;
    }

    public static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
        }
        return (seconds / 3600) + " 小时 " + ((seconds % 3600) / 60) + " 分";
    }
}
