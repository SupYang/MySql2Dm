package mysql2dm;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据迁移：MySQL 流式读取 → 达梦批量写入。
 * 批次失败时回滚后逐行重放定位坏行，打印字段级诊断信息；
 * onInsertError=abort（默认）打印诊断后终止，continue 时跳过坏行并计数（最终退出码非 0）。
 */
final class DataMigrator {

    /** 单表逐行诊断的最大输出条数，避免刷屏 */
    private static final int MAX_DIAG = 50;

    private DataMigrator() {
    }

    static void migrate(TableMeta t, String schema, Connection mc, Connection dc, Config cfg,
                        TableStats st) throws Exception {
        List<ColumnMeta> cols = t.mappableColumns();
        if (cols.isEmpty()) {
            Log.warn("[%s] 没有可迁移的列，跳过数据迁移", t.name);
            return;
        }

        String qualified = DdlBuilder.qualify(schema, t.name);
        String insertSql = buildInsert(qualified, cols);
        String selectSql = buildSelect(t, cols);
        boolean identity = false;
        for (ColumnMeta c : cols) {
            if (c.identity) {
                identity = true;
                break;
            }
        }

        Log.info("[%s] 开始迁移数据 (批量=%d 行/批, 每 %d 行提交)%s",
                t.name, cfg.batchSize, cfg.commitRows, cfg.truncateBeforeLoad ? "，先清空目标表" : "");

        if (cfg.truncateBeforeLoad) {
            execDdl(dc, DdlBuilder.truncateTable(schema, t.name));
        }
        // 允许向 IDENTITY 自增列显式插入源库主键值
        if (identity) {
            execDdl(dc, DdlBuilder.identityInsertOn(schema, t.name));
        }

        long t0 = System.currentTimeMillis();
        long total = 0;      // 已写入行数
        long inTx = 0;       // 当前事务内未提交行数
        long lastProgress = 0;
        long rowNo = 0;      // 当前读取到的源行号

        dc.setAutoCommit(false);
        PreparedStatement ps = dc.prepareStatement(insertSql);
        Statement readStmt = mc.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        readStmt.setFetchSize(Integer.MIN_VALUE);   // MySQL 流式读取，避免整表载入内存
        ResultSet rs = null;
        List<Object[]> batch = new ArrayList<>(cfg.batchSize);
        try {
            rs = readStmt.executeQuery(selectSql);
            while (rs.next()) {
                rowNo++;
                Object[] row = readRow(rs, cols);
                batch.add(row);
                bind(ps, row, cols);
                ps.addBatch();

                if (batch.size() >= cfg.batchSize) {
                    flushBatch(ps, batch, dc, t, cols, rowNo, cfg, st);
                    total += batch.size();
                    inTx += batch.size();
                    batch.clear();
                    if (inTx >= cfg.commitRows) {
                        dc.commit();
                        inTx = 0;
                    }
                    if (total - lastProgress >= cfg.progressRows) {
                        double sec = (System.currentTimeMillis() - t0) / 1000.0;
                        Log.info("[%s] 已写入 %d 行 (速率 %.0f 行/秒)", t.name, total, sec > 0 ? total / sec : 0);
                        lastProgress = total;
                    }
                }
            }
            if (!batch.isEmpty()) {
                flushBatch(ps, batch, dc, t, cols, rowNo, cfg, st);
                total += batch.size();
                inTx += batch.size();
                batch.clear();
            }
            dc.commit();
        } catch (Exception e) {
            try {
                dc.rollback();
            } catch (SQLException ignore) {
            }
            throw e;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
            closeQuietly(readStmt);
            if (identity) {
                try {
                    execDdl(dc, DdlBuilder.identityInsertOff(schema, t.name));
                } catch (SQLException e) {
                    Log.warn("[%s] 关闭 IDENTITY_INSERT 失败: %s", t.name, e.getMessage());
                }
            }
            dc.setAutoCommit(true);
        }

        st.inserted = total;
        st.ms = System.currentTimeMillis() - t0;

        double sec = st.ms / 1000.0;
        if (st.skippedRows > 0) {
            Log.error("[%s] 完成，但跳过了 %d 行失败数据（共写入 %d 行，耗时 %.1f 秒）",
                    t.name, st.skippedRows, total, sec);
        } else {
            Log.info("[%s] 数据迁移完成，共 %d 行，耗时 %.1f 秒", t.name, total, sec);
        }
    }

    /**
     * 执行一批。批量失败时：回滚当前事务 → 逐行重放 → 对失败行打印字段级诊断。
     * abort 策略抛出 MigrationException 终止；continue 策略跳过坏行（st.skippedRows 计数）。
     */
    private static void flushBatch(PreparedStatement ps, List<Object[]> batch, Connection dc,
                                   TableMeta t, List<ColumnMeta> cols, long endRowNo, Config cfg,
                                   TableStats st) throws SQLException {
        try {
            ps.executeBatch();
            return;
        } catch (SQLException e) {
            Log.warn("[%s] 批量写入失败: %s，回滚当前事务后逐行重放以定位坏行", t.name, e.getMessage());
        }
        try {
            dc.rollback();
        } catch (SQLException ignore) {
        }
        List<Object[]> replay = new ArrayList<>(batch);
        batch.clear();
        long firstRowNo = endRowNo - replay.size() + 1;
        for (int i = 0; i < replay.size(); i++) {
            Object[] row = replay.get(i);
            try {
                bind(ps, row, cols);
                ps.executeUpdate();
            } catch (SQLException sex) {
                st.skippedRows++;
                if (st.diagCount < MAX_DIAG) {
                    st.diagCount++;
                    dumpRowError(t, firstRowNo + i, cols, row, sex);
                }
                if ("abort".equalsIgnoreCase(cfg.onInsertError)) {
                    throw new MigrationException(String.format(
                            "表 [%s] 第 %d 行写入失败（上方 ERROR 已给出字段级诊断），原因: %s",
                            t.name, firstRowNo + i, sex.getMessage()));
                }
                Log.warn("[%s] onInsertError=continue，跳过第 %d 行", t.name, firstRowNo + i);
            }
        }
    }

    /** 字段级诊断输出：列序号、列名、达梦类型、NULL/值预览、长度 */
    private static void dumpRowError(TableMeta t, long rowNo, List<ColumnMeta> cols, Object[] row,
                                     SQLException e) {
        Log.rawError("──────────────────────────────────────────────────────────");
        Log.rawError(String.format("表 [%s] 第 %d 行写入失败: %s", t.name, rowNo, e.getMessage()));
        Log.rawError(String.format("  SQLState=%s, ErrorCode=%d", e.getSQLState(), e.getErrorCode()));
        SQLException next = e.getNextException();
        int chain = 0;
        while (next != null && chain < 3) {
            Log.rawError(String.format("  原因%d: %s", ++chain, next.getMessage()));
            next = next.getNextException();
        }
        for (int i = 0; i < cols.size(); i++) {
            ColumnMeta c = cols.get(i);
            Object v = row[i];
            if (v == null) {
                Log.rawError(String.format("  列 %d/%d %s [%s] : NULL", i + 1, cols.size(),
                        DdlBuilder.q(c.name), c.dmType));
            } else {
                Log.rawError(String.format("  列 %d/%d %s [%s] : %s (%s)", i + 1, cols.size(),
                        DdlBuilder.q(c.name), c.dmType, preview(v), v.getClass().getSimpleName()));
            }
        }
        Log.rawError("──────────────────────────────────────────────────────────");
    }

    static String preview(Object v) {
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            StringBuilder sb = new StringBuilder("0x");
            int n = Math.min(b.length, 8);
            for (int i = 0; i < n; i++) {
                sb.append(String.format("%02X", b[i]));
            }
            if (b.length > n) {
                sb.append("…");
            }
            sb.append(" (length=").append(b.length).append("字节)");
            return sb.toString();
        }
        String s = String.valueOf(v);
        boolean cut = false;
        if (s.length() > 80) {
            s = s.substring(0, 80);
            cut = true;
        }
        if (v instanceof String) {
            s += " (length=" + ((String) v).length() + "字符)";
        } else if (cut) {
            s += "…";
        }
        return "'" + s.replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    // ---- SQL 拼装 ----
    private static String buildSelect(TableMeta t, List<ColumnMeta> cols) {
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('`').append(cols.get(i).name.replace("`", "``")).append('`');
        }
        sb.append(" FROM `").append(t.name.replace("`", "``")).append('`');
        return sb.toString();
    }

    private static String buildInsert(String qualified, List<ColumnMeta> cols) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(qualified).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(DdlBuilder.q(cols.get(i).name));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        sb.append(")");
        return sb.toString();
    }

    // ---- 读写按 Kind 分派 ----
    private static Object[] readRow(ResultSet rs, List<ColumnMeta> cols) throws SQLException {
        Object[] row = new Object[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            int idx = i + 1;
            ColumnMeta c = cols.get(i);
            Object v;
            switch (c.kind) {
                case INT:
                case YEAR_INT: {
                    int x = rs.getInt(idx);
                    v = rs.wasNull() ? null : x;
                    break;
                }
                case LONG: {
                    long x = rs.getLong(idx);
                    v = rs.wasNull() ? null : x;
                    break;
                }
                case DECIMAL:
                    v = rs.getBigDecimal(idx);
                    break;
                case FLOAT: {
                    float x = rs.getFloat(idx);
                    v = rs.wasNull() ? null : x;
                    break;
                }
                case DOUBLE: {
                    double x = rs.getDouble(idx);
                    v = rs.wasNull() ? null : x;
                    break;
                }
                case STRING:
                    v = rs.getString(idx);
                    break;
                case DATE:
                    v = rs.getDate(idx);
                    break;
                case TIME:
                    v = rs.getString(idx);   // 保留原始字面量（含小数秒）
                    break;
                case TIMESTAMP:
                    v = rs.getTimestamp(idx);
                    break;
                case BIT1: {
                    boolean x = rs.getBoolean(idx);
                    v = rs.wasNull() ? null : x;
                    break;
                }
                case BYTES:
                    v = rs.getBytes(idx);
                    break;
                default:
                    v = rs.getObject(idx);
            }
            row[i] = v;
        }
        return row;
    }

    private static void bind(PreparedStatement ps, Object[] row, List<ColumnMeta> cols) throws SQLException {
        for (int i = 0; i < cols.size(); i++) {
            int idx = i + 1;
            ColumnMeta c = cols.get(i);
            Object v = row[i];
            if (v == null) {
                ps.setNull(idx, sqlType(c.kind));
                continue;
            }
            switch (c.kind) {
                case INT:
                case YEAR_INT:
                    ps.setInt(idx, (Integer) v);
                    break;
                case LONG:
                    ps.setLong(idx, (Long) v);
                    break;
                case DECIMAL:
                    ps.setBigDecimal(idx, (java.math.BigDecimal) v);
                    break;
                case FLOAT:
                    ps.setFloat(idx, (Float) v);
                    break;
                case DOUBLE:
                    ps.setDouble(idx, (Double) v);
                    break;
                case STRING:
                    ps.setString(idx, (String) v);
                    break;
                case DATE:
                    ps.setDate(idx, (java.sql.Date) v);
                    break;
                case TIME:
                    ps.setString(idx, (String) v);
                    break;
                case TIMESTAMP:
                    ps.setTimestamp(idx, (java.sql.Timestamp) v);
                    break;
                case BIT1:
                    ps.setBoolean(idx, (Boolean) v);
                    break;
                case BYTES:
                    ps.setBytes(idx, (byte[]) v);
                    break;
                default:
                    ps.setObject(idx, v);
            }
        }
    }

    private static int sqlType(ColumnMeta.Kind kind) {
        switch (kind) {
            case INT:
            case YEAR_INT:
                return Types.INTEGER;
            case LONG:
                return Types.BIGINT;
            case DECIMAL:
                return Types.DECIMAL;
            case FLOAT:
                return Types.REAL;
            case DOUBLE:
                return Types.DOUBLE;
            case STRING:
                return Types.VARCHAR;
            case DATE:
                return Types.DATE;
            case TIME:
                return Types.VARCHAR;
            case TIMESTAMP:
                return Types.TIMESTAMP;
            case BIT1:
                return Types.BIT;
            case BYTES:
                return Types.VARBINARY;
            default:
                return Types.OTHER;
        }
    }

    // ---- 工具 ----
    private static void execDdl(Connection dc, String sql) throws SQLException {
        try (Statement s = dc.createStatement()) {
            s.execute(sql);
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
            }
        }
    }
}
