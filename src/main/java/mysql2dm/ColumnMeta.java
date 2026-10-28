package mysql2dm;

/** 列元数据（MySQL information_schema.COLUMNS + 解析结果）。 */
final class ColumnMeta {

    /** 数据搬运时使用的 JDBC 读写方式 */
    enum Kind {
        INT, LONG, DECIMAL, FLOAT, DOUBLE, STRING,
        DATE, TIME, TIMESTAMP, YEAR_INT, BIT1, BYTES
    }

    String table;
    String name;
    int ordinal;

    String dataType;      // 基础类型，如 int
    String columnType;    // 完整类型，如 int unsigned / varchar(255) / enum('a','b')
    boolean nullable = true;
    String defaultValue;  // information_schema 原始默认值
    String extra;         // auto_increment / on update ... / GENERATED 等
    String comment;

    boolean autoIncrement;
    boolean onUpdateTimestamp;
    boolean generated;

    // ---- 解析结果 ----
    String dmType;        // 达梦类型，如 VARCHAR(100)、TEXT
    Kind kind;            // 数据搬运方式
    boolean identity;     // 映射为达梦 IDENTITY 列
    boolean skipped;      // 因不可支持而被跳过（仅在 ignoreUnsupported=true 时）
    String skipReason;

    boolean isMappable() {
        return !skipped;
    }
}
