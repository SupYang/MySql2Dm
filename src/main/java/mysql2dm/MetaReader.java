package mysql2dm;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 从 MySQL information_schema 读取表、列、主键、索引、外键元数据并完成类型解析。 */
final class MetaReader {

    private final Connection mc;
    private final String db;
    private final Config cfg;

    MetaReader(Connection mc, String db, Config cfg) {
        this.mc = mc;
        this.db = db;
        this.cfg = cfg;
    }

    List<TableMeta> readAll() throws SQLException {
        Map<String, TableMeta> byName = new LinkedHashMap<>();

        // ---- 表清单（仅基表） ----
        try (PreparedStatement ps = mc.prepareStatement(
                "SELECT TABLE_NAME, TABLE_COMMENT, AUTO_INCREMENT, CREATE_OPTIONS"
                        + " FROM information_schema.TABLES"
                        + " WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'"
                        + " ORDER BY TABLE_NAME")) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableMeta t = new TableMeta();
                    t.name = rs.getString(1);
                    t.comment = rs.getString(2);
                    long ai = rs.getLong(3);
                    t.autoIncrement = rs.wasNull() || ai <= 0 ? null : ai;
                    String opts = rs.getString(4);
                    t.partitioned = opts != null && opts.toLowerCase(Locale.ROOT).contains("partitioned");
                    checkName(t.name, "表");
                    byName.put(t.name, t);
                }
            }
        }
        if (byName.isEmpty()) {
            Log.warn("源库 [%s] 中未发现任何基表", db);
        }

        // ---- 视图提示 ----
        int views = 0;
        try (PreparedStatement ps = mc.prepareStatement(
                "SELECT TABLE_NAME FROM information_schema.TABLES"
                        + " WHERE TABLE_SCHEMA = ? AND TABLE_TYPE <> 'BASE TABLE'")) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    views++;
                    Log.warn("跳过非基表对象: %s", rs.getString(1));
                }
            }
        }
        if (views == 0) {
            Log.info("源库无视图对象");
        }

        // ---- 列 ----
        try (PreparedStatement ps = mc.prepareStatement(
                "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE,"
                        + " COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT"
                        + " FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = ?"
                        + " ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableMeta t = byName.get(rs.getString(1));
                    if (t == null) {
                        continue;
                    }
                    ColumnMeta c = new ColumnMeta();
                    c.table = t.name;
                    c.name = rs.getString(2);
                    c.ordinal = rs.getInt(3);
                    c.columnType = rs.getString(4);
                    c.dataType = c.columnType == null ? null
                            : c.columnType.trim().toLowerCase(Locale.ROOT).split("\\s|\\(")[0];
                    c.nullable = !"NO".equalsIgnoreCase(rs.getString(5));
                    c.defaultValue = rs.getString(6);
                    c.extra = rs.getString(7);
                    c.comment = rs.getString(8);
                    String extraLow = c.extra == null ? "" : c.extra.toLowerCase(Locale.ROOT);
                    c.autoIncrement = extraLow.contains("auto_increment");
                    c.onUpdateTimestamp = extraLow.contains("on update current_timestamp");
                    c.generated = extraLow.contains("generated");
                    checkName(c.name, "列");
                    t.columns.add(c);
                }
            }
        }

        // ---- 主键 ----
        Map<String, List<String>> pks = new HashMap<>();
        try (PreparedStatement ps = mc.prepareStatement(
                "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = ? AND CONSTRAINT_NAME = 'PRIMARY'")) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pks.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                }
            }
        }

        // ---- 索引 ----
        try (PreparedStatement ps = mc.prepareStatement(
                "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, COLLATION, INDEX_TYPE, SUB_PART"
                        + " FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = ? AND INDEX_NAME <> 'PRIMARY'"
                        + " ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX")) {
            ps.setString(1, db);
            IndexMeta cur = null;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String idxName = rs.getString(2);
                    String type = rs.getString(7);
                    TableMeta t = byName.get(table);
                    if (t == null) {
                        continue;
                    }
                    if (type != null && (type.equalsIgnoreCase("FULLTEXT") || type.equalsIgnoreCase("SPATIAL"))) {
                        Log.warn("[%s] 索引 %s 为 %s 类型，达梦不支持，已跳过", table, idxName, type);
                        continue;
                    }
                    if (cur == null || !cur.name.equals(idxName) || !cur.table.equals(table)) {
                        cur = new IndexMeta();
                        cur.table = table;
                        cur.name = idxName;
                        cur.unique = rs.getInt(3) == 0;
                        cur.type = type;
                        t.indexes.add(cur);
                    }
                    String col = rs.getString(5);
                    long subPart = rs.getLong(8);
                    boolean subPartNull = rs.wasNull();
                    ColumnMeta cm = t.column(col);
                    if (cm != null && cm.skipped) {
                        Log.warn("[%s] 索引 %s 引用了被跳过的列 %s，该索引将不迁移", table, idxName, col);
                        t.indexes.remove(cur);
                        cur = null;
                        continue;
                    }
                    if (!subPartNull && subPart > 0 && !cur.prefixWarned) {
                        cur.prefixWarned = true;
                        Log.warn("[%s] 索引 %s 为前缀索引，达梦不支持前缀，将按完整列创建", table, idxName);
                    }
                    cur.columns.add(col);
                    cur.desc.add("D".equalsIgnoreCase(rs.getString(6)));
                }
            }
        }

        // ---- 外键 ----
        try (PreparedStatement ps = mc.prepareStatement(
                "SELECT rc.TABLE_NAME, rc.CONSTRAINT_NAME, rc.REFERENCED_TABLE_NAME,"
                        + " rc.UPDATE_RULE, rc.DELETE_RULE, kcu.COLUMN_NAME, kcu.REFERENCED_COLUMN_NAME"
                        + " FROM information_schema.REFERENTIAL_CONSTRAINTS rc"
                        + " JOIN information_schema.KEY_COLUMN_USAGE kcu"
                        + "   ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA"
                        + "  AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME"
                        + "  AND kcu.TABLE_NAME = rc.TABLE_NAME"
                        + " WHERE rc.CONSTRAINT_SCHEMA = ?"
                        + " ORDER BY rc.TABLE_NAME, rc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION")) {
            ps.setString(1, db);
            FkMeta cur = null;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String fkName = rs.getString(2);
                    TableMeta t = byName.get(table);
                    if (t == null) {
                        continue;
                    }
                    if (cur == null || !cur.name.equals(fkName) || !cur.table.equals(table)) {
                        cur = new FkMeta();
                        cur.table = table;
                        cur.name = fkName;
                        cur.refTable = rs.getString(3);
                        cur.onUpdate = rs.getString(4);
                        cur.onDelete = rs.getString(5);
                        t.foreignKeys.add(cur);
                    }
                    cur.columns.add(rs.getString(6));
                    cur.refColumns.add(rs.getString(7));
                }
            }
        }

        // ---- 应用类型映射 ----
        List<TableMeta> out = new ArrayList<>(byName.values());
        for (TableMeta t : out) {
            List<String> pk = pks.get(t.name);
            if (pk != null) {
                t.pkColumns.addAll(pk);
            }
            for (ColumnMeta c : t.columns) {
                applyMapping(t, c);
            }
            if (t.partitioned) {
                Log.warn("[%s] 源表为分区表，将在达梦中按普通表创建（数据完整迁移）", t.name);
            }
        }
        return out;
    }

    private void applyMapping(TableMeta t, ColumnMeta c) {
        String full = t.name + "." + c.name;

        if (c.generated) {
            if (!cfg.ignoreUnsupported) {
                throw new MigrationException("[" + full + "] 为生成列（GENERATED），达梦表达式语法不同，无法自动迁移。"
                        + "如确认可丢弃该列数据，请配置 ignoreUnsupported=true 后重试（仅应针对虚拟列）。");
            }
            c.skipped = true;
            c.skipReason = "生成列";
            Log.warn("[%s] 生成列已跳过（ignoreUnsupported=true），该列数据不会迁移", full);
            return;
        }

        TypeMapper.Result r = TypeMapper.map(c, cfg.varcharExpandFactor);
        for (String w : r.warnings) {
            Log.warn("[%s] %s", full, w);
        }
        if (r.dmType == null) {
            if (!cfg.ignoreUnsupported) {
                throw new MigrationException("[" + full + "] 的类型 [" + c.columnType + "] 无法映射到达梦，迁移中止。"
                        + "如确认可丢弃该列数据，请配置 ignoreUnsupported=true 后重试。");
            }
            c.skipped = true;
            c.skipReason = "类型 " + c.columnType + " 不支持";
            Log.warn("[%s] 列已跳过（ignoreUnsupported=true），该列数据不会迁移", full);
            return;
        }
        c.dmType = r.dmType;
        c.kind = r.kind;
        c.identity = c.autoIncrement;

        if (c.onUpdateTimestamp) {
            Log.warn("[%s] MySQL 的 ON UPDATE CURRENT_TIMESTAMP 达梦不支持，未迁移（应用层需自行处理更新时间）", full);
        }
    }

    private static void checkName(String name, String what) {
        if (name == null || name.isEmpty()) {
            throw new MigrationException(what + "名为空，无法迁移");
        }
        if (name.length() > 128) {
            throw new MigrationException(what + "名 [" + name + "] 超过达梦 128 字符上限，无法迁移");
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20) {
                throw new MigrationException(what + "名 [" + name + "] 含控制字符，无法迁移");
            }
        }
    }
}
