package fd.chat.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;

/**
 * 指令注册与「昵称替代 ID」重写。
 *
 * 重写机制（Velocity 原生能力，已实测）：
 *   CommandExecuteEvent.CommandResult.forwardToServer(newCommand)
 * 可以在指令到达后端子服之前改写它。
 *
 * 安全护栏：
 *   - 只重写配置里列出的指令（identity.commands），避免误伤
 *   - 只替换「能解析成玩家」的参数，不动其它参数
 *   - 支持指令黑名单（登录/注册之类绝不改写）
 */
public final class CommandRegistrar {

    /** AI 插件注册进来的重写器（order 越小越先）。 */
    public static final List<ApiImplRewriter> REWRITERS = new CopyOnWriteArrayList<>();

    /** 内部记录类型，避免 ApiImpl 的包可见性问题。 */
    public record ApiImplRewriter(fd.chat.api.FdChatApi.CommandRewriter rewriter, int order) {
    }

    private final MyLifeChatPlugin plugin;

    public CommandRegistrar(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        var manager = this.plugin.proxy().getCommandManager();

        // /中文名 —— 默认对所有玩家开放（权限节点为空表示不检查）
        // 设 nickname.permission 可重新启用权限限制
        String nickPerm = this.plugin.config().str("nickname.permission", "");
        register(manager, "中文名", List.of("名字", "nick", "nickname", "cnname"),
            new NicknameCommand(this.plugin), nickPerm);

        // /t 私聊
        List<String> aliases = new ArrayList<>(this.plugin.config()
            .strList("channels.private.aliases"));
        if (aliases.isEmpty()) {
            aliases = List.of("t", "tell", "msg", "w", "m", "私聊");
        }
        String primary = aliases.get(0).equals("t") ? "t" : aliases.get(0);
        List<String> rest = aliases.size() > 1 ? aliases.subList(1, aliases.size()) : List.of();
        register(manager, primary, rest, new MessageCommand(this.plugin), "mylife.msg");

        // /r 回复
        register(manager, "r", List.of("reply", "回复"),
            new ReplyCommand(this.plugin), "mylife.msg");

        // /ignore
        register(manager, "ignore", List.of("忽略", "block"),
            new IgnoreCommand(this.plugin), "mylife.ignore");

        // /spy
        register(manager, "spy", List.of("监视", "socialspy"),
            new SpyCommand(this.plugin), "mylife.spy");

        // /在线 跨服在线列表
        if (this.plugin.config().bool("online.enabled", true)) {
            List<String> onlineAliases = new ArrayList<>(this.plugin.config().strList("online.aliases"));
            String onlinePrimary = onlineAliases.isEmpty() ? "在线" : onlineAliases.get(0);
            List<String> onlineRest = onlineAliases.size() > 1
                ? onlineAliases.subList(1, onlineAliases.size()) : List.of();
            register(manager, onlinePrimary, onlineRest, new OnlineCommand(this.plugin), "");
        }

        // /fd 管理
        register(manager, "fd", List.of("fdchat"),
            new AdminCommand(this.plugin), "mylife.admin");

        this.plugin.logger().info("Commands registered.");
    }

    private void register(com.velocitypowered.api.command.CommandManager manager,
                          String name, List<String> aliases, SimpleCommand command, String permission) {
        var meta = manager.metaBuilder(name).aliases(aliases.toArray(String[]::new)).build();
        manager.register(meta, new PermissionedCommand(command, permission));
    }

    /** 包一层权限判断（console 始终允许）。 */
    private record PermissionedCommand(SimpleCommand delegate, String permission) implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            // 权限为空 → 所有人可用
            if (this.permission != null && !this.permission.isBlank()
                && !invocation.source().hasPermission(this.permission)
                && invocation.source() instanceof Player) {
                invocation.source().sendMessage(Component.text("§c你没有权限使用该指令。"));
                return;
            }
            this.delegate.execute(invocation);
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return this.delegate.suggest(invocation);
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return true; // 权限在 execute 里统一处理，便于 console 放行
        }
    }

    // ------------------------------------------------------------------
    //  昵称 -> ID 重写
    // ------------------------------------------------------------------

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        if (!this.plugin.config().bool("identity.enabled", true)) {
            return;
        }

        String original = event.getCommand();
        if (original == null || original.isBlank()) {
            return;
        }

        // 先给 AI 插件等外部重写器一次机会（例如纠正错别字指令）
        String current = runExternalRewriters(player, original);

        // 未知指令 → 给出相近指令建议（本地兜底，AI 插件可覆盖）
        maybeSuggestSimilar(player, current);

        // 再做昵称 -> ID 替换
        String rewritten = rewriteIdentity(player, current);
        if (!rewritten.equals(original)) {
            event.setResult(CommandExecuteEvent.CommandResult.forwardToServer(rewritten));
            if (this.plugin.config().bool("settings.debug", false)) {
                this.plugin.logger().info("[NickRewrite] {} : /{} -> /{}",
                    player.getUsername(), original, rewritten);
            }
        }
    }

    private String runExternalRewriters(Player player, String command) {
        String current = command;
        List<ApiImplRewriter> sorted = REWRITERS.stream()
            .sorted(java.util.Comparator.comparingInt(ApiImplRewriter::order))
            .toList();
        for (ApiImplRewriter entry : sorted) {
            try {
                Optional<String> result = entry.rewriter().rewrite(player, current);
                if (result.isPresent() && !result.get().isBlank()) {
                    current = result.get();
                }
            } catch (Exception ex) {
                this.plugin.logger().warn("Command rewriter failed, skipped.", ex);
            }
        }
        return current;
    }

    /** 把指令参数里的中文名替换成账号名。 */
    private String rewriteIdentity(Player player, String command) {
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        if (trimmed.isBlank()) {
            return command;
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            return command; // 没有参数，无需处理
        }

        String label = parts[0].toLowerCase(Locale.ROOT);

        // 黑名单：绝不改写
        List<String> blacklist = lower(this.plugin.config().strList("identity.command-blacklist"));
        if (blacklist.contains(label)) {
            return command;
        }

        // 白名单：只处理列出的指令
        List<String> allowed = lower(this.plugin.config().strList("identity.commands"));
        boolean allowedCommand = allowed.isEmpty() || allowed.contains(label);
        if (!allowedCommand) {
            return command;
        }

        boolean changed = false;
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            String replaced = tryResolve(player, arg);
            if (replaced != null && !replaced.equals(arg)) {
                out.append(' ').append(replaced);
                changed = true;
            } else {
                out.append(' ').append(arg);
            }
        }
        return changed ? out.toString() : command;
    }

    /** 尝试把一个参数解析成账号名；解析不了返回 null。 */
    private String tryResolve(Player player, String arg) {
        if (arg == null || arg.isBlank()) {
            return null;
        }
        // 纯数字/含特殊符号的当作非玩家名，直接跳过（价格、数量、坐标等）
        if (!arg.matches("[\\p{IsHan}A-Za-z0-9_]{2,16}")) {
            return null;
        }
        // 已经是账号名就不用动
        if (this.plugin.proxy().getPlayer(arg).isPresent()) {
            return null;
        }

        var resolution = this.plugin.identity().resolve(arg);
        if (resolution instanceof fd.chat.user.IdentityResolver.Resolution.Found found) {
            // 解析结果与输入一致（大小写差异除外）说明本来就是账号名
            if (found.username().equalsIgnoreCase(arg)) {
                return null;
            }
            return found.username();
        }

        if (resolution instanceof fd.chat.user.IdentityResolver.Resolution.Ambiguous ambiguous) {
            player.sendMessage(this.plugin.renderer().parse(
                "<red>「" + arg + "」匹配到多个玩家：<yellow>"
                + String.join("<gray>, <yellow>", ambiguous.candidates())
                + "</yellow>，请输入更完整的名字。"));
            return null;
        }
        return null;
    }

    /**
     * 如果玩家输入的指令不存在，给出相近建议。
     * 只提示，不拦截 —— 避免误判导致正常指令无法执行。
     */
    private void maybeSuggestSimilar(Player player, String command) {
        if (!this.plugin.config().bool("ai.suggest-on-unknown-command", true)) {
            return;
        }
        LocalSuggester suggester = this.plugin.localSuggester();
        if (suggester == null) {
            return;
        }
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        String label = trimmed.split("\\s+")[0];
        if (label.isBlank()) {
            return;
        }
        // 已知指令（代理侧 + 内置表）直接跳过
        if (suggester.knownCommands().stream().anyMatch(c -> c.equalsIgnoreCase(label))) {
            return;
        }

        var suggestions = this.plugin.collectSuggestions(player, label, 3);
        if (suggestions.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("<red>未知指令 <yellow>/" + label + "</yellow>");
        sb.append("<gray>，你是不是想输入：");
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) {
                sb.append("<gray> / <yellow>");
            } else {
                sb.append("<yellow>");
            }
            sb.append("/").append(suggestions.get(i));
        }
        sb.append("<gray>？");
        player.sendMessage(this.plugin.renderer().parse(sb.toString()));
    }

    private static List<String> lower(List<String> in) {
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    /** 供 API 使用：注册外部重写器。 */
    public static void addRewriter(fd.chat.api.FdChatApi.CommandRewriter rewriter, int order) {
        REWRITERS.add(new ApiImplRewriter(rewriter, order));
    }
}
