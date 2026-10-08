package fd.chat.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import fd.chat.user.IdentityResolver;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** /ignore &lt;玩家&gt; —— 屏蔽某人的聊天与私聊（再执行一次解除）。 */
final class IgnoreCommand implements SimpleCommand {

    private final MyLifeChatPlugin plugin;

    IgnoreCommand(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("该指令只能由玩家使用。"));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            var list = this.plugin.users().ignored(player.getUniqueId());
            if (list.isEmpty()) {
                player.sendMessage(this.plugin.renderer().parse("<gray>你还没有忽略任何人。"));
                return;
            }
            StringBuilder sb = new StringBuilder("<gray>已忽略：<yellow>");
            boolean first = true;
            for (var id : list) {
                String name = this.plugin.proxy().getPlayer(id)
                    .map(Player::getUsername)
                    .orElse(id.toString().substring(0, 8));
                if (!first) {
                    sb.append("<gray>, <yellow>");
                }
                sb.append(name);
                first = false;
            }
            player.sendMessage(this.plugin.renderer().parse(sb.toString()));
            return;
        }

        var resolution = this.plugin.identity().resolve(args[0]);
        if (resolution instanceof IdentityResolver.Resolution.Ambiguous a) {
            player.sendMessage(this.plugin.renderer().parse(
                "<red>「" + args[0] + "」匹配到多个玩家：<yellow>" + String.join("<gray>, <yellow>", a.candidates())));
            return;
        }
        if (!(resolution instanceof IdentityResolver.Resolution.Found found)) {
            player.sendMessage(this.plugin.renderer().parse("<red>找不到玩家 <yellow>" + args[0] + "</yellow>。"));
            return;
        }
        if (found.uuid().equals(player.getUniqueId())) {
            player.sendMessage(this.plugin.renderer().parse("<red>不能忽略自己。"));
            return;
        }
        if (player.hasPermission("mylife.ignore.exempt")
            && this.plugin.proxy().getPlayer(found.uuid())
                .map(p -> p.hasPermission("mylife.ignore.exempt")).orElse(false)) {
            player.sendMessage(this.plugin.renderer().parse("<red>该玩家无法被忽略。"));
            return;
        }

        String display = this.plugin.identity().displayName(found.uuid(), found.username());
        if (this.plugin.users().ignoring(player.getUniqueId(), found.uuid())) {
            this.plugin.users().unignore(player.getUniqueId(), found.uuid());
            player.sendMessage(this.plugin.renderer().parse("<green>已取消忽略 <yellow>" + display + "</yellow>。"));
        } else {
            this.plugin.users().ignore(player.getUniqueId(), found.uuid());
            player.sendMessage(this.plugin.renderer().parse("<green>已忽略 <yellow>" + display + "</yellow>。"));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return List.of();
        }
        String[] args = invocation.arguments();
        String prefix = args.length == 1 ? args[0] : "";
        return this.plugin.identity().completions(prefix.toLowerCase(Locale.ROOT));
    }
}
