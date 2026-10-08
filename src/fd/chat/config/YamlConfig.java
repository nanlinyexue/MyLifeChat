package fd.chat.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * YAML 配置读取（带默认值，缺字段不影响启动）。
 *
 * 不引入 Configurate/Flyway 等重依赖，只用 SnakeYAML（342KB）。
 * 中文注释：首次启动时把 resources 里的原文件原样复制到数据目录。
 */
public final class YamlConfig {

    private final String fileName;
    private Map<String, Object> root;

    private YamlConfig(String fileName, Map<String, Object> root) {
        this.fileName = fileName;
        this.root = root;
    }

    public String fileName() {
        return this.fileName;
    }

    /** 从数据目录加载；不存在则从 jar 内释放默认文件。 */
    public static YamlConfig load(Path dataDir, String fileName, Logger logger) {
        Path file = dataDir.resolve(fileName);
        if (Files.notExists(file)) {
            try {
                Files.createDirectories(dataDir);
                try (InputStream in = YamlConfig.class.getClassLoader().getResourceAsStream(fileName)) {
                    if (in != null) {
                        Files.copy(in, file);
                    } else {
                        logger.warn("jar 内缺少默认配置 {}，将使用空配置。", fileName);
                        Files.createFile(file);
                    }
                }
            } catch (IOException ex) {
                logger.warn("释放默认配置 {} 失败。", fileName, ex);
            }
        }
        return read(file, fileName, logger);
    }

    /** 重新从磁盘读取（热重载用）。 */
    public YamlConfig reload(Path dataDir, Logger logger) {
        return read(dataDir.resolve(this.fileName), this.fileName, logger);
    }

    @SuppressWarnings("unchecked")
    private static YamlConfig read(Path file, String fileName, Logger logger) {
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (loaded instanceof Map<?, ?> map) {
                return new YamlConfig(fileName, (Map<String, Object>) map);
            }
            logger.warn("{} 顶层不是映射结构，使用空配置。", fileName);
        } catch (Exception ex) {
            logger.warn("读取 {} 失败，使用空配置。", fileName, ex);
        }
        return new YamlConfig(fileName, Map.of());
    }

    // ---------------- 基础取值 ----------------

    public Object raw(String path) {
        Object cur = this.root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            cur = map.get(part);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    public String str(String path, String def) {
        Object v = raw(path);
        return v == null ? def : String.valueOf(v);
    }

    public boolean bool(String path, boolean def) {
        Object v = raw(path);
        if (v instanceof Boolean b) {
            return b;
        }
        return v instanceof String s ? Boolean.parseBoolean(s.trim()) : def;
    }

    public int i(String path, int def) {
        Object v = raw(path);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return parseNum(v, def, Number::intValue);
    }

    public long l(String path, long def) {
        Object v = raw(path);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return parseNum(v, def, Number::longValue);
    }

    public double d(String path, double def) {
        Object v = raw(path);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return parseNum(v, def, Number::doubleValue);
    }

    private static <T> T parseNum(Object v, T def, java.util.function.Function<Number, T> conv) {
        if (v instanceof String s) {
            try {
                return conv.apply(Double.parseDouble(s.trim()));
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public List<String> strList(String path) {
        Object v = raw(path);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> mapList(String path) {
        Object v = raw(path);
        if (v instanceof List<?> list) {
            return list.stream()
                .filter(Map.class::isInstance)
                .map(e -> (Map<String, Object>) e)
                .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> sections(String path) {
        Object v = raw(path);
        if (!(v instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> child) {
                out.put(String.valueOf(e.getKey()), (Map<String, Object>) child);
            }
        }
        return out;
    }

    /** 安全编译正则；失败返回 null 并记录。 */
    public static Pattern compile(String regex, Logger logger, String what) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (Exception ex) {
            logger.warn("正则编译失败（{}）：{}", what, regex);
            return null;
        }
    }
}
