package mysql2dm;

/** 迁移过程中的可读性异常（消息已面向用户，直接展示）。 */
class MigrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MigrationException(String message) {
        super(message);
    }
}
