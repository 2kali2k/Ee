package app.ee.core.model

/**
 * The kind of filesystem a node lives in. This is the stable identity of a
 * provider (see `app.ee.core.fs.FsProvider` in `core-file`).
 *
 * Mirrors the "virtual filesystem" design of the reference app
 * (`com.estrongs.fs.impl.*`, docs/01-apk-analysis.md §4), rebuilt from scratch.
 */
enum class FsType {
    LOCAL,
    USB,
    MEDIA,
    RECENT,
    FAVORITE,
    ARCHIVE,
    VAULT,
    SMB,
    SFTP,
    FTP,
    FTPSRV,
    WEBDAV,
    HTTP,
    CLOUD,
}

/** What kind of node/file is being created. */
enum class FsKind {
    FILE,
    DIRECTORY,
    SYMLINK,
}

/**
 * What a provider can do. UI must check capabilities instead of assuming;
 * e.g. HTTP roots are read-only, archives may be read-only for RAR.
 */
enum class FsCapability {
    READ,
    WRITE,
    CREATE,
    CREATE_DIR,
    RENAME,
    DELETE,
    RANGE_READ,
    RESUME_DOWNLOAD,
    STREAM,
}

/** POSIX-ish permission flags, when a provider exposes them. */
enum class Perm {
    OWNER_READ,
    OWNER_WRITE,
    OWNER_EXECUTE,
    GROUP_READ,
    GROUP_WRITE,
    GROUP_EXECUTE,
    OTHERS_READ,
    OTHERS_WRITE,
    OTHERS_EXECUTE,
}
