# MySql2Dm

MySQL → 达梦（DM8）表结构与数据迁移工具。单 jar 免安装，内置 MySQL 与达梦 JDBC 驱动，开箱即用。

## 特性

- **大小写原样保留**：所有 DDL/DML 使用双引号包裹标识符（`"UserInfo"."Id"`），迁移后在大小写敏感的达梦库中保持 MySQL 原始表名/字段名不变。连接达梦后自动检测 `CASE_SENSITIVE()`，目标库不敏感时终止，避免静默转大写。
- **结构与数据完整迁移**：三阶段执行——建表 → 数据装载 → 索引/外键（后置创建提速装载并规避依赖顺序），完成后逐表校验源/目标行数。
- **全面的类型映射**：unsigned 自动扩宽（`int unsigned → BIGINT`、`bigint unsigned → DECIMAL(20,0)`）、`enum/set → VARCHAR`、`json → TEXT`、`bit/year/零值日期` 等特殊处理，内置 40+ 映射自测断言。
- **自增主键无缝迁移**：`AUTO_INCREMENT → IDENTITY(种子,1)`，装载数据期间自动开关 `SET IDENTITY_INSERT`，自增值不丢。
- **流式读 + 批量写**：MySQL 侧流式游标防 OOM，达梦侧批量提交；失败时自动逐行重放定位坏行，打印字段级诊断（列名/类型/值预览/字节长度）。
- **模拟运行**：`--dry-run` 只连源库打印将执行的 DDL 与行数，不写目标库。
- **注释迁移**：表/列 COMMENT 一并迁移。

## 环境要求

- Java 8 及以上（无需安装 MySQL/达梦客户端）
- MySQL 5.7 / 8.0 / MariaDB（源库账号需可读 `information_schema`）
- 达梦 DM8，且初始化参数 **`CASE_SENSITIVE=Y`**（大小写敏感，工具会强制检测）

## 快速开始

```bash
# 1. 修改配置（源库、目标库连接信息见 config.properties）
# 2. 模拟运行：只打印 DDL 与行数，不写达梦
run.bat --config=config.properties --dry-run

# 3. 确认无误后正式迁移（结构 + 数据）
run.bat --config=config.properties
```

Linux/macOS 下直接用 java 命令：

```bash
java -jar target/mysql2dm.jar --config=config.properties --dry-run
```

## 构建

```bash
mvn package
# 产出 target/mysql2dm.jar（fat-jar，已包含两个 JDBC 驱动）
```

## 常用命令

```bash
java -jar mysql2dm.jar --config config.properties            # 全量迁移（结构+数据）
java -jar mysql2dm.jar --config config.properties --dry-run  # 模拟运行
java -jar mysql2dm.jar --mode=structure                      # 仅迁移表结构
java -jar mysql2dm.jar --mode=data                           # 仅迁移数据（表须已存在）
java -jar mysql2dm.jar --tables user,order_info              # 只迁指定表
java -jar mysql2dm.jar --selftest                            # 内置类型映射自测（无需数据库）
java -jar mysql2dm.jar --help                                # 查看全部配置项
```

命令行参数可覆盖配置文件中的同名项；布尔项支持无值开关（`--dry-run` 等价 `--dryRun=true`）；
键名大小写、连字符、下划线不敏感（`--dry-run` / `--dry_run` / `--dryRun` 等价）。

## 主要配置项

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `mysql.url` / `mysql.user` / `mysql.password` | — | 源库连接，**URL 路径中必须包含库名** |
| `dm.url` / `dm.user` / `dm.password` | — | 目标库连接，如 `jdbc:dm://host:5236` |
| `dm.schema` | 登录用户默认模式 | 目标模式名 |
| `mode` | `all` | `all` / `structure` / `data` |
| `tables` / `excludeTables` | 全部表 | 逗号分隔的表名白/黑名单 |
| `createStrategy` | `skip` | 目标表已存在时：`skip` 跳过 / `recreate` 删了重建 / `fail` 报错 |
| `varcharExpandFactor` | `1` | VARCHAR/CHAR 长度换算系数，见下方 FAQ |
| `migrateComments` / `migrateIndexes` / `migrateForeignKeys` | `true` | 是否迁移注释/索引/外键 |
| `batchSize` / `commitRows` | `1000` / `10000` | 批量写大小 / 提交行数 |
| `truncateBeforeLoad` | `false` | 装载前清空目标表 |
| `onInsertError` | `abort` | 坏行处理：`abort` 打印诊断后终止 / `continue` 跳过并计数 |
| `verifyCounts` | `true` | 迁移后逐表校验源/目标行数 |
| `dryRun` | `false` | 模拟运行 |
| `logFile` | `mysql2dm.log` | 日志文件（控制台与文件双写） |

完整说明见 [config.properties](config.properties) 内注释。

## 类型映射要点

| MySQL | 达梦 |
|---|---|
| `tinyint unsigned` | `SMALLINT` |
| `int unsigned` | `BIGINT` |
| `bigint unsigned` | `DECIMAL(20,0)` |
| `varchar(n)` / `char(n)` | `VARCHAR(n×系数)`；超 8188 字节转 `TEXT`，`char` 超 2000 转 `VARCHAR` |
| `enum` / `set` | `VARCHAR`（按字面量计算长度） |
| `json` | `TEXT` |
| `bit(1)` / `bit(n)` | `BIT` / `BINARY` |
| `year` | `SMALLINT` |
| `AUTO_INCREMENT` | `IDENTITY(种子,1)` |
| `geometry`、生成列 | 默认报错终止；`ignoreUnsupported=true` 跳过该列 |

## FAQ

**Q: 为什么大小写不会变？**
达梦大小写敏感库中，不加引号的标识符会统一转大写；本工具所有 DDL/DML 一律用双引号包裹标识符，因此保留 MySQL 原始大小写。

**Q: VARCHAR 长度要不要扩宽（×3/×4）？**
默认 ×1（长度原样）。仅当达梦按字节计长（建库参数 `LENGTH_IN_CHAR=0`）且数据含中文/emoji 时，可能出现「数据超长」报错——此时加 `--varcharExpandFactor=3`（或 4）即可，无需改表。

**Q: 遇到个别坏行怎么办？**
默认 `onInsertError=abort`：回滚当前批次、打印坏行的字段级诊断后终止。确认坏行可舍弃后改 `--onInsertError=continue` 跳过并计数，迁移汇总中会体现跳过行数。

**Q: 二次执行会重复建表吗？**
默认 `createStrategy=skip`：已存在的表跳过建表、数据照常装载（建议配合 `truncateBeforeLoad=true`）。需要推倒重来用 `recreate`。

## 许可证

MIT
