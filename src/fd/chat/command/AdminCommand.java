package fd.chat.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * /fd —— 管理指令。
 *
 *   /fd reload [all|ads|spam]      热重载配置
 *   /fd nick &lt;玩家&gt; &lt;中文名&gt;     管理员强制设置/清除中文名
 *   /fd mute &lt;玩家&gt; [秒] [原因]    禁言
 *   /fd unmute &lt;玩家&gt;             解除禁言
 *   /fd info &lt;玩家&gt;               查看玩家资料
 *   /fd links                      显示可用的对外接口（供 AI 插件开发参考）
 */
final class AdminCommand implements SimpleCommand {

    private final MyLifeChatPlugin plugin;

    AdminCommand(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            help(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                String what = args.length > 1 ? args[1].toLowerCase() : "all";
                boolean ads = what.equals("all") || what.equals("ads");
                boolean spam = what.equals("all") || what.equals("spam");
                this.plugin.reload(ads, spam);
                source.sendMessage(this.plugin.renderer().parse(
                    "<green>已重载：<yellow>" + what + "</yellow>"));
            }
            case "nick" -> adminNick(source, args);
            case "mute" -> mute(source, args, true);
            case "unmute" -> mute(source, args, false);
            case "info" -> info(source, args);
            case "links" -> links(source);
            case "debug" -> debug(source);
            default -> help(source);
        }
    }

    private void help(CommandSource source) {
        source.sendMessage(this.plugin.renderer().parse(
            "<yellow><bold>fdchat 管理指令</bold></yellow>\n"
            + "<gray>  /fd reload [all|ads|spam]  <dark_gray>-</dark_gray> 热重载配置\n"

            + "<gray>  /fd nick <玩家> <中文名>    <dark_gray>-</dark_gray> 强制设置中文名\n"
            + "<gray>  /fd mute <玩家> [秒] [原因] <dark_gray>-</dark_gray> 禁言\n"
            + "<gray>  /fd unmute <玩家>          <dark_gray>-</dark_gray> 解除禁言\n"
            + "<gray>  /fd info <玩家>            <dark_gray>-</dark_gray> 查看资料\n"
            + "<gray>  /fd links                  <dark_gray>-</dark_gray> 对外接口说明"));
    }

    private void adminNick(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(Component.text("用法: /fd nick <玩家> <中文名>"));
            return;
        }
        var resolution = this.plugin.identity().resolve(args[1]);
        if (!(resolution instanceof fd.chat.user.IdentityResolver.Resolution.Found found)) {
            source.sendMessage(this.plugin.renderer().parse("<red>找不到玩家 <yellow>" + args[1] + "</yellow>。"));
            return;
        }
        String nickname = args[2];
        var result = this.plugin.nicknames().set(found.uuid(), found.username(), nickname, 0, false);
        source.sendMessage(this.plugin.renderer().parse(switch (result) {
            case OK -> "<green>已将 <yellow>" + found.username() + "</yellow> 的中文名设为 <yellow>" + nickname + "</yellow>。";
            case TAKEN -> "<red>该中文名已被占用。";
            case INVALID -> "<red>中文名不合法。";
            case UNCHANGED -> "<gray>没有变化。";
            default -> "<red>保存失败。";
        }));
    }

    private void mute(CommandSource source, String[] args, boolean mute) {
        if (args.length < 2) {
            source.sendMessage(Component.text("用法: /fd " + (mute ? "mute <玩家> [秒] [原因]" : "unmute <玩家>")));
            return;
        }
        var resolution = this.plugin.identity().resolve(args[1]);
        if (!(resolution instanceof fd.chat.user.IdentityResolver.Resolution.Found found)) {
            source.sendMessage(this.plugin.renderer().parse("<red>找不到玩家 <yellow>" + args[1] + "</yellow>。"));
            return;
        }
        Optional<Player> online = this.plugin.proxy().getPlayer(found.uuid());
        String username = online.map(Player::getUsername).orElse(found.username());

        if (!mute) {
            this.plugin.users().unmute(found.uuid(), username);
            source.sendMessage(this.plugin.renderer().parse("<green>已解除 <yellow>" + username + "</yellow> 的禁言。"));
            online.ifPresent(p -> p.sendMessage(this.plugin.renderer().parse("<green>你已被解除禁言。")));
            return;
        }

        long seconds = args.length > 2 ? parseLong(args[2], 0) : 0;
        String reason = args.length > 3
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
            : null;
        this.plugin.users().mute(found.uuid(), username, seconds, reason);
        String duration = seconds <= 0 ? "永久" : fd.chat.ChatDispatcher.formatDuration(seconds);
        source.sendMessage(this.plugin.renderer().parse(
            "<green>已禁言 <yellow>" + username + "</yellow>（" + duration + "）。"));
        online.ifPresent(p -> p.sendMessage(this.plugin.renderer().parse(
            "<red>你已被禁言（" + duration + "）"
            + (reason == null ? "" : "，原因：" + reason))));
    }

    private void info(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(Component.text("用法: /fd info <玩家>"));
            return;
        }
        var resolution = this.plugin.identity().resolve(args[1]);
        if (!(resolution instanceof fd.chat.user.IdentityResolver.Resolution.Found found)) {
            source.sendMessage(this.plugin.renderer().parse("<red>找不到玩家。"));
            return;
        }
        Optional<Player> online = this.plugin.proxy().getPlayer(found.uuid());
        String nickname = this.plugin.nicknames().byUuid(found.uuid())
            .map(fd.chat.user.NicknameRepository.Entry::nickname).orElse("<gray>未设置");
        boolean muted = this.plugin.users().isMuted(found.uuid());
        String server = online.flatMap(p -> p.getCurrentServer()
            .map(c -> c.getServerInfo().getName())).orElse("<gray>离线");
        double balance = this.plugin.economy().balance(found.uuid());

        source.sendMessage(this.plugin.renderer().parse(
            "<yellow><bold>" + found.username() + "</bold></yellow>\n"
            + "<gray>  中文名: <yellow>" + nickname + "\n"
            + "<gray>  所在服: <yellow>" + server + "\n"
            + "<gray>  禁言中: <yellow>" + (muted ? "是（剩余 " + fd.chat.ChatDispatcher.formatDuration(
                this.plugin.users().mutedRemainingSeconds(found.uuid())) + "）" : "否") + "\n"
            + "<gray>  余额: <yellow>" + this.plugin.economy().format(balance) + "\n"
            + "<gray>  UUID: <dark_gray>" + found.uuid()));
    }

    /** 给 AI 插件开发者看的接口说明。 */
    private void links(CommandSource source) {
        boolean available = fd.chat.api.FdChatApiRegistry.available();
        source.sendMessage(this.plugin.renderer().parse(
            "<yellow><bold>fdchat 对外接口</bold></yellow>\n"
            + "<gray>  状态: " + (available ? "<green>已注册" : "<red>未注册") + "\n"
            + "<gray>  入口: <white>fd.chat.api.FdChatApiRegistry.get()\n"
            + "<gray>  能力: <white>identity / suggestions / registerInterceptor / registerRewriter\n"
            + "<gray>  说明: AI 插件可用 <white>FdChatApiRegistry.whenAvailable(api -&gt; ...)\n"
            + "<gray>        注册联想来源与指令重写器，无需依赖 fdchat 内部类。"));
    }

    /** 诊断：检查 MiniPlaceholders 与 LuckPerms 扩展的可见性。 */
    private void debug(CommandSource source) {
        StringBuilder sb = new StringBuilder("<yellow><bold>fdchat 诊断</bold></yellow>\n");
        try {
            var expansions = io.github.miniplaceholders.api.MiniPlaceholders.expansionsAvailable();
            sb.append("<gray>MiniPlaceholders 扩展数: <yellow>").append(expansions.size()).append("\n");
            for (var e : expansions) {
                sb.append("<gray>  · <white>").append(e.name())
                  .append(" <dark_gray>audience=").append(e.registeredAudiencePlaceholders().size())
                  .append(" global=").append(e.globalPlaceholders() == null ? 0 : 1)
                  .append(" relational=").append(e.registeredRelationalPlaceholders().size())
                  .append("\n");
                if (e.name().toLowerCase().contains("luckperms")) {
                    sb.append("<gray>    标签: <white>");
                    e.registeredAudiencePlaceholders().forEach(p -> sb.append(p.name()).append(" "));
                    sb.append("\n");
                }
            }
        } catch (Throwable t) {
            sb.append("<red>MiniPlaceholders 不可用: ").append(t.getClass().getSimpleName()).append("\n");
        }
        // 实测解析：必须用「玩家自己」作为 audience，控制台没有 LuckPerms 数据
        if (source instanceof Player self) {
            try {
                var resolver = io.github.miniplaceholders.api.MiniPlaceholders.audienceGlobalPlaceholders();
                var ctx = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                    "self", net.kyori.adventure.text.minimessage.tag.Tag.inserting(
                        net.kyori.adventure.text.Component.text(self.getUsername())));
                var test = this.plugin.renderer().parseFor(self,
                    "<gray>以你为上下文解析: <white><prefix><gray>|<white><suffix>|", resolver);
                sb.append("<gray>标签解析: ")
                  .append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                      .plainText().serialize(test)).append("\n");
            } catch (Throwable t) {
                sb.append("<red>标签解析异常: ").append(t.getClass().getSimpleName())
                  .append(" ").append(String.valueOf(t.getMessage())).append("\n");
            }
        }
        source.sendMessage(this.plugin.renderer().parse(sb.toString()));
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase() : "";
            return List.of("reload", "nick", "mute", "unmute", "info", "links", "debug").stream()
                .filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return List.of("all", "ads", "spam");
        }
        if (args.length == 2) {
            return this.plugin.identity().completions("");
        }
        return List.of();
    }
}
