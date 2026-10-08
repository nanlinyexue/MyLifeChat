package fd.chat.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * 旧式颜色代码（&amp;a / §a）转换器。
 *
 * 拆成两档权限，便于分开售卖：
 *   - 颜色：&0-&f（16 色 + &r 重置）
 *   - 字体：&l 加粗 / &o 斜体 / &n 下划线 / &m 删除线 / &k 乱码
 *
 * 安全约束：只认「& + 单字符」这一种形式，绝不把玩家输入交给 MiniMessage，
 * 因此玩家无法注入 hover / click / 渐变 等标签，杜绝钓鱼链接。
 */
public final class LegacyColors {

    private LegacyColors() {
    }

    /** 允许的颜色代码（含十六进制 &x 形式可后续扩展）。 */
    private static final String COLOR_CODES = "0123456789abcdefr";

    /** 允许的字体代码。 */
    private static final String FORMAT_CODES = "lonmk";

    /** 是否需要转换（快速判断，避免无谓开销）。 */
    public static boolean containsCode(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '&' || c == '\u00a7') {
                return true;
            }
        }
        return false;
    }

    /**
     * 把玩家消息转成 Component。
     *
     * @param text            玩家原始输入
     * @param allowColor      是否允许颜色代码（权限 mylife.chat.color）
     * @param allowFormat     是否允许字体代码（权限 mylife.chat.format）
     * @param defaultColor    基础颜色（通常白色）
     * @return 渲染好的组件；无权限的代码会以字面量原样显示
     */
    public static Component render(String text, boolean allowColor, boolean allowFormat,
                                   NamedTextColor defaultColor) {
        if (text == null || text.isEmpty()) {
            return Component.empty().color(defaultColor);
        }

        Component root = Component.empty();
        StringBuilder buffer = new StringBuilder();
        NamedTextColor currentColor = defaultColor;
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        boolean obfuscated = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isMarker = (c == '&' || c == '\u00a7') && i + 1 < text.length();
            if (!isMarker) {
                buffer.append(c);
                continue;
            }

            char code = Character.toLowerCase(text.charAt(i + 1));
            boolean isColorCode = COLOR_CODES.indexOf(code) >= 0;
            boolean isFormatCode = FORMAT_CODES.indexOf(code) >= 0;

            // 无权限：原样输出这两个字符
            if ((isColorCode && !allowColor) || (isFormatCode && !allowFormat)) {
                buffer.append(c);
                continue;
            }

            // 先把已累积的文本按当前样式落盘
            if (buffer.length() > 0) {
                root = root.append(styled(buffer.toString(), currentColor, bold, italic,
                    underlined, strikethrough, obfuscated));
                buffer.setLength(0);
            }

            if (isColorCode) {
                if (code == 'r') {
                    // 重置：颜色回默认、字体全部关闭
                    currentColor = defaultColor;
                    bold = italic = underlined = strikethrough = obfuscated = false;
                } else {
                    currentColor = legacyColor(code);
                }
            } else {
                switch (code) {
                    case 'l' -> bold = true;
                    case 'o' -> italic = true;
                    case 'n' -> underlined = true;
                    case 'm' -> strikethrough = true;
                    case 'k' -> obfuscated = true;
                    default -> {
                        // 不会到达
                    }
                }
            }
            i++; // 跳过代码字符
        }

        if (buffer.length() > 0) {
            root = root.append(styled(buffer.toString(), currentColor, bold, italic,
                underlined, strikethrough, obfuscated));
        }
        return root;
    }

    private static Component styled(String text, NamedTextColor color, boolean bold, boolean italic,
                                    boolean underlined, boolean strikethrough, boolean obfuscated) {
        Component c = Component.text(text).color(color);
        c = c.decoration(TextDecoration.BOLD, bold ? TextDecoration.State.TRUE : TextDecoration.State.FALSE);
        c = c.decoration(TextDecoration.ITALIC, italic ? TextDecoration.State.TRUE : TextDecoration.State.FALSE);
        c = c.decoration(TextDecoration.UNDERLINED, underlined ? TextDecoration.State.TRUE : TextDecoration.State.FALSE);
        c = c.decoration(TextDecoration.STRIKETHROUGH, strikethrough ? TextDecoration.State.TRUE : TextDecoration.State.FALSE);
        c = c.decoration(TextDecoration.OBFUSCATED, obfuscated ? TextDecoration.State.TRUE : TextDecoration.State.FALSE);
        return c;
    }

    /** 旧式 16 色映射。 */
    private static NamedTextColor legacyColor(char code) {
        return switch (code) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> NamedTextColor.WHITE;
        };
    }

    /** 供配置/文档展示用的代码清单。 */
    public static List<String> colorCodeList() {
        List<String> out = new ArrayList<>();
        for (char c : COLOR_CODES.toCharArray()) {
            out.add("&" + c);
        }
        return out;
    }

    public static List<String> formatCodeList() {
        List<String> out = new ArrayList<>();
        for (char c : FORMAT_CODES.toCharArray()) {
            out.add("&" + c);
        }
        return out;
    }
}
