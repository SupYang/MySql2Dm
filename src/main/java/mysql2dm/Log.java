package mysql2dm;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 极简日志：同时输出到控制台与文件（UTF-8）。
 */
final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    private static PrintStream fileOut;

    private Log() {
    }

    static void initFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        try {
            Path p = Paths.get(path.trim());
            Path parent = p.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OutputStream os = new BufferedOutputStream(new FileOutputStream(p.toFile(), true), 1 << 16);
            fileOut = new PrintStream(os, true, "UTF-8");
        } catch (Exception e) {
            System.err.println("[WARN] 无法打开日志文件 " + path + " : " + e.getMessage());
        }
    }

    static void info(String fmt, Object... args) {
        write("INFO ", fmt(fmt, args));
    }

    static void warn(String fmt, Object... args) {
        write("WARN ", fmt(fmt, args));
    }

    static void error(String fmt, Object... args) {
        write("ERROR", fmt(fmt, args));
    }

    /** 多行错误明细（用于字段级诊断输出） */
    static void rawError(String line) {
        write("ERROR", line);
    }

    private static String fmt(String fmt, Object... args) {
        if (args == null || args.length == 0) {
            return fmt;
        }
        try {
            return String.format(fmt, args);
        } catch (Exception e) {
            return fmt;
        }
    }

    private static void write(String level, String msg) {
        String line = LocalDateTime.now().format(TS) + " [" + level + "] " + msg;
        synchronized (LOCK) {
            System.out.println(line);
            if (fileOut != null) {
                fileOut.println(line);
                fileOut.flush();
            }
        }
    }
}
