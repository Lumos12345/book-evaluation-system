-- 书籍智能评价系统 - 建表脚本
CREATE TABLE IF NOT EXISTS books (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '书籍ID',
    title VARCHAR(200) NOT NULL COMMENT '书名',
    author VARCHAR(100) COMMENT '作者',
    genre VARCHAR(50) COMMENT '类型',
    total_sales INT DEFAULT 0 COMMENT '总销量',
    total_rating DECIMAL(3,1) DEFAULT 0 COMMENT '平台评分',
    rating_count INT DEFAULT 0 COMMENT '评分人数',
    price DECIMAL(8,2) DEFAULT 0 COMMENT '定价',
    description TEXT COMMENT '简介',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='书籍表';

CREATE TABLE IF NOT EXISTS book_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '评论ID',
    book_id BIGINT NOT NULL COMMENT '书籍ID',
    content TEXT COMMENT '评论内容',
    source VARCHAR(50) COMMENT '来源',
    rating DECIMAL(2,1) COMMENT '评分',
    sentiment VARCHAR(20) COMMENT '情感倾向',
    sentiment_score DECIMAL(3,2) COMMENT '情感得分',
    keywords TEXT COMMENT '关键词(逗号分隔)',
    author VARCHAR(50) COMMENT '评论者',
    like_count INT DEFAULT 0 COMMENT '点赞数',
    reply_count INT DEFAULT 0 COMMENT '回复数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_book_id (book_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='书籍评论表';

-- 用户表（登录注册）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(64) NOT NULL COMMENT '密码哈希(SHA-256)',
    salt VARCHAR(32) NOT NULL COMMENT '盐值',
    nickname VARCHAR(50) COMMENT '昵称',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 演示账号：demo / 123456（已存在则跳过）
INSERT INTO users(username, password_hash, salt, nickname)
SELECT 'demo', SHA2(CONCAT('demoSalt', ':', '123456'), 256), 'demoSalt', '演示用户'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'demo');

-- 收藏书架表（用户收藏的书籍，重启不丢失）
CREATE TABLE IF NOT EXISTS favorites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '收藏ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    book_id BIGINT NOT NULL COMMENT '书籍ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_book (user_id, book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收藏书架表';

-- 自定义书库表（用户导入生成的书库，book_ids 为JSON数组字符串）
CREATE TABLE IF NOT EXISTS libraries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '书库ID',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    name VARCHAR(100) NOT NULL COMMENT '书库名称',
    book_ids TEXT COMMENT '书籍ID列表(JSON数组)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义书库表';

-- 用户投稿书评表（AI 自动打分，与种子评论数据分离，重启不丢失）
CREATE TABLE IF NOT EXISTS user_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '投稿ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    book_id BIGINT NOT NULL COMMENT '书籍ID',
    book_title VARCHAR(200) COMMENT '书名快照',
    content TEXT NOT NULL COMMENT '书评内容',
    rating DECIMAL(2,1) COMMENT '用户评分(1-5,选填)',
    sentiment VARCHAR(20) COMMENT 'AI情感倾向',
    sentiment_score DECIMAL(3,2) COMMENT 'AI情感得分',
    keywords TEXT COMMENT 'AI提取关键词(逗号分隔)',
    ai_comment VARCHAR(500) COMMENT 'AI一句话点评',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_book_id (book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户投稿书评表';
