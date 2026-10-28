package mysql2dm;

import java.util.List;

/** 达梦 DDL 生成。所有标识符一律加双引号，保证大小写原样保留。 */
final class DdlBuilder {

    private DdlBuilder() {
    }

    /** 标识符加双引号（达梦：加引号即保留原始大小写）。 */
    static String q(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /** schema 限定名 */
    static String qualify(String schema, String table) {
        return (schema == null || schema.trim().isEmpty())
                ? q(table) : q(schema.trim()) + "." + q(table);
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    /** 约束/索引名超长时截断并加哈希后缀，保证 ≤128 且尽量唯一。 */
    static String fitName(String name) {
        if (name.length() <= 128) {
            return name;
        }
        String h = Integer.toHexString(name.hashCode());
        return name.substring(0, 128 - h.length() - 1) + "_" + h;
    }

    static String createTable(String schema, TableMeta t) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(qualify(schema, t.name)).append(" (\n");
        List<ColumnMeta> cols = t.mappableColumns();
        for (int i = 0; i < cols.size(); i++) {
            ColumnMeta c = cols.get(i);
            sb.append("    ").append(q(c.name)).append(' ').append(c.dmType);
            if (c.identity) {
                sb.append(" IDENTITY(").append(identitySeed(t)).append(",1)");
            }
            java.util.List<String> defWarns = new java.util.ArrayList<>();
            String def = TypeMapper.buildDefaultLiteral(c, defWarns);
            for (String w : defWarns) {
                Log.warn("[%s.%s] %s", t.name, c.name, w);
            }
            if (def != null) {
                sb.append(" DEFAULT ").append(def);
            }
            if (!c.nullable) {
                sb.append(" NOT NULL");
            }
            if (i < cols.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        if (!t.pkColumns.isEmpty()) {
            sb.append("    ,CONSTRAINT ").append(q(fitName("pk_" + t.name))).append(" PRIMARY KEY (");
            for (int i = 0; i < t.pkColumns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(q(t.pkColumns.get(i)));
            }
            sb.append(")\n");
        }
        sb.append(")");
        return sb.toString();
    }

    /** 表与列注释，返回可逐条执行的语句列表 */
    static List<String> buildComments(String schema, TableMeta t) {
        List<String> out = new java.util.ArrayList<>();
        if (t.comment != null && !t.comment.trim().isEmpty()) {
            out.add("COMMENT ON TABLE " + qualify(schema, t.name)
                    + " IS '" + esc(t.comment) + "'");
        }
        for (ColumnMeta c : t.mappableColumns()) {
            if (c.comment != null && !c.comment.trim().isEmpty()) {
                out.add("COMMENT ON COLUMN " + qualify(schema, t.name) + "." + q(c.name)
                        + " IS '" + esc(c.comment) + "'");
            }
        }
        return out;
    }

    static String createIndex(String schema, TableMeta t, IndexMeta idx) {
        StringBuilder sb = new StringBuilder();
        sb.append(idx.unique ? "CREATE UNIQUE INDEX " : "CREATE INDEX ")
                .append(q(fitName(idx.finalName != null ? idx.finalName : idx.name)))
                .append(" ON ").append(qualify(schema, t.name)).append(" (");
        for (int i = 0; i < idx.columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(q(idx.columns.get(i)));
            if (idx.desc.get(i)) {
                sb.append(" DESC");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    static String createForeignKey(String schema, FkMeta fk) {
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(qualify(schema, fk.table))
                .append(" ADD CONSTRAINT ").append(q(fitName(fk.name)))
                .append(" FOREIGN KEY (");
        appendQuotedList(sb, fk.columns);
        sb.append(") REFERENCES ").append(qualify(schema, fk.refTable)).append(" (");
        appendQuotedList(sb, fk.refColumns);
        sb.append(")");
        if (fk.onDelete != null && !"NO ACTION".equalsIgnoreCase(fk.onDelete)) {
            sb.append(" ON DELETE ").append(fk.onDelete);
        }
        if (fk.onUpdate != null && !"NO ACTION".equalsIgnoreCase(fk.onUpdate)) {
            sb.append(" ON UPDATE ").append(fk.onUpdate);
        }
        return sb.toString();
    }

    static String dropTable(String schema, String table) {
        return "DROP TABLE " + qualify(schema, table);
    }

    static String truncateTable(String schema, String table) {
        return "TRUNCATE TABLE " + qualify(schema, table);
    }

    static String identityInsertOn(String schema, String table) {
        return "SET IDENTITY_INSERT " + qualify(schema, table) + " ON";
    }

    static String identityInsertOff(String schema, String table) {
        return "SET IDENTITY_INSERT " + qualify(schema, table) + " OFF";
    }

    private static void appendQuotedList(StringBuilder sb, List<String> ids) {
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(q(ids.get(i)));
        }
    }

    /** IDENTITY 种子取 MySQL 下一自增值（≥1），保证迁移后自增不与已有主键冲突。 */
    private static long identitySeed(TableMeta t) {
        return (t.autoIncrement != null && t.autoIncrement > 1) ? t.autoIncrement : 1L;
    }
}
