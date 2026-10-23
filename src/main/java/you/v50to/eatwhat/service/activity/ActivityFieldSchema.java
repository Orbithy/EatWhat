package you.v50to.eatwhat.service.activity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 活动扩展字段：管理员给每个活动定义最多 10 个字段，用户发内容时按定义填写 extra。
 * <p>
 * 字段定义的格式（每个类型的键集合是固定的，便于两站对同一份定义算出相同的内容摘要）：
 * <pre>
 * TEXT    {key, label, type, required, maxLength}   maxLength 1-500
 * NUMBER  {key, label, type, required, min, max}    min/max 为整数，可为 null
 * BOOLEAN {key, label, type, required}
 * SELECT  {key, label, type, required, options}     options 1-20 个互不相同的选项
 * </pre>
 */
public final class ActivityFieldSchema {
    public static final int MAX_FIELDS = 10;
    public static final int MAX_EXTRA_BYTES = 8192;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,31}$");
    private static final Set<String> COMMON = Set.of("key", "label", "type", "required");
    private static final Map<String, Set<String>> TYPE_FIELDS = Map.of(
            "TEXT", Set.of("maxLength"),
            "NUMBER", Set.of("min", "max"),
            "BOOLEAN", Set.of(),
            "SELECT", Set.of("options"));

    private ActivityFieldSchema() { }

    /** 校验字段定义，返回规范化后的副本；不合法时抛出带中文原因的 IllegalArgumentException。 */
    public static List<Map<String, Object>> validateDefinition(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw invalid("扩展字段定义必须是数组");
        if (list.size() > MAX_FIELDS) throw invalid("扩展字段最多 " + MAX_FIELDS + " 个");
        Set<String> keys = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> field)) throw invalid("扩展字段定义必须是对象");
            Object type = field.get("type");
            if (!(type instanceof String typeName) || !TYPE_FIELDS.containsKey(typeName)) {
                throw invalid("扩展字段类型只能是 TEXT、NUMBER、BOOLEAN 或 SELECT");
            }
            Set<String> expected = new HashSet<>(COMMON);
            expected.addAll(TYPE_FIELDS.get(typeName));
            if (!expected.equals(stringKeys(field))) throw invalid("扩展字段 " + field.get("key") + " 的属性不符合 " + typeName + " 类型的要求");
            if (!(field.get("key") instanceof String key) || !KEY.matcher(key).matches()) {
                throw invalid("扩展字段 key 需以小写字母开头，只含字母、数字和下划线，最长 32");
            }
            if (!keys.add(key)) throw invalid("扩展字段 key 重复：" + key);
            if (!(field.get("label") instanceof String label) || label.isBlank() || label.length() > 32) {
                throw invalid("扩展字段 " + key + " 的名称需为 1-32 个字符");
            }
            if (!(field.get("required") instanceof Boolean required)) throw invalid("扩展字段 " + key + " 的 required 必须是布尔值");
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("key", key);
            normalized.put("label", label);
            normalized.put("type", typeName);
            normalized.put("required", required);
            switch (typeName) {
                case "TEXT" -> {
                    Long max = integer(field.get("maxLength"));
                    if (max == null || max < 1 || max > 500) throw invalid("文本字段 " + key + " 的 maxLength 需为 1-500");
                    normalized.put("maxLength", max.intValue());
                }
                case "NUMBER" -> {
                    Long min = nullableInteger(field.get("min"), key);
                    Long max = nullableInteger(field.get("max"), key);
                    if (min != null && max != null && min > max) throw invalid("数字字段 " + key + " 的 min 不能大于 max");
                    normalized.put("min", min);
                    normalized.put("max", max);
                }
                case "SELECT" -> {
                    if (!(field.get("options") instanceof List<?> options) || options.isEmpty() || options.size() > 20) {
                        throw invalid("选择字段 " + key + " 需有 1-20 个选项");
                    }
                    Set<String> seen = new HashSet<>();
                    List<String> copy = new ArrayList<>(options.size());
                    for (Object option : options) {
                        if (!(option instanceof String text) || text.isBlank() || text.length() > 32 || !seen.add(text)) {
                            throw invalid("选择字段 " + key + " 的选项需为互不相同的 1-32 个字符");
                        }
                        copy.add(text);
                    }
                    normalized.put("options", List.copyOf(copy));
                }
                default -> { }
            }
            result.add(java.util.Collections.unmodifiableMap(normalized));
        }
        return List.copyOf(result);
    }

    /**
     * 发布内容时按活动当前的字段定义校验 extra，返回只含已定义字段的副本。
     * 文本去掉首尾空白，空文本视为没填。
     */
    public static Map<String, Object> validateExtra(List<Map<String, Object>> definition, Map<String, Object> extra) {
        Map<String, Object> source = extra == null ? Map.of() : extra;
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Map<String, Object> field : definition) fields.put((String) field.get("key"), field);
        for (String key : source.keySet()) {
            if (!fields.containsKey(key)) throw invalid("活动没有扩展字段：" + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> field = entry.getValue();
            String label = (String) field.get("label");
            Object value = source.get(key);
            if (value instanceof String text) {
                value = text.strip();
                if (((String) value).isEmpty()) value = null;
            }
            if (value == null) {
                if (Boolean.TRUE.equals(field.get("required"))) throw invalid("请填写" + label);
                continue;
            }
            switch ((String) field.get("type")) {
                case "TEXT" -> {
                    if (!(value instanceof String text)) throw invalid(label + "需为文本");
                    if (text.length() > ((Number) field.get("maxLength")).intValue()) {
                        throw invalid(label + "不能超过 " + field.get("maxLength") + " 个字符");
                    }
                }
                case "NUMBER" -> {
                    if (!(value instanceof Number number)) throw invalid(label + "需为数字");
                    BigDecimal decimal;
                    try { decimal = new BigDecimal(number.toString()); }
                    catch (NumberFormatException notFinite) { throw invalid(label + "需为数字"); }
                    if (field.get("min") instanceof Number min && decimal.compareTo(BigDecimal.valueOf(min.longValue())) < 0) {
                        throw invalid(label + "不能小于 " + min);
                    }
                    if (field.get("max") instanceof Number max && decimal.compareTo(BigDecimal.valueOf(max.longValue())) > 0) {
                        throw invalid(label + "不能大于 " + max);
                    }
                }
                case "BOOLEAN" -> {
                    if (!(value instanceof Boolean)) throw invalid(label + "需为是或否");
                }
                case "SELECT" -> {
                    if (!(value instanceof String text) || !((List<?>) field.get("options")).contains(text)) {
                        throw invalid(label + "不是可选的选项");
                    }
                }
                default -> throw invalid("活动扩展字段定义无效");
            }
            result.put(key, value);
        }
        return result;
    }

    private static Set<String> stringKeys(Map<?, ?> map) {
        Set<String> keys = new HashSet<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String text)) throw invalid("扩展字段定义的属性名必须是字符串");
            keys.add(text);
        }
        return keys;
    }

    private static Long integer(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) return ((Number) value).longValue();
        if (value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0) {
            try { return decimal.longValueExact(); } catch (ArithmeticException overflow) { return null; }
        }
        return null;
    }

    private static Long nullableInteger(Object value, String key) {
        if (value == null) return null;
        Long parsed = integer(value);
        if (parsed == null || Math.abs(parsed) > 1_000_000_000_000L) throw invalid("数字字段 " + key + " 的 min/max 需为整数");
        return parsed;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
