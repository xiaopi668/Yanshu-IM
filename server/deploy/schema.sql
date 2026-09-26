-- IM schema v2（雁书二期：雁书号 / 通讯录 / 朋友圈 / 归档 / 管理后台）
CREATE DATABASE IF NOT EXISTS im DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE im;

CREATE TABLE IF NOT EXISTS `user` (
  `uid`           VARCHAR(32)  NOT NULL,
  `username`      VARCHAR(64)  NOT NULL,
  `password_hash` VARCHAR(128) NOT NULL,
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT '',
  `avatar_url`    VARCHAR(512) NOT NULL DEFAULT '',
  `yid`           VARCHAR(32)  NULL,     -- 雁书号（对外唯一 ID，可改一次）
  `yid_changed`   TINYINT      NOT NULL DEFAULT 0,
  `email`         VARCHAR(128) NULL,     -- 绑定邮箱
  `created_at`    BIGINT       NOT NULL,
  PRIMARY KEY (`uid`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_yid` (`yid`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `friend` (
  `owner_uid`  VARCHAR(32) NOT NULL,
  `friend_uid` VARCHAR(32) NOT NULL,
  `remark`     VARCHAR(64) NOT NULL DEFAULT '',
  `created_at` BIGINT      NOT NULL,
  PRIMARY KEY (`owner_uid`, `friend_uid`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `conversation` (
  `conversation_id` VARCHAR(64) NOT NULL,
  `type`            VARCHAR(16) NOT NULL, -- single / group
  `created_at`      BIGINT      NOT NULL,
  PRIMARY KEY (`conversation_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `conversation_member` (
  `conversation_id` VARCHAR(64) NOT NULL,
  `uid`             VARCHAR(32) NOT NULL,
  `role`            VARCHAR(16) NOT NULL DEFAULT 'member',
  `joined_at`       BIGINT      NOT NULL,
  `read_seq`        BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`conversation_id`, `uid`),
  KEY `idx_uid` (`uid`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `group_info` (
  `group_id`    VARCHAR(64) NOT NULL,
  `name`        VARCHAR(64) NOT NULL,
  `owner_uid`   VARCHAR(32) NOT NULL,
  `avatar_url`  VARCHAR(512) NOT NULL DEFAULT '',
  `announcement` MEDIUMTEXT NULL,
  `created_at`  BIGINT      NOT NULL,
  PRIMARY KEY (`group_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `message` (
  `server_msg_id`   VARCHAR(32) NOT NULL,
  `conversation_id` VARCHAR(64) NOT NULL,
  `seq`             BIGINT UNSIGNED NOT NULL,
  `from_uid`        VARCHAR(32) NOT NULL,
  `msg_type`        INT         NOT NULL,
  `text`            MEDIUMTEXT,
  `attachment`      TEXT,
  `mention_uids`    TEXT,
  `sent_at`         BIGINT      NOT NULL,
  PRIMARY KEY (`conversation_id`, `seq`),
  UNIQUE KEY `uk_msg_id` (`server_msg_id`)
) ENGINE=InnoDB;

-- 客户端消息幂等去重表
CREATE TABLE IF NOT EXISTS `message_client_map` (
  `client_msg_id` VARCHAR(64)  NOT NULL,
  `from_uid`      VARCHAR(32)  NOT NULL,
  `server_msg_id` VARCHAR(32)  NOT NULL,
  `seq`           BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`client_msg_id`, `from_uid`)
) ENGINE=InnoDB;

-- ============ 二期新增 ============

-- 通讯录：好友申请流程
CREATE TABLE IF NOT EXISTS `contact_request` (
  `id`         VARCHAR(32)  NOT NULL,
  `from_uid`   VARCHAR(32)  NOT NULL,
  `to_uid`     VARCHAR(32)  NOT NULL,
  `message`    VARCHAR(256) NOT NULL DEFAULT '',
  `status`     VARCHAR(16)  NOT NULL DEFAULT 'pending', -- pending/accepted/rejected
  `created_at` BIGINT       NOT NULL,
  `updated_at` BIGINT       NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_to_status` (`to_uid`, `status`),
  KEY `idx_from` (`from_uid`),
  UNIQUE KEY `uk_pair_pending` (`from_uid`, `to_uid`)
) ENGINE=InnoDB;

-- 朋友圈
CREATE TABLE IF NOT EXISTS `moment` (
  `id`         VARCHAR(32) NOT NULL,
  `uid`        VARCHAR(32) NOT NULL,
  `text`       MEDIUMTEXT,
  `images`     TEXT,               -- JSON: ["image/xxx_key", ...]
  `created_at` BIGINT      NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_uid_created` (`uid`, `created_at`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `moment_like` (
  `moment_id`  VARCHAR(32) NOT NULL,
  `uid`        VARCHAR(32) NOT NULL,
  `created_at` BIGINT      NOT NULL,
  PRIMARY KEY (`moment_id`, `uid`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `moment_comment` (
  `id`         VARCHAR(32) NOT NULL,
  `moment_id`  VARCHAR(32) NOT NULL,
  `uid`        VARCHAR(32) NOT NULL,
  `text`       VARCHAR(500) NOT NULL,
  `created_at` BIGINT      NOT NULL,
  KEY `idx_moment` (`moment_id`)
) ENGINE=InnoDB;

-- 用户封禁（管理后台）
CREATE TABLE IF NOT EXISTS `user_state` (
  `uid`      VARCHAR(32) NOT NULL,
  `disabled` TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (`uid`)
) ENGINE=InnoDB;

-- 站点配置（登录方式开关等）
CREATE TABLE IF NOT EXISTS `site_config` (
  `k` VARCHAR(64) PRIMARY KEY,
  `v` TEXT NOT NULL
) ENGINE=InnoDB;

-- OIDC 账号关联
CREATE TABLE IF NOT EXISTS `oidc_user` (
  `provider`  VARCHAR(32) NOT NULL,
  `sub`       VARCHAR(64) NOT NULL,
  `uid`       VARCHAR(32) NOT NULL,
  `email`     VARCHAR(128) NOT NULL DEFAULT '',
  `linked_at` BIGINT NOT NULL,
  PRIMARY KEY (`provider`, `sub`),
  KEY `idx_uid` (`uid`)
) ENGINE=InnoDB;

-- 聊天记录归档任务记录（归档到 S3）
CREATE TABLE IF NOT EXISTS `archive_log` (
  `day`         VARCHAR(8)  NOT NULL, -- 20260926
  `conversation_id` VARCHAR(64) NOT NULL,
  `count`       BIGINT      NOT NULL,
  `object_key`  VARCHAR(256) NOT NULL,
  `created_at`  BIGINT      NOT NULL,
  PRIMARY KEY (`day`, `conversation_id`)
) ENGINE=InnoDB;
