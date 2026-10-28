package mysql2dm;

import java.util.ArrayList;
import java.util.List;

/** 外键元数据。 */
final class FkMeta {

    String table;
    String name;
    String refTable;
    String onUpdate;   // CASCADE / RESTRICT / NO ACTION / SET NULL / SET DEFAULT
    String onDelete;
    final List<String> columns = new ArrayList<>();
    final List<String> refColumns = new ArrayList<>();
}
