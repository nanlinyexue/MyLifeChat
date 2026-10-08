package fd.chat.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import java.util.List;
import net.kyori.adventure.text.Component;

/** /r &lt;内容&gt; —— 回复最近一位私聊对象。 */
final class ReplyCommand implements SimpleCommand {

    private final MyLifeChatPlugin plugin;

    ReplyCommand(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("该指令只能由玩家使用。"));
            return;
        }
        new MessageCommand(this.plugin).send(player, invocation.arguments(), true);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
