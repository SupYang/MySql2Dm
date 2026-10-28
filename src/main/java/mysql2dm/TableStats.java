package mysql2dm;

/** 单表迁移统计。 */
final class TableStats {

    static final String PENDING = "待迁移";
    static final String CREATED = "建表完成";
    static final String SKIP_EXISTS = "跳过建表";
    static final String LOADED = "成功";
    static final String PARTIAL = "部分成功";
    static final String FAILED = "失败";
    static final String STRUCT_ONLY = "仅结构";
    static final String DRY_RUN = "模拟";

    final String table;
    String status = PENDING;
    long inserted = -1;
    long skippedRows = 0;
    int diagCount = 0;
    long srcCount = -1;
    long dstCount = -1;
    long ms = 0;
    String error;

    TableStats(String table) {
        this.table = table;
    }
}
