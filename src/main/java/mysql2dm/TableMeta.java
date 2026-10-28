package mysql2dm;

import java.util.ArrayList;
import java.util.List;

/** 表元数据。 */
final class TableMeta {

    String name;
    String comment;
    boolean partitioned;
    Long autoIncrement;   // MySQL 下一自增值；null 表示无自增或为 0

    final List<ColumnMeta> columns = new ArrayList<>();
    final List<String> pkColumns = new ArrayList<>();
    final List<IndexMeta> indexes = new ArrayList<>();
    final List<FkMeta> foreignKeys = new ArrayList<>();

    List<ColumnMeta> mappableColumns() {
        List<ColumnMeta> out = new ArrayList<>();
        for (ColumnMeta c : columns) {
            if (!c.skipped) {
                out.add(c);
            }
        }
        return out;
    }

    ColumnMeta column(String name) {
        for (ColumnMeta c : columns) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }
}
