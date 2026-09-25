-- IM schema v1
CREATE DATABASE IF NOT EXISTS im DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE im;

CREATE TABLE IF NOT EXISTS `user` (
  `uid`           VARCHAR(32)  NOT NULL,
  `username`      VARCHAR(64)  NOT NULL,
  `password_hash` VARCHAR(128) NOT NULL,
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT '',
  `avatar_url`    VARCHAR(512) NOT NULL DEFAULT '',
  `created_at`    BIGINT       NOT NULL,
  PRIMARY KEY (`uid`),
  UNIQUE KEY `uk_username` (`username`)
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
  `group_id`   VARCHAR(64) NOT NULL,
  `name`       VARCHAR(64) NOT NULL,
  `owner_uid`  VARCHAR(32) NOT NULL,
  `avatar_url` VARCHAR(512) NOT NULL DEFAULT '',
  `created_at` BIGINT      NOT NULL,
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
