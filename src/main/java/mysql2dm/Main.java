package mysql2dm;

import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** MySQL → 达梦(DM8) 表结构与数据迁移工具。 */
public final class Main {

    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Exception e) {
            Log.error("迁移中止: %s", e.getMessage());
            if (e.getCause() != null) {
                Log.error("根因: %s", e.getCause().getMessage());
            }
            code = 2;
        }
        System.exit(code);
    }

    private static int run(String[] args) throws Exception {
        Config cfg;
        try {
            cfg = Config.load(args);
        } catch (IllegalArgumentException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.err.println("使用 --help 查看用法");
            return 2;
        }

        Log.initFile(cfg.logFile);
        Log.info("MySQL → 达梦(DM8) 迁移工具 v%s", VERSION);
        if (cfg.selfTest) {
            Log.info("运行内置类型映射自测…");
            return SelfTest.run();
        }
        cfg.dump();

        long t0 = System.currentTimeMillis();

        // ---- 连接 ----
        Class.forName("com.mysql.cj.jdbc.Driver");
        Class.forName("dm.jdbc.driver.DmDriver");
        String myDb = extractMysqlDb(cfg.mysqlUrl);
        if (myDb == null) {
            Log.error("mysql.url 未包含数据库名（如 jdbc:mysql://127.0.0.1:3306/mydb?...）");
            return 2;
        }
        String myUrl = normalizeMysqlUrl(cfg.mysqlUrl);
        Connection mc;
        Connection dc = null;
        try {
            mc = DriverManager.getConnection(myUrl, cfg.mysqlUser, cfg.mysqlPassword);
        } catch (SQLException e) {
            Log.error("连接 MySQL 失败 (%s@%s): %s", cfg.mysqlUser, maskUrlHost(cfg.mysqlUrl), e.getMessage());
            return 2;
        }
        if (!cfg.dryRun) {
            try {
                dc = DriverManager.getConnection(cfg.dmUrl, cfg.dmUser, cfg.dmPassword);
            } catch (SQLException e) {
                closeQuietly(mc);
                Log.error("连接达梦失败 (%s@%s): %s", cfg.dmUser, maskUrlHost(cfg.dmUrl), e.getMessage());
                return 2;
            }
        }
        try {

            Log.info("已连接 MySQL 库 [%s]", myDb);
            if (cfg.dryRun) {
                Log.info("模拟运行：仅连接 MySQL 预览 DDL 与行数，不连接达梦、不执行任何写入");
            } else if (!checkDmCaseSensitive(dc, cfg)) {
                return 2;
            }

            // ---- 元数据 ----
            Log.info("读取 MySQL 元数据…");
            MetaReader reader = new MetaReader(mc, myDb, cfg);
            List<TableMeta> all = reader.readAll();
            List<TableMeta> selected = selectTables(all, cfg);
            if (selected.isEmpty()) {
                Log.error("没有可迁移的表");
                return 2;
            }
            Log.info("待迁移表 %d 张: %s", selected.size(), tableNames(selected));
            resolveIndexNames(selected);

            String schema = cfg.dmSchema;
            boolean structurePhase = cfg.mode.equals("all") || cfg.mode.equals("structure");
            boolean dataPhase = cfg.mode.equals("all") || cfg.mode.equals("data");

            Map<String, TableStats> statsMap = new LinkedHashMap<>();
            for (TableMeta t : selected) {
                statsMap.put(t.name, new TableStats(t.name));
            }

            // ---- 阶段一：建表 ----
            if (structurePhase) {
                Log.info("════ 阶段一：表结构迁移（%d 张） ════", selected.size());
                for (TableMeta t : selected) {
                    TableStats st = statsMap.get(t.name);
                    try {
                        String ddl = DdlBuilder.createTable(schema, t);
                        if (cfg.dryRun) {
                            Log.info("[模拟] %s;", ddl);
                            if (cfg.migrateComments) {
                                for (String c : DdlBuilder.buildComments(schema, t)) {
                                    Log.info("[模拟] %s;", c);
                                }
                            }
                            st.status = TableStats.DRY_RUN;
                            continue;
                        }
                        boolean exists = tableExists(dc, schema, t.name);
                        if (exists) {
                            if (cfg.createStrategy.equals("fail")) {
                                throw new MigrationException("目标表 " + DdlBuilder.qualify(schema, t.name)
                                        + " 已存在，且 createStrategy=fail");
                            }
                            if (cfg.createStrategy.equals("skip")) {
                                Log.info("[%s] 目标表已存在，按 createStrategy=skip 跳过建表", t.name);
                                st.status = TableStats.SKIP_EXISTS;
                                continue;
                            }
                            exec(dc, DdlBuilder.dropTable(schema, t.name));
                            Log.info("[%s] 已删除旧表（createStrategy=recreate）", t.name);
                        }
                        exec(dc, ddl);
                        if (cfg.migrateComments) {
                            for (String c : DdlBuilder.buildComments(schema, t)) {
                                exec(dc, c);
                            }
                        }
                        Log.info("[%s] 建表完成 (%d 列%s)", t.name, t.mappableColumns().size(),
                                t.pkColumns.isEmpty() ? "" : ", 含主键");
                        st.status = TableStats.CREATED;
                    } catch (Exception e) {
                        st.status = TableStats.FAILED;
                        st.error = e.getMessage();
                        Log.error("[%s] 建表失败: %s", t.name, e.getMessage());
                    }
                }
            }

            // ---- 阶段二：数据 ----
            if (dataPhase) {
                Log.info("════ 阶段二：数据迁移（%d 张） ════", selected.size());
                for (TableMeta t : selected) {
                    TableStats st = statsMap.get(t.name);
                    if (TableStats.FAILED.equals(st.status)) {
                        continue;
                    }
                    try {
                        if (!cfg.dryRun && !structurePhase) {
                            if (!tableExists(dc, schema, t.name)) {
                                throw new MigrationException("目标表 " + DdlBuilder.qualify(schema, t.name)
                                        + " 不存在，请先完成结构迁移（mode=all 或 structure）");
                            }
                        }
                        st.srcCount = count(mc, qualifyMysql(t.name));
                        if (cfg.dryRun) {
                            Log.info("[模拟] 将流式迁移表 [%s]，源行数 %d", t.name, st.srcCount);
                            st.status = TableStats.DRY_RUN;
                            continue;
                        }
                        DataMigrator.migrate(t, schema, mc, dc, cfg, st);
                        st.dstCount = count(dc, DdlBuilder.qualify(schema, t.name));
                        st.status = st.skippedRows > 0 ? TableStats.PARTIAL : TableStats.LOADED;
                        if (cfg.verifyCounts && st.srcCount != st.dstCount) {
                            st.status = TableStats.FAILED;
                            st.error = String.format("行数校验不一致: 源=%d 目标=%d", st.srcCount, st.dstCount);
                            Log.error("[%s] %s", t.name, st.error);
                        }
                    } catch (Exception e) {
                        st.status = TableStats.FAILED;
                        st.error = e.getMessage();
                        Log.error("[%s] 数据迁移失败: %s", t.name, e.getMessage());
                    }
                }
            }

            // ---- 阶段三：索引与外键（数据之后创建，提速装载且规避依赖顺序） ----
            int secondaryFail = 0;
            if (cfg.migrateIndexes || cfg.migrateForeignKeys) {
                Log.info("════ 阶段三：索引与外键 ════");
                Set<String> selectedNames = new HashSet<>();
                for (TableMeta t : selected) {
                    selectedNames.add(t.name.toLowerCase(Locale.ROOT));
                }
                for (TableMeta t : selected) {
                    if (cfg.migrateIndexes) {
                        for (IndexMeta idx : t.indexes) {
                            String sql = DdlBuilder.createIndex(schema, t, idx);
                            if (cfg.dryRun) {
                                Log.info("[模拟] %s;", sql);
                                continue;
                            }
                            try {
                                Log.info("[%s] 创建索引 %s", t.name, idx.finalName);
                                exec(dc, sql);
                            } catch (SQLException e) {
                                secondaryFail++;
                                Log.error("[%s] 索引 %s 创建失败: %s", t.name, idx.finalName, e.getMessage());
                            }
                        }
                    }
                    if (cfg.migrateForeignKeys) {
                        for (FkMeta fk : t.foreignKeys) {
                            if (!selectedNames.contains(fk.refTable.toLowerCase(Locale.ROOT))) {
                                Log.warn("[%s] 外键 %s 引用的表 %s 不在迁移范围内，已跳过", t.name, fk.name, fk.refTable);
                                continue;
                            }
                            String sql = DdlBuilder.createForeignKey(schema, fk);
                            if (cfg.dryRun) {
                                Log.info("[模拟] %s;", sql);
                                continue;
                            }
                            try {
                                Log.info("[%s] 创建外键 %s", t.name, fk.name);
                                exec(dc, sql);
                            } catch (SQLException e) {
                                secondaryFail++;
                                Log.error("[%s] 外键 %s 创建失败: %s", t.name, fk.name, e.getMessage());
                            }
                        }
                    }
                }
            }

            // ---- 汇总 ----
            return summarize(statsMap, secondaryFail, cfg, t0);
        } finally {
            closeQuietly(mc);
            closeQuietly(dc);
        }
    }

    // ---------- 辅助 ----------

    private static String qualifyMysql(String table) {
        // 源端 COUNT(*)：用反引号限定表名
        return "`" + table.replace("`", "``") + "`";
    }

    private static String maskUrlHost(String url) {
        return url.replaceAll("(?i)(password=)[^&]*", "$1******");
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
            }
        }
    }

    private static List<TableMeta> selectTables(List<TableMeta> all, Config cfg) {
        List<TableMeta> out = new ArrayList<>();
        if (!cfg.tables.isEmpty()) {
            Map<String, TableMeta> byLower = new HashMap<>();
            for (TableMeta t : all) {
                byLower.put(t.name.toLowerCase(Locale.ROOT), t);
            }
            for (String want : cfg.tables) {
                TableMeta t = byLower.get(want.toLowerCase(Locale.ROOT));
                if (t == null) {
                    throw new MigrationException("指定的表 [" + want + "] 在源库中不存在，中止迁移"
                            + "（源库现有表: " + tableNames(all) + "）");
                }
                out.add(t);
            }
        } else {
            out.addAll(all);
        }
        Set<String> excl = new HashSet<>();
        for (String e : cfg.excludeTables) {
            excl.add(e.toLowerCase(Locale.ROOT));
        }
        if (!excl.isEmpty()) {
            out.removeIf(t -> excl.contains(t.name.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    /** MySQL 索引名仅表内唯一；达梦索引名为 schema 内唯一，重名时加表名前缀。 */
    private static void resolveIndexNames(List<TableMeta> tables) {
        Set<String> used = new HashSet<>();
        for (TableMeta t : tables) {
            for (IndexMeta idx : t.indexes) {
                String n = idx.name;
                if (used.contains(n.toLowerCase(Locale.ROOT))) {
                    String base = t.name + "_" + n;
                    String cand = base;
                    int i = 2;
                    while (used.contains(cand.toLowerCase(Locale.ROOT))) {
                        cand = base + "_" + i++;
                    }
                    n = cand;
                    Log.info("[%s] 索引名 %s 与其他表冲突，重命名为 %s", t.name, idx.name, n);
                }
                used.add(n.toLowerCase(Locale.ROOT));
                idx.finalName = n;
            }
        }
    }

    private static boolean tableExists(Connection dc, String schema, String table) {
        try (Statement s = dc.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1 FROM " + DdlBuilder.qualify(schema, table) + " WHERE 1=0")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static long count(Connection conn, String qualified) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection dc, String sql) throws SQLException {
        try (Statement s = dc.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * 校验达梦实例大小写敏感参数。要求 CASE_SENSITIVE=Y，否则无法保留 MySQL 原始大小写。
     */
    private static boolean checkDmCaseSensitive(Connection dc, Config cfg) {
        try (Statement s = dc.createStatement(); ResultSet rs = s.executeQuery("SELECT CASE_SENSITIVE()")) {
            if (rs.next() && rs.getInt(1) == 0) {
                Log.error("目标达梦实例为【大小写不敏感】初始化（CASE_SENSITIVE=0），"
                        + "无法保留 MySQL 表名/字段名原始大小写，需求无法满足！");
                if (cfg.requireCaseSensitive) {
                    Log.error("已按 requireCaseSensitive=true 终止。请以 CASE_SENSITIVE=Y 重新初始化达梦实例，"
                            + "或确认后设置 requireCaseSensitive=false 强行继续。");
                    return false;
                }
                Log.warn("requireCaseSensitive=false，强行继续（大小写将无法保留）");
            } else {
                Log.info("达梦实例大小写敏感校验通过（CASE_SENSITIVE=Y）");
            }
        } catch (SQLException e) {
            Log.warn("无法检测达梦大小写敏感参数（%s），请自行确认实例初始化参数 CASE_SENSITIVE=Y", e.getMessage());
        }
        return true;
    }

    private static String normalizeMysqlUrl(String url) {
        String[][] required = {
                {"useUnicode", "true"},
                {"characterEncoding", "utf8"},
                {"zeroDateTimeBehavior", "convertToNull"},
                {"tinyInt1isBit", "false"},
                {"allowPublicKeyRetrieval", "true"},
                {"serverTimezone", "Asia/Shanghai"},
        };
        String low = url.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(url);
        for (String[] kv : required) {
            if (!low.contains(kv[0].toLowerCase(Locale.ROOT) + "=")) {
                sb.append(sb.indexOf("?") >= 0 ? "&" : "?")
                        .append(kv[0]).append('=').append(kv[1]);
                Log.info("MySQL 连接串自动补充参数: %s=%s", kv[0], kv[1]);
            }
        }
        return sb.toString();
    }

    private static String extractMysqlDb(String url) {
        String u = url;
        int qm = u.indexOf('?');
        if (qm >= 0) {
            u = u.substring(0, qm);
        }
        int slash = u.lastIndexOf('/');
        int scheme = u.indexOf("://");
        if (slash < 0 || (scheme >= 0 && slash <= scheme + 2)) {
            return null;
        }
        String db = u.substring(slash + 1).trim();
        try {
            db = URLDecoder.decode(db, "UTF-8");
        } catch (Exception ignore) {
        }
        return db.isEmpty() ? null : db;
    }

    private static String tableNames(List<TableMeta> tables) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tables.get(i).name);
        }
        return sb.toString();
    }

    private static int summarize(Map<String, TableStats> stats, int secondaryFail, Config cfg, long t0) {
        Log.info("══════════════════ 迁移结果汇总 ══════════════════");
        Log.info(String.format("%-32s %-10s %12s %12s %10s", "表名", "状态", "源行数", "目标行数", "耗时(秒)"));
        int failed = 0;
        int partial = 0;
        int ok = 0;
        for (TableStats st : stats.values()) {
            boolean hardFail = TableStats.FAILED.equals(st.status) || TableStats.PARTIAL.equals(st.status);
            if (hardFail) {
                failed++;
            } else if (TableStats.LOADED.equals(st.status) || TableStats.CREATED.equals(st.status)
                    || TableStats.SKIP_EXISTS.equals(st.status) || TableStats.STRUCT_ONLY.equals(st.status)
                    || TableStats.DRY_RUN.equals(st.status)) {
                ok++;
            }
            if (TableStats.PARTIAL.equals(st.status)) {
                partial++;
            }
            Log.info(String.format("%-32s %-10s %12s %12s %10.1f",
                    st.table, st.status,
                    st.srcCount < 0 ? "-" : String.valueOf(st.srcCount),
                    st.dstCount < 0 ? "-" : String.valueOf(st.dstCount),
                    st.ms / 1000.0));
            if (st.error != null) {
                Log.info(String.format("    └─ %s", st.error));
            }
        }
        Log.info("══════════════════════════════════════════════════");
        Log.info("耗时 %.1f 秒 | 表: 成功 %d, 失败/部分 %d | 索引/外键失败 %d 项 | 日志: %s",
                (System.currentTimeMillis() - t0) / 1000.0, ok, failed, secondaryFail, cfg.logFile);
        if (failed > 0) {
            Log.error("存在失败或数据不完整的表（含被跳过的行），请根据上方 ERROR 明细处理后再运行。");
            return 2;
        }
        if (secondaryFail > 0) {
            Log.warn("数据已迁移完成，但存在索引/外键创建失败项，请根据 ERROR 明细手工处理。");
            return 2;
        }
        Log.info("迁移全部成功 ✓");
        return 0;
    }

    static void printHelp() {
        System.out.println("MySQL → 达梦(DM8) 表结构与数据迁移工具 v" + VERSION);
        System.out.println();
        System.out.println("用法:");
        System.out.println("  java -jar mysql2dm.jar --config config.properties [覆盖项...]");
        System.out.println("  java -jar mysql2dm.jar --help");
        System.out.println("  java -jar mysql2dm.jar --selftest    # 内置类型映射自测（无需数据库）");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  # 全量迁移（结构+数据）");
        System.out.println("  java -jar mysql2dm.jar --config config.properties");
        System.out.println("  # 只迁移指定表，失败行打印诊断后继续");
        System.out.println("  java -jar mysql2dm.jar --config config.properties --tables user,order_info --onInsertError continue");
        System.out.println("  # 仅数据（表结构已就绪）");
        System.out.println("  java -jar mysql2dm.jar --config config.properties --mode data");
        System.out.println("  # 模拟运行，仅打印将执行的 DDL");
        System.out.println("  java -jar mysql2dm.jar --config config.properties --dryRun true");
        System.out.println();
        System.out.println("配置项（均可用 --键 值 或 --键=值 覆盖，完整说明见 config.properties 注释）:");
        System.out.println("  " + Config.KEYS);
        System.out.println();
        System.out.println("重要前提: 目标达梦实例必须以大小写敏感方式初始化（CASE_SENSITIVE=Y），");
        System.out.println("工具会自动检测；所有表名/字段名迁移后均加双引号保留原始大小写。");
    }
}
