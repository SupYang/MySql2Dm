package mysql2dm;

import java.util.ArrayList;
import java.util.List;

/** 索引元数据（不含主键）。 */
final class IndexMeta {

    String table;
    String name;                // MySQL 索引名（表内唯一）
    String finalName;           // 解决达梦 schema 内重名后的最终索引名
    boolean unique;
    String type;                // BTREE / HASH / FULLTEXT / SPATIAL
    final List<String> columns = new ArrayList<>();
    final List<Boolean> desc = new ArrayList<>();   // 与 columns 一一对应，true=DESC
    boolean prefixWarned;       // 存在前缀索引（col(10)），已告警
}
