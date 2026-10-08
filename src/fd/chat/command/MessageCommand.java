package fd.chat.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import fd.chat.user.IdentityResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * /t &lt;目标&gt; &lt;内容&gt; —— 私聊。
 *
 * 目标支持中文名、账号名、唯一前缀（走 IdentityResolver）。
 * 支持忽略、私聊开关、管理员窥屏（/spy）。
 */
final class MessageCommand implements SimpleCommand {

    /** 记录每个玩家的最近一位对话对象，供 /r 使用。 */
    static final java.util.Map<UUID, UUID> LAST_PARTNER = new java.util.concurrent.ConcurrentHashMap<>();

    private final MyLifeChatPlugin plugin;

    MessageCommand(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("该指令只能由玩家使用。"));
            return;
        }
        send(player, invocation.arguments(), false);
    }

    void send(Player player, String[] args, boolean replyMode) {
        if (args.length == 0) {
            player.sendMessage(this.plugin.renderer().parse(
                replyMode ? "<red>用法：<yellow>/r <内容></yellow>" : "<red>用法：<yellow>/t <玩家> <内容></yellow>"));
            return;
        }

        Player target;
        String content;

        if (replyMode) {
            UUID partner = LAST_PARTNER.get(player.getUniqueId());
            if (partner == null) {
                player.sendMessage(this.plugin.renderer().parse("<red>还没有可以回复的对象。"));
                return;
            }
            Optional<Player> online = this.plugin.proxy().getPlayer(partner);
            if (online.isEmpty()) {
                player.sendMessage(this.plugin.renderer().parse("<red>对方已下线。"));
                return;
            }
            target = online.get();
            content = String.join(" ", args);
        } else {
            if (args.length < 2) {
                player.sendMessage(this.plugin.renderer().parse("<red>用法：<yellow>/t <玩家> <内容></yellow>"));
                return;
            }
            String nameArg = args[0];
            IdentityResolver.Resolution resolution = this.plugin.identity().resolve(nameArg);

            if (resolution instanceof IdentityResolver.Resolution.Ambiguous ambiguous) {
                player.sendMessage(this.plugin.renderer().parse(
                    "<red>「" + nameArg + "」匹配到多个玩家：<yellow>"
                    + String.join("<gray>, <yellow>", ambiguous.candidates()) + "</yellow>。"));
                return;
            }
            if (!(resolution instanceof IdentityResolver.Resolution.Found found)) {
                player.sendMessage(this.plugin.renderer().parse(
                    "<red>找不到玩家 <yellow>" + nameArg + "</yellow>。"));
                return;
            }
            Optional<Player> online = this.plugin.proxy().getPlayer(found.uuid());
            if (online.isEmpty()) {
                player.sendMessage(this.plugin.renderer().parse(
                    "<red><yellow>" + found.username() + "</yellow> 当前不在线。"));
                return;
            }
            target = online.get();
            content = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(this.plugin.renderer().parse("<red>不能给自己发私聊。"));
            return;
        }

        // 忽略关系
        if (this.plugin.users().ignoring(target.getUniqueId(), player.getUniqueId())) {
            player.sendMessage(this.plugin.renderer().parse(
                "<red>" + this.plugin.identity().displayName(target.getUniqueId(), target.getUsername())
                + " 正在忽略你。"));
            return;
        }
        if (this.plugin.users().ignoring(player.getUniqueId(), target.getUniqueId())) {
            player.sendMessage(this.plugin.renderer().parse(
                "<red>你正在忽略对方，请先 <yellow>/ignore "
                + this.plugin.identity().displayName(target.getUniqueId(), target.getUsername())
                + "</yellow> 解除。"));
            return;
        }

        // 对方关闭了私聊
        if (!this.plugin.users().dmEnabled(target.getUniqueId())) {
            player.sendMessage(this.plugin.renderer().parse("<red>对方已关闭私聊接收。"));
            return;
        }

        // 广告/刷屏同样作用于私聊
        boolean bypass = player.hasPermission(
            this.plugin.config().str("moderation.bypass-permission", "mylife.moderation.bypass"));
        if (!bypass && this.plugin.config().bool("moderation.anti-advertisement", true)) {
            var verdict = this.plugin.adFilter().check(content);
            if (verdict.hit() && this.plugin.adFilter().action()
                == fd.chat.moderation.AdFilter.Action.BLOCK) {
                player.sendMessage(this.plugin.renderer().parse(this.plugin.adFilter().blockMessage()));
                return;
            }
        }

        String senderName = this.plugin.identity().displayName(player.getUniqueId(), player.getUsername());
        String targetName = this.plugin.identity().displayName(target.getUniqueId(), target.getUsername());

        // 发送者
        player.sendMessage(this.plugin.renderer().parse(
            this.plugin.config().str("channels.private.format-to",
                "<gold>[<green>你</green>] -> [<green>%recipient%</green>]</gold> <white>%message%</white>")
                .replace("%recipient%", targetName).replace("%message%", fd.chat.render.Renderer.escape(content))));

        // 接收者
        target.sendMessage(this.plugin.renderer().parse(
            this.plugin.config().str("channels.private.format-from",
                "<gold>[<green>%sender%</green>] -> [<green>你</green>]</gold> <white>%message%</white>")
                .replace("%sender%", senderName).replace("%message%", fd.chat.render.Renderer.escape(content))));

        LAST_PARTNER.put(player.getUniqueId(), target.getUniqueId());
        LAST_PARTNER.put(target.getUniqueId(), player.getUniqueId());

        // 提示音
        playSound(target);

        // 窥屏
        notifySpies(player, target, senderName, targetName, content);

        this.plugin.logger().info("[PM] {} -> {}: {}", player.getUsername(), target.getUsername(), content);
    }

    private void playSound(Player target) {
        String soundName = this.plugin.config().str("channels.private.sound", "");
        if (soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            target.playSound(Sound.sound(Key.key(soundName), Sound.Source.MASTER, 1f, 1f));
        } catch (Exception ignored) {
            // 音效名非法时静默跳过
        }
    }

    private void notifySpies(Player sender, Player target, String senderName, String targetName, String content) {
        String format = this.plugin.config().str("channels.private.format-spy",
            "<dark_red>SPY</dark_red> <gray>[%sender%] -> [%recipient%]</gray> <white>%message%</white>");
        for (Player online : this.plugin.proxy().getAllPlayers()) {
            if (online.getUniqueId().equals(sender.getUniqueId())
                || online.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (!this.plugin.users().spying(online.getUniqueId())) {
                continue;
            }
            online.sendMessage(this.plugin.renderer().parse(
                format.replace("%sender%", senderName)
                    .replace("%recipient%", targetName)
                    .replace("%message%", fd.chat.render.Renderer.escape(content))));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return List.of();
        }
        String[] args = invocation.arguments();
        // 补全第一个参数（玩家名/中文名）
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0] : "";
            return this.plugin.identity().completions(prefix.toLowerCase(Locale.ROOT));
        }
        return List.of();
    }
}
