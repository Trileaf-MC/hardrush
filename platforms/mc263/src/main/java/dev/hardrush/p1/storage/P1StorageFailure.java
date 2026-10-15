package dev.hardrush.p1.storage;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Concrete recovery instructions supplement, and never replace, the original exception. */
public final class P1StorageFailure {
    private P1StorageFailure() {}

    public static String advice(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                // SQLite extended result codes retain the primary result in the low byte.
                switch (sql.getErrorCode() & 0xff) {
                    case 5, 6: return "检查其他占用同一 SQLite 的进程并释放写锁；修复后正常停止并重启核验。";
                    case 8: return "检查数据库及 WAL/SHM 所在目录的写权限和只读挂载；修复后正常停止并重启核验。";
                    case 13: return "扩容或移走经确认可迁移的其他资料；不要删除数据库、WAL、世界或录像；修复后正常停止并重启核验。";
                    case 10, 11, 26: return "保留数据库及 WAL/SHM 和世界清单，检查存储介质；在副本核验完整性，禁止删除原库重新开局。";
                    default: break;
                }
            }
        }
        return "保留原始文件并检查完整错误；世界准备失败可申请 retry-world，数据库或档案故障需修复后重启核验。";
    }
}
