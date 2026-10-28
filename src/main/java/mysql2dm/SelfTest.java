package mysql2dm;

import java.util.ArrayList;
import java.util.List;

/** 类型映射内置自测（--selftest），验证核心映射规则，无需数据库连接。 */
final class SelfTest {

    private static int failures = 0;

    private SelfTest() {
    }

    static int run() {
        // int 族
        expect("int", "INT", ColumnMeta.Kind.INT, 4);
        expect("int unsigned", "BIGINT", ColumnMeta.Kind.LONG, 4);
        expect("int(11)", "INT", ColumnMeta.Kind.INT, 4);
        expect("tinyint(1)", "TINYINT", ColumnMeta.Kind.INT, 4);
        expect("tinyint unsigned", "SMALLINT", ColumnMeta.Kind.INT, 4);
        expect("smallint unsigned", "INT", ColumnMeta.Kind.INT, 4);
        expect("mediumint", "INT", ColumnMeta.Kind.INT, 4);
        expect("mediumint unsigned", "INT", ColumnMeta.Kind.INT, 4);
        expect("bigint", "BIGINT", ColumnMeta.Kind.LONG, 4);
        expect("bigint unsigned", "DECIMAL(20,0)", ColumnMeta.Kind.DECIMAL, 4);
        // 小数
        expect("decimal(10,2)", "DECIMAL(10,2)", ColumnMeta.Kind.DECIMAL, 4);
        expectWarn("decimal(65,30)", "DECIMAL(38,30)", 4);
        expect("double", "DOUBLE", ColumnMeta.Kind.DOUBLE, 4);
        expect("float(10,2)", "DECIMAL(10,2)", ColumnMeta.Kind.DECIMAL, 4);
        // 字符串
        expect("varchar(10)", "VARCHAR(40)", ColumnMeta.Kind.STRING, 4);
        expect("varchar(10)", "VARCHAR(10)", ColumnMeta.Kind.STRING, 1);
        expect("varchar(3000)", "TEXT", ColumnMeta.Kind.STRING, 4);
        expect("char(255)", "CHAR(1020)", ColumnMeta.Kind.STRING, 4);
        expectWarn("char(600)", "VARCHAR(2400)", 4);   // 600*4 > 2000，CHAR 转 VARCHAR
        expect("char(10)", "CHAR(40)", ColumnMeta.Kind.STRING, 4);
        expect("text", "TEXT", ColumnMeta.Kind.STRING, 4);
        expect("json", "TEXT", ColumnMeta.Kind.STRING, 4);
        expect("enum('a','bcd')", "VARCHAR(12)", ColumnMeta.Kind.STRING, 4);
        expect("set('a','bb')", "VARCHAR(28)", ColumnMeta.Kind.STRING, 4);
        // 二进制
        expect("binary(16)", "BINARY(16)", ColumnMeta.Kind.BYTES, 4);
        expect("varbinary(100)", "VARBINARY(100)", ColumnMeta.Kind.BYTES, 4);
        expect("blob", "BLOB", ColumnMeta.Kind.BYTES, 4);
        // 时间
        expect("date", "DATE", ColumnMeta.Kind.DATE, 4);
        expect("datetime(6)", "TIMESTAMP(6)", ColumnMeta.Kind.TIMESTAMP, 4);
        expect("timestamp", "TIMESTAMP", ColumnMeta.Kind.TIMESTAMP, 4);
        expect("time(3)", "TIME(3)", ColumnMeta.Kind.TIME, 4);
        expect("year", "SMALLINT", ColumnMeta.Kind.YEAR_INT, 4);
        // 位
        expect("bit(1)", "BIT", ColumnMeta.Kind.BIT1, 4);
        expect("bit(9)", "BINARY(2)", ColumnMeta.Kind.BYTES, 4);
        // 不支持
        expectUnsupported("geometry", 4);
        expectUnsupported("point", 4);

        // ---- 默认值转换 ----
        defaultExpect(kind(ColumnMeta.Kind.STRING), "'abc'", "'abc'");
        defaultExpect(kind(ColumnMeta.Kind.STRING), "''", "''");
        defaultExpect(kind(ColumnMeta.Kind.INT), "5", "5");
        defaultExpect(kind(ColumnMeta.Kind.INT), "-1", "-1");
        defaultExpect(kind(ColumnMeta.Kind.TIMESTAMP), "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP");
        defaultExpect(kind(ColumnMeta.Kind.TIMESTAMP), "current_timestamp()", "CURRENT_TIMESTAMP");
        defaultExpect(kind(ColumnMeta.Kind.BIT1), "b'1'", "1");
        defaultExpect(kind(ColumnMeta.Kind.TIMESTAMP), "'0000-00-00 00:00:00'", null);
        defaultExpect(kind(ColumnMeta.Kind.INT), "(now())", null);
        defaultExpect(kind(ColumnMeta.Kind.STRING), "'it''s'", "'it''s'");

        if (failures == 0) {
            System.out.println("[自测] 全部通过 ✓");
            return 0;
        }
        System.out.println("[自测] 失败 " + failures + " 项");
        return 3;
    }

    private static ColumnMeta col(String columnType) {
        ColumnMeta c = new ColumnMeta();
        c.table = "t";
        c.name = "c";
        c.columnType = columnType;
        c.dataType = columnType.trim().toLowerCase().split("\\s|\\(")[0];
        return c;
    }

    private static ColumnMeta kind(ColumnMeta.Kind k) {
        ColumnMeta c = new ColumnMeta();
        c.kind = k;
        return c;
    }

    private static void expect(String columnType, String dmType, ColumnMeta.Kind kind, double factor) {
        TypeMapper.Result r = TypeMapper.map(col(columnType), factor);
        if (!dmType.equals(r.dmType) || kind != r.kind) {
            fail(String.format("类型映射 %s(×%s): 期望 %s/%s, 实际 %s/%s",
                    columnType, factor, dmType, kind, r.dmType, r.kind));
        }
    }

    private static void expectWarn(String columnType, String dmType, double factor) {
        TypeMapper.Result r = TypeMapper.map(col(columnType), factor);
        if (!dmType.equals(r.dmType) || r.warnings.isEmpty()) {
            fail(String.format("类型映射(需告警) %s: 期望 %s+告警, 实际 %s, 告警数 %d",
                    columnType, dmType, r.dmType, r.warnings.size()));
        }
    }

    private static void expectUnsupported(String columnType, double factor) {
        TypeMapper.Result r = TypeMapper.map(col(columnType), factor);
        if (r.dmType != null || r.warnings.isEmpty()) {
            fail(String.format("类型映射(应不支持) %s: 实际 %s, 告警数 %d", columnType, r.dmType, r.warnings.size()));
        }
    }

    private static void defaultExpect(ColumnMeta c, String mysqlDefault, String expected) {
        c.defaultValue = mysqlDefault;
        List<String> warns = new ArrayList<>();
        String actual = TypeMapper.buildDefaultLiteral(c, warns);
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            fail(String.format("默认值 %s (kind=%s): 期望 %s, 实际 %s, 告警 %s",
                    mysqlDefault, c.kind, expected, actual, warns));
        }
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("[自测][失败] " + msg);
    }
}
