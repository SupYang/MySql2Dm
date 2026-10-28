package mysql2dm;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/** 配置：默认值 → 配置文件 → 命令行覆盖。 */
final class Config {

    String mysqlUrl = "";
    String mysqlUser = "";
    String mysqlPassword = "";
    String dmUrl = "";
    String dmUser = "";
    String dmPassword = "";
    String dmSchema = "";          // 为空时使用达梦登录用户默认模式

    String mode = "all";           // all | structure | data
    List<String> tables = new ArrayList<>();        // 空=全部
    List<String> excludeTables = new ArrayList<>();

    String createStrategy = "skip";            // skip | recreate | fail
    double varcharExpandFactor = 1.0;          // 1=长度原样迁移；若达梦 LENGTH_IN_CHAR=0（字节语义）且含中文/emoji 可调大至 4
    boolean migrateComments = true;
    boolean migrateIndexes = true;
    boolean migrateForeignKeys = true;
    boolean ignoreUnsupported = false;         // true 时跳过无法映射的列（数据将缺失）

    int batchSize = 1000;
    int commitRows = 10000;
    int progressRows = 50000;
    boolean truncateBeforeLoad = false;
    String onInsertError = "abort";            // abort | continue
    boolean verifyCounts = true;

    boolean requireCaseSensitive = true;       // 目标库大小写不敏感时终止
    boolean dryRun = false;
    String logFile = "mysql2dm.log";

    boolean selfTest = false;

    static final TreeSet<String> KEYS = new TreeSet<>(Arrays.asList(
            "mysql.url", "mysql.user", "mysql.password",
            "dm.url", "dm.user", "dm.password", "dm.schema",
            "mode", "tables", "excludeTables", "createStrategy", "varcharExpandFactor",
            "migrateComments", "migrateIndexes", "migrateForeignKeys", "ignoreUnsupported",
            "batchSize", "commitRows", "progressRows", "truncateBeforeLoad", "onInsertError",
            "verifyCounts", "requireCaseSensitive", "dryRun", "logFile"));

    private static final TreeSet<String> MODES = new TreeSet<>(Arrays.asList("all", "structure", "data"));
    private static final TreeSet<String> STRATEGIES = new TreeSet<>(Arrays.asList("skip", "recreate", "fail"));
    private static final TreeSet<String> ERROR_POLICIES = new TreeSet<>(Arrays.asList("abort", "continue"));

    /** 布尔配置项：命令行可用无值开关形式（如 --dry-run 等价 --dryRun=true） */
    private static final TreeSet<String> BOOLEAN_KEYS = new TreeSet<>(Arrays.asList(
            "migrateComments", "migrateIndexes", "migrateForeignKeys", "ignoreUnsupported",
            "truncateBeforeLoad", "verifyCounts", "requireCaseSensitive", "dryRun"));

    /** 归一化键名 → 正式键名（dry-run / dry_run / dryRun / DRYRUN 均等价） */
    private static final Map<String, String> NORM_KEYS = new HashMap<>();
    static {
        for (String k : KEYS) {
            NORM_KEYS.put(normKey(k), k);
        }
    }

    private static String normKey(String k) {
        return k.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    /** 键名归一化并校验，未知键抛错，返回正式键名。 */
    private static String canonicalKey(String key) {
        String canonical = NORM_KEYS.get(normKey(key));
        if (canonical == null) {
            throw new IllegalArgumentException("未知配置项 [" + key + "]，可用项: " + KEYS);
        }
        return canonical;
    }

    private Config() {
    }

    static Config load(String[] args) {
        Config c = new Config();
        String cfgPath = null;
        List<String[]> cli = new ArrayList<>();
        boolean selfTest = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("--help") || a.equalsIgnoreCase("-h")) {
                Main.printHelp();
                System.exit(0);
            }
            if (a.equalsIgnoreCase("--selftest")) {
                selfTest = true;
                continue;
            }
            String la = a.toLowerCase(Locale.ROOT);
            if (la.equals("--config") || la.startsWith("--config=")) {
                if (la.startsWith("--config=")) {
                    cfgPath = a.substring(9).trim();
                    if (cfgPath.isEmpty()) {
                        throw new IllegalArgumentException("--config 需要指定文件路径");
                    }
                } else {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--config 需要指定文件路径");
                    }
                    cfgPath = args[++i].trim();
                }
                continue;
            }
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + a + "（使用 --help 查看用法）");
            }
            String kv = a.substring(2);
            String key;
            String value;
            int eq = kv.indexOf('=');
            if (eq >= 0) {
                key = kv.substring(0, eq).trim();
                value = kv.substring(eq + 1).trim();
            } else {
                key = kv.trim();
                if (BOOLEAN_KEYS.contains(key) || BOOLEAN_KEYS.contains(canonicalKey(key))) {
                    // 布尔开关：无值即 true（如 --dry-run），不消费下一个参数
                    value = "true";
                } else {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("参数 --" + key + " 缺少取值");
                    }
                    value = args[++i].trim();
                }
            }
            key = canonicalKey(key);
            cli.add(new String[]{key, value});
        }

        if (cfgPath != null) {
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(cfgPath)) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalArgumentException("无法读取配置文件 " + cfgPath + " : " + e.getMessage());
            }
            for (String key : KEYS) {
                String v = p.getProperty(key);
                if (v != null) {
                    c.set(key, v.trim());
                }
            }
        }
        for (String[] kv : cli) {
            c.set(kv[0], kv[1]);
        }
        c.selfTest = selfTest;
        if (!selfTest) {
            c.validate();
        }
        return c;
    }

    private void set(String key, String value) {
        try {
            switch (key) {
                case "mysql.url": mysqlUrl = value; break;
                case "mysql.user": mysqlUser = value; break;
                case "mysql.password": mysqlPassword = value; break;
                case "dm.url": dmUrl = value; break;
                case "dm.user": dmUser = value; break;
                case "dm.password": dmPassword = value; break;
                case "dm.schema": dmSchema = value; break;
                case "mode": mode = value.toLowerCase(); break;
                case "tables": tables = splitCsv(value); break;
                case "excludeTables": excludeTables = splitCsv(value); break;
                case "createStrategy": createStrategy = value.toLowerCase(); break;
                case "varcharExpandFactor": varcharExpandFactor = Double.parseDouble(value); break;
                case "migrateComments": migrateComments = parseBool(key, value); break;
                case "migrateIndexes": migrateIndexes = parseBool(key, value); break;
                case "migrateForeignKeys": migrateForeignKeys = parseBool(key, value); break;
                case "ignoreUnsupported": ignoreUnsupported = parseBool(key, value); break;
                case "batchSize": batchSize = Integer.parseInt(value); break;
                case "commitRows": commitRows = Integer.parseInt(value); break;
                case "progressRows": progressRows = Integer.parseInt(value); break;
                case "truncateBeforeLoad": truncateBeforeLoad = parseBool(key, value); break;
                case "onInsertError": onInsertError = value.toLowerCase(); break;
                case "verifyCounts": verifyCounts = parseBool(key, value); break;
                case "requireCaseSensitive": requireCaseSensitive = parseBool(key, value); break;
                case "dryRun": dryRun = parseBool(key, value); break;
                case "logFile": logFile = value; break;
                default: throw new IllegalArgumentException("未知配置项 " + key);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置项 " + key + " 需要数值，得到 [" + value + "]");
        }
    }

    private static boolean parseBool(String key, String value) {
        if (value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equals("0") || value.equalsIgnoreCase("no")
                || value.equalsIgnoreCase("off")) {
            return false;
        }
        throw new IllegalArgumentException("配置项 " + key + " 需要 true/false，得到 [" + value + "]");
    }

    private static List<String> splitCsv(String v) {
        List<String> out = new ArrayList<>();
        for (String s : v.split("[,，]")) {
            s = s.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private void validate() {
        requireEnum(mode, MODES, "mode");
        requireEnum(createStrategy, STRATEGIES, "createStrategy");
        requireEnum(onInsertError, ERROR_POLICIES, "onInsertError");
        requireNonEmpty(mysqlUrl, "mysql.url");
        requireNonEmpty(mysqlUser, "mysql.user");
        // dryRun 仅连接 MySQL 预览，不校验达梦配置
        if (!dryRun) {
            requireNonEmpty(dmUrl, "dm.url");
            requireNonEmpty(dmUser, "dm.user");
        }
        if (batchSize < 1 || commitRows < 1 || progressRows < 1) {
            throw new IllegalArgumentException("batchSize/commitRows/progressRows 必须 ≥ 1");
        }
        if (varcharExpandFactor < 1 || varcharExpandFactor > 4) {
            throw new IllegalArgumentException("varcharExpandFactor 取值范围 1~4，默认 1（长度原样；达梦字节语义下存中文/emoji 溢出时可调大）");
        }
    }

    private static void requireEnum(String v, TreeSet<String> allowed, String key) {
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException("配置项 " + key + " 可选值 " + allowed + "，得到 [" + v + "]");
        }
    }

    private static void requireNonEmpty(String v, String key) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("配置项 " + key + " 不能为空");
        }
    }

    String mask(String s) {
        return (s == null || s.isEmpty()) ? "(空)" : "******";
    }

    void dump() {
        Log.info("──────────────── 有效配置 ────────────────");
        Log.info("  mysql.url            = %s", maskUrl(mysqlUrl));
        Log.info("  mysql.user           = %s", mysqlUser);
        Log.info("  mysql.password       = %s", mask(mysqlPassword));
        Log.info("  dm.url               = %s", maskUrl(dmUrl));
        Log.info("  dm.user              = %s", dmUser);
        Log.info("  dm.password          = %s", mask(dmPassword));
        Log.info("  dm.schema            = %s", dmSchema.isEmpty() ? "(登录用户默认模式)" : dmSchema);
        Log.info("  mode                 = %s", mode);
        Log.info("  tables               = %s", tables.isEmpty() ? "(全部表)" : tables);
        Log.info("  excludeTables        = %s", excludeTables.isEmpty() ? "(无)" : excludeTables);
        Log.info("  createStrategy       = %s", createStrategy);
        Log.info("  varcharExpandFactor  = %s", varcharExpandFactor);
        Log.info("  migrateComments/Indexes/ForeignKeys = %s/%s/%s", migrateComments, migrateIndexes, migrateForeignKeys);
        Log.info("  ignoreUnsupported    = %s", ignoreUnsupported);
        Log.info("  batchSize/commitRows/progressRows = %d/%d/%d", batchSize, commitRows, progressRows);
        Log.info("  truncateBeforeLoad   = %s", truncateBeforeLoad);
        Log.info("  onInsertError        = %s", onInsertError);
        Log.info("  verifyCounts         = %s", verifyCounts);
        Log.info("  requireCaseSensitive = %s", requireCaseSensitive);
        Log.info("  dryRun               = %s", dryRun);
        Log.info("  logFile              = %s", logFile);
        Log.info("──────────────────────────────────────────");
    }

    private static String maskUrl(String url) {
        return url.replaceAll("(?i)(password=)[^&]*", "$1******");
    }
}
