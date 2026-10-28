# BookMind 书思 · 书籍智能评价系统

基于 Spring Boot + LLM 的书籍评价分析系统：聚合读者评论，通过大模型进行情感分析与观点挖掘，并以可视化图表呈现，同时提供书单管理、书评社区、AI 荐书等完整功能。

未配置 LLM API Key 时，系统自动降级为关键词匹配模式，核心功能不受影响。

## 功能特性

- **书库浏览**：书籍目录、详情、读者评论、相似书籍推荐
- **评论分析**：情感分析、评论趋势、来源分布、关键词趋势（词云）、时段分析
- **书籍对比**：多书多维对比分析
- **AI Agent**：`agent/analyze` 智能分析、`ai/understand` 语义理解、润色书评
- **AI 荐书**：对话式图书推荐（recommend-chat）
- **数据图表**：销量排行等基于 ECharts 的可视化
- **报告导出**：评论数据与分析报告导出
- **用户体系**：注册登录、发表/管理书评、收藏、用户徽章
- **我的书单**：创建书单、书籍匹配、书单管理

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.3.5、Java 17、MyBatis |
| 数据库 | MySQL 8（启动时自动建表并导入初始数据） |
| 前端 | Vue 3（CDN 引入）、ECharts + 词云 |
| AI | 阿里云 DashScope（qwen-plus），支持关键词匹配降级 |

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8+
- Maven 3.6+

### 1. 创建数据库

```sql
CREATE DATABASE book_eval DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

### 2. 修改配置

编辑 `book-evaluation-system/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    password: 你的MySQL密码

llm:
  api-key: "你的 DashScope API Key"   # 可选，留空则使用关键词匹配
```

### 3. 启动

```bash
cd book-evaluation-system
mvn spring-boot:run
```

或打包后运行：

```bash
mvn clean package -DskipTests
java -jar target/book-evaluation-system-1.0.0.jar
```

访问 http://localhost:8080

> **Windows 中文系统注意**：若启动后页面中文乱码，是 SQL 初始化脚本被按 GBK 读取所致。请删除数据库重建，并添加启动参数：
>
> ```bash
> java -jar target/book-evaluation-system-1.0.0.jar --spring.sql.init.encoding=UTF-8
> ```

## 项目结构

```
book-evaluation-system
├── src/main/java/com/book/eval
│   ├── controller/      # REST API（书籍、分析、Agent、书单等）
│   ├── mapper/          # MyBatis 数据访问层
│   └── service/         # LLM 调用、评论分析、Agent、Prompt 模板
└── src/main/resources
    ├── application.yml  # 数据源与 LLM 配置
    ├── schema.sql       # 建表脚本（启动自动执行）
    ├── data.sql         # 初始数据（启动自动执行）
    └── static/          # 单页前端（Vue 3 + ECharts）
```

## 主要 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/books` | 书籍列表 |
| GET | `/api/book-detail` | 书籍详情与评论 |
| POST | `/api/analyze-comments` | 评论情感分析 |
| POST | `/api/compare-books` | 多书对比 |
| POST | `/api/agent/analyze` | AI Agent 分析 |
| POST | `/api/recommend-chat` | 对话式荐书 |
| POST | `/api/submit-review` | 发表书评 |
| POST | `/api/polish-review` | AI 润色书评 |
| GET | `/api/export-report` | 导出分析报告 |

完整接口见 [ApiController.java](book-evaluation-system/src/main/java/com/book/eval/controller/ApiController.java)。
