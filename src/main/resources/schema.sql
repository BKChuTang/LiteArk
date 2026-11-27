-- 轻舟 LiteArk V1.1 表结构（SQLite）
-- 迁移说明：V1.0 → V1.1 均为增量新增字段/表，本项目为全新重建，直接建最终结构。

CREATE TABLE IF NOT EXISTS role (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT NOT NULL UNIQUE,
  description TEXT DEFAULT '',
  is_system   INTEGER DEFAULT 0,
  created_at  TEXT DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS user (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  username   TEXT NOT NULL UNIQUE,
  password   TEXT NOT NULL,
  nickname   TEXT DEFAULT '',
  role_id    INTEGER DEFAULT 2,
  quota      INTEGER DEFAULT 21474836480,
  used       INTEGER DEFAULT 0,
  status     INTEGER DEFAULT 1,
  created_at TEXT DEFAULT (datetime('now','localtime')),
  last_login TEXT,
  totp_allowed INTEGER DEFAULT 1,
  FOREIGN KEY (role_id) REFERENCES role(id)
);

CREATE TABLE IF NOT EXISTS file_meta (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id      INTEGER DEFAULT 0,
  owner_id       INTEGER NOT NULL,
  name           TEXT NOT NULL,
  is_dir         INTEGER DEFAULT 0,
  ext            TEXT DEFAULT '',
  size           INTEGER DEFAULT 0,
  storage_key    TEXT DEFAULT '',
  md5            TEXT DEFAULT '',
  version        INTEGER DEFAULT 1,
  is_deleted     INTEGER DEFAULT 0,
  deleted_at     TEXT,
  created_at     TEXT DEFAULT (datetime('now','localtime')),
  updated_at     TEXT DEFAULT (datetime('now','localtime')),
  thumbnail_path TEXT DEFAULT '',
  FOREIGN KEY (owner_id) REFERENCES user(id)
);
CREATE INDEX IF NOT EXISTS idx_file_parent ON file_meta(parent_id, owner_id, is_deleted);

CREATE TABLE IF NOT EXISTS permission (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_type TEXT NOT NULL,
  subject_id   INTEGER NOT NULL,
  folder_id    INTEGER NOT NULL,
  perm_bits    TEXT DEFAULT 'view,download,upload,edit,delete,share',
  inherit      INTEGER DEFAULT 1,
  source_role_id INTEGER DEFAULT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_perm_subject_folder ON permission(subject_type, subject_id, folder_id);

CREATE TABLE IF NOT EXISTS share (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  token      TEXT NOT NULL UNIQUE,
  file_id    INTEGER NOT NULL,
  owner_id   INTEGER NOT NULL,
  permission TEXT DEFAULT 'view',
  password   TEXT DEFAULT '',
  expires_at TEXT,
  created_at TEXT DEFAULT (datetime('now','localtime')),
  revoked    INTEGER DEFAULT 0,
  max_downloads  INTEGER DEFAULT 0,   -- 0 表示不限制
  download_count INTEGER DEFAULT 0,
  share_type     TEXT DEFAULT 'external',  -- external=外部分享 / internal=仅指定内部用户
  target_user_ids TEXT DEFAULT '',         -- 内部分享允许访问的用户 ID，逗号分隔
  FOREIGN KEY (file_id) REFERENCES file_meta(id),
  FOREIGN KEY (owner_id) REFERENCES user(id)
);

CREATE TABLE IF NOT EXISTS share_access_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  share_id    INTEGER NOT NULL,
  token       TEXT DEFAULT '',
  visitor     TEXT DEFAULT '',
  ip          TEXT DEFAULT '',
  ua          TEXT DEFAULT '',
  client_type TEXT DEFAULT 'PC',
  accessed_at TEXT DEFAULT (datetime('now','localtime')),
  FOREIGN KEY (share_id) REFERENCES share(id)
);

CREATE TABLE IF NOT EXISTS operation_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER,
  username    TEXT DEFAULT '',
  action      TEXT NOT NULL,
  resource    TEXT DEFAULT '',
  detail      TEXT DEFAULT '',
  ip          TEXT DEFAULT '',
  client_type TEXT DEFAULT 'PC',
  ua          TEXT DEFAULT '',
  result      TEXT DEFAULT 'success',
  created_at  TEXT DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_log_time ON operation_log(created_at);

CREATE TABLE IF NOT EXISTS upload_task (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  upload_id   TEXT NOT NULL UNIQUE,
  file_md5    TEXT NOT NULL,
  file_name   TEXT NOT NULL,
  total_size  INTEGER NOT NULL,
  chunk_size  INTEGER NOT NULL,
  chunk_total INTEGER NOT NULL,
  parent_id   INTEGER DEFAULT 0,
  uploaded    TEXT DEFAULT '[]',
  owner_id    INTEGER NOT NULL,
  client_type TEXT DEFAULT 'PC',
  status      TEXT DEFAULT 'uploading',
  expire_at   TEXT,
  created_at  TEXT DEFAULT (datetime('now','localtime')),
  updated_at  TEXT DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS doc_version (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  file_id     INTEGER NOT NULL,
  version     INTEGER NOT NULL,
  storage_key TEXT NOT NULL,
  size        INTEGER DEFAULT 0,
  created_at  TEXT DEFAULT (datetime('now','localtime')),
  FOREIGN KEY (file_id) REFERENCES file_meta(id)
);

CREATE TABLE IF NOT EXISTS system_config (
  config_key   TEXT PRIMARY KEY,
  config_value TEXT NOT NULL
);

-- TOTP 二次验证：密钥密文、失败锁定与验证码重放防护
CREATE TABLE IF NOT EXISTS user_totp (
  user_id       INTEGER PRIMARY KEY,
  secret_cipher TEXT NOT NULL DEFAULT '',
  enabled       INTEGER DEFAULT 0,
  failed_count  INTEGER DEFAULT 0,
  locked_until  TEXT,
  last_used_step INTEGER DEFAULT 0,
  created_at    TEXT DEFAULT (datetime('now','localtime')),
  updated_at    TEXT DEFAULT (datetime('now','localtime')),
  FOREIGN KEY (user_id) REFERENCES user(id)
);

-- 一次性恢复码：仅保存加盐哈希，不保存明文
CREATE TABLE IF NOT EXISTS totp_recovery_code (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  code_hash  TEXT NOT NULL,
  used       INTEGER DEFAULT 0,
  used_at    TEXT,
  batch      TEXT DEFAULT '',
  created_at TEXT DEFAULT (datetime('now','localtime')),
  FOREIGN KEY (user_id) REFERENCES user(id)
);
CREATE INDEX IF NOT EXISTS idx_totp_recovery_user ON totp_recovery_code(user_id, used);

-- 信任设备：device_hash 为加盐哈希，不保存原始设备标识
CREATE TABLE IF NOT EXISTS trusted_device (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL,
  device_hash TEXT NOT NULL,
  device_name TEXT DEFAULT '',
  ip          TEXT DEFAULT '',
  client_type TEXT DEFAULT 'PC',
  created_at  TEXT DEFAULT (datetime('now','localtime')),
  expire_at   TEXT,
  FOREIGN KEY (user_id) REFERENCES user(id)
);
CREATE INDEX IF NOT EXISTS idx_trusted_device_user ON trusted_device(user_id, device_hash);
