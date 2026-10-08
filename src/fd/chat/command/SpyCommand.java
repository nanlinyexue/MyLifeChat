package fd.chat.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import java.util.List;
import net.kyori.adventure.text.Component;

/** /spy —— 切换私聊窥屏。 */
final class SpyCommand implements SimpleCommand {

    private final MyLifeChatPlugin plugin;

    SpyCommand(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("该指令只能由玩家使用。"));
            return;
        }
        boolean now = this.plugin.users().toggleSpy(player.getUniqueId(), player.getUsername());
        player.sendMessage(this.plugin.renderer().parse(now
            ? "<green>已开启私聊窥屏。"
            : "<red>已关闭私聊窥屏。"));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
