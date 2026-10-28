package mysql2dm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MySQL 列类型 → 达梦(DM8) 列类型映射。
 *
 * 说明：
 * - 达梦 VARCHAR(n)/CHAR(n) 的 n 默认按字节计（实例初始化参数 LENGTH_IN_CHAR=0），
 *   MySQL utf8mb4 每字符最多 4 字节，故默认将字符长度乘以 expandFactor=4；
 *   若目标库以 LENGTH_IN_CHAR=1 初始化，应将 varcharExpandFactor 配置为 1。
 * - VARCHAR 超过 8188 字节（8K 页上限）时映射为 TEXT，保证不截断数据。
 * - UNSIGNED 通过扩宽有符号类型保证数值范围不丢失：
 *   tinyint unsigned→SMALLINT, smallint unsigned→INT, int unsigned→BIGINT,
 *   mediumint unsigned→INT, bigint unsigned→DECIMAL(20,0)。
 */
final class TypeMapper {

    /** 达梦 8K 页下 VARCHAR 最大字节数 */
    static final int DM_VARCHAR_MAX = 8188;
    /** 达梦 CHAR 最大字节数 */
    static final int DM_CHAR_MAX = 2000;

    private TypeMapper() {
    }

    static class Result {
        String dmType;
        ColumnMeta.Kind kind;
        final List<String> warnings = new ArrayList<>();
    }

    static Result map(ColumnMeta c, double factor) {
        Result r = new Result();
        String t = (c.columnType == null || c.columnType.trim().isEmpty())
                ? c.dataType : c.columnType;
        t = t.trim().toLowerCase(Locale.ROOT);
        boolean unsigned = t.contains("unsigned");
        // 先剥离修饰符再解析基础类型，如 "int unsigned" → "int"
        t = t.replace("unsigned", "").replace("zerofill", "").trim();

        String head;
        String args;
        int p = t.indexOf('(');
        if (p >= 0) {
            head = t.substring(0, p).trim();
            int close = t.lastIndexOf(')');
            args = close > p ? t.substring(p + 1, close) : "";
        } else {
            head = t;
            args = "";
        }

        switch (head) {
            case "tinyint":
                r.dmType = unsigned ? "SMALLINT" : "TINYINT";
                r.kind = ColumnMeta.Kind.INT;
                break;
            case "smallint":
                r.dmType = unsigned ? "INT" : "SMALLINT";
                r.kind = ColumnMeta.Kind.INT;
                break;
            case "mediumint":
                r.dmType = unsigned ? "INT" : "INT";
                r.kind = ColumnMeta.Kind.INT;
                break;
            case "int":
            case "integer":
                r.dmType = unsigned ? "BIGINT" : "INT";
                r.kind = unsigned ? ColumnMeta.Kind.LONG : ColumnMeta.Kind.INT;
                break;
            case "bigint":
                r.dmType = unsigned ? "DECIMAL(20,0)" : "BIGINT";
                r.kind = unsigned ? ColumnMeta.Kind.DECIMAL : ColumnMeta.Kind.LONG;
                break;
            case "decimal":
            case "dec":
            case "numeric": {
                long[] ps = parseArgs(args);
                if (ps.length == 0) {
                    r.dmType = "DECIMAL(10,0)";
                } else {
                    long m = ps[0];
                    long n = ps.length > 1 ? ps[1] : 0;
                    if (m > 38) {
                        r.dmType = "DECIMAL(38," + Math.min(n, 38) + ")";
                        r.warnings.add("DECIMAL 精度 " + m + " 超过达梦上限 38，已映射为 " + r.dmType
                                + "，整数位超长的数据写入时将报错");
                    } else {
                        r.dmType = "DECIMAL(" + m + "," + n + ")";
                    }
                }
                r.kind = ColumnMeta.Kind.DECIMAL;
                break;
            }
            case "float": {
                long[] ps = parseArgs(args);
                if (ps.length >= 2) {
                    r.dmType = "DECIMAL(" + ps[0] + "," + ps[1] + ")";
                    r.kind = ColumnMeta.Kind.DECIMAL;
                    r.warnings.add("MySQL 旧式 FLOAT(M,D) 已映射为 DECIMAL(" + ps[0] + "," + ps[1] + ")");
                } else {
                    r.dmType = "FLOAT";
                    r.kind = ColumnMeta.Kind.FLOAT;
                }
                break;
            }
            case "double":
            case "real": {
                long[] ps = parseArgs(args);
                if (ps.length >= 2) {
                    r.dmType = "DECIMAL(" + ps[0] + "," + ps[1] + ")";
                    r.kind = ColumnMeta.Kind.DECIMAL;
                    r.warnings.add("MySQL 旧式 DOUBLE(M,D) 已映射为 DECIMAL(" + ps[0] + "," + ps[1] + ")");
                } else {
                    r.dmType = "DOUBLE";
                    r.kind = ColumnMeta.Kind.DOUBLE;
                }
                break;
            }
            case "bool":
            case "boolean":
                r.dmType = "TINYINT";
                r.kind = ColumnMeta.Kind.INT;
                break;
            case "bit": {
                long n = args.isEmpty() ? 1 : parseArgs(args)[0];
                if (n <= 1) {
                    r.dmType = "BIT";
                    r.kind = ColumnMeta.Kind.BIT1;
                } else {
                    r.dmType = "BINARY(" + ((n + 7) / 8) + ")";
                    r.kind = ColumnMeta.Kind.BYTES;
                    r.warnings.add("BIT(" + n + ") 已映射为 BINARY(" + ((n + 7) / 8) + ")");
                }
                break;
            }
            case "char": {
                long n = args.isEmpty() ? 1 : parseArgs(args)[0];
                long bytes = Math.round(n * factor);
                if (bytes <= DM_CHAR_MAX) {
                    r.dmType = "CHAR(" + bytes + ")";
                    r.kind = ColumnMeta.Kind.STRING;
                } else if (bytes <= DM_VARCHAR_MAX) {
                    r.dmType = "VARCHAR(" + bytes + ")";
                    r.kind = ColumnMeta.Kind.STRING;
                    r.warnings.add("CHAR(" + n + ") 按 ×" + factor + " 扩展后超出达梦 CHAR 上限 "
                            + DM_CHAR_MAX + " 字节，已映射为 " + r.dmType);
                } else {
                    r.dmType = "TEXT";
                    r.kind = ColumnMeta.Kind.STRING;
                    r.warnings.add("CHAR(" + n + ") 过长，已映射为 TEXT");
                }
                break;
            }
            case "varchar": {
                long n = args.isEmpty() ? 255 : parseArgs(args)[0];
                long bytes = Math.round(n * factor);
                if (bytes <= DM_VARCHAR_MAX) {
                    r.dmType = "VARCHAR(" + bytes + ")";
                    r.kind = ColumnMeta.Kind.STRING;
                } else {
                    r.dmType = "TEXT";
                    r.kind = ColumnMeta.Kind.STRING;
                    r.warnings.add("VARCHAR(" + n + ") 按 ×" + factor + " 扩展后为 " + bytes
                            + " 字节，超过达梦 VARCHAR 上限 " + DM_VARCHAR_MAX + "，已映射为 TEXT");
                }
                break;
            }
            case "tinytext":
            case "text":
            case "mediumtext":
            case "longtext":
                r.dmType = "TEXT";
                r.kind = ColumnMeta.Kind.STRING;
                break;
            case "binary": {
                long n = args.isEmpty() ? 1 : parseArgs(args)[0];
                r.dmType = "BINARY(" + n + ")";
                r.kind = ColumnMeta.Kind.BYTES;
                break;
            }
            case "varbinary": {
                long n = args.isEmpty() ? 255 : parseArgs(args)[0];
                if (n <= DM_VARCHAR_MAX) {
                    r.dmType = "VARBINARY(" + n + ")";
                } else {
                    r.dmType = "BLOB";
                    r.warnings.add("VARBINARY(" + n + ") 超过达梦 VARBINARY 上限，已映射为 BLOB");
                }
                r.kind = ColumnMeta.Kind.BYTES;
                break;
            }
            case "tinyblob":
            case "blob":
            case "mediumblob":
            case "longblob":
                r.dmType = "BLOB";
                r.kind = ColumnMeta.Kind.BYTES;
                break;
            case "date":
                r.dmType = "DATE";
                r.kind = ColumnMeta.Kind.DATE;
                break;
            case "time": {
                long f = args.isEmpty() ? 0 : parseArgs(args)[0];
                r.dmType = f > 0 ? "TIME(" + f + ")" : "TIME";
                r.kind = ColumnMeta.Kind.TIME;
                r.warnings.add("MySQL TIME 允许 ±838 小时及负值，达梦 TIME 仅支持 00:00:00 ~ 23:59:59.999999，"
                        + "超范围数据写入时将报错");
                break;
            }
            case "datetime":
            case "timestamp": {
                long f = args.isEmpty() ? 0 : parseArgs(args)[0];
                r.dmType = f > 0 ? "TIMESTAMP(" + f + ")" : "TIMESTAMP";
                r.kind = ColumnMeta.Kind.TIMESTAMP;
                if (head.equals("timestamp")) {
                    r.warnings.add("MySQL TIMESTAMP 具有会话时区转换语义，达梦 TIMESTAMP 为无时区存储，"
                            + "请确保迁移连接与业务会话使用相同时区");
                }
                break;
            }
            case "year":
                r.dmType = "SMALLINT";
                r.kind = ColumnMeta.Kind.YEAR_INT;
                break;
            case "json":
                r.dmType = "TEXT";
                r.kind = ColumnMeta.Kind.STRING;
                break;
            case "enum": {
                List<String> vals = parseQuotedList(args);
                long max = 0;
                for (String v : vals) {
                    max = Math.max(max, v.length());
                }
                long bytes = Math.max(Math.round(max * factor), 4);
                r.dmType = bytes > DM_VARCHAR_MAX ? "TEXT" : "VARCHAR(" + bytes + ")";
                r.kind = ColumnMeta.Kind.STRING;
                break;
            }
            case "set": {
                List<String> vals = parseQuotedList(args);
                long total = 0;
                for (String v : vals) {
                    total += v.length() + 2;   // 值 + 分隔符余量
                }
                long bytes = Math.max(Math.round(total * factor), 4);
                r.dmType = bytes > DM_VARCHAR_MAX ? "TEXT" : "VARCHAR(" + bytes + ")";
                r.kind = ColumnMeta.Kind.STRING;
                break;
            }
            case "geometry":
            case "point":
            case "linestring":
            case "polygon":
            case "multipoint":
            case "multilinestring":
            case "multipolygon":
            case "geomcollection":
            case "geometrycollection":
                r.warnings.add("不支持的空间类型 [" + c.columnType + "]，无法映射到达梦");
                return r;
            default:
                r.warnings.add("未知 MySQL 类型 [" + c.columnType + "]，无法映射到达梦");
                return r;
        }
        return r;
    }

    /**
     * 将 MySQL 列默认值转换为达梦 DEFAULT 字面量；无法安全转换时返回 null 并记录告警。
     */
    static String buildDefaultLiteral(ColumnMeta c, List<String> warnsOut) {
        String d = c.defaultValue;
        if (d == null || c.identity || c.kind == null) {
            return null;
        }
        String trimmed = d.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // MySQL 5.7/MariaDB 对 SQL NULL 默认值可能返回字符串 "NULL"，统一视为无默认值
        if (trimmed.equalsIgnoreCase("null")) {
            return null;
        }
        String low = trimmed.toLowerCase(Locale.ROOT);

        if (low.equals("current_timestamp") || low.equals("current_timestamp()")
                || low.startsWith("current_timestamp(") || low.equals("now()") || low.equals("now")) {
            if (c.kind == ColumnMeta.Kind.TIMESTAMP) {
                return "CURRENT_TIMESTAMP";
            }
            warnsOut.add("默认值 [" + d + "] 仅对时间戳列有意义，已忽略");
            return null;
        }
        // MySQL 8 表达式默认值形如 (expr)
        if (trimmed.startsWith("(")) {
            warnsOut.add("表达式默认值 [" + trimmed + "] 无法自动转换，已忽略，请手工处理");
            return null;
        }

        switch (c.kind) {
            case INT:
            case LONG:
            case YEAR_INT:
            case DECIMAL:
            case FLOAT:
            case DOUBLE:
                try {
                    new BigDecimal(trimmed);
                    return trimmed;
                } catch (Exception e) {
                    warnsOut.add("数值默认值 [" + d + "] 无法解析，已忽略");
                    return null;
                }
            case BIT1: {
                String v = trimmed;
                if (v.length() >= 3 && v.toLowerCase(Locale.ROOT).startsWith("b'") && v.endsWith("'")) {
                    v = v.substring(2, v.length() - 1);
                }
                try {
                    int i = Integer.parseInt(v.trim());
                    if (i == 0 || i == 1) {
                        return String.valueOf(i);
                    }
                } catch (Exception ignored) {
                }
                warnsOut.add("BIT 默认值 [" + d + "] 无法解析，已忽略");
                return null;
            }
            case STRING:
            case DATE:
            case TIME:
            case TIMESTAMP: {
                String v = trimmed;
                if (v.startsWith("_binary ")) {
                    v = v.substring("_binary ".length());
                }
                // MySQL 8 的字符串默认值带引号（内部引号转义为 ''），5.7 不带
                if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
                    v = v.substring(1, v.length() - 1).replace("''", "'");
                }
                if (c.kind != ColumnMeta.Kind.STRING && v.toLowerCase(Locale.ROOT).startsWith("0000-00-00")) {
                    warnsOut.add("零日期默认值 [" + v + "] 达梦不支持，已忽略，请确认数据后手工处理");
                    return null;
                }
                return "'" + v.replace("'", "''") + "'";
            }
            default:
                warnsOut.add("该类型的默认值 [" + d + "] 不支持自动迁移，已忽略");
                return null;
        }
    }

    /** 解析 "10,2" 形式的参数列表；解析失败返回空数组。 */
    private static long[] parseArgs(String args) {
        if (args == null || args.trim().isEmpty()) {
            return new long[0];
        }
        String[] parts = args.split(",");
        long[] out = new long[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Long.parseLong(parts[i].trim());
            }
            return out;
        } catch (Exception e) {
            return new long[0];
        }
    }

    /** 解析 enum('a','b''c') 中的字符串字面量列表。 */
    static List<String> parseQuotedList(String args) {
        List<String> out = new ArrayList<>();
        if (args == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (inQuote) {
                if (ch == '\'') {
                    if (i + 1 < args.length() && args.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inQuote = false;
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '\'') {
                inQuote = true;
            }
            // 逗号等分隔符忽略
        }
        return out;
    }
}
