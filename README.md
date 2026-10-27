# 膳食指南 AI App

基于《中国居民膳食指南（2022）》的膳食规划与记录应用。核心目标是让 **AI 负责理解与解释、计算引擎负责可追溯的数值**，并支持补录、修正与多次加餐。

仓库为单仓多包结构：后端 `apps/server`（Java + Spring Boot），前端 `apps/mobile`（TypeScript + Expo React Native），数据库脚本在根目录 `supabase/`。

## 目录结构

```text
apps/
  server/                     # 后端（Java 17 + Spring Boot 3，Maven）
    src/main/java/com/dietguide/
      ai/                     # 智谱 Gateway、Prompt 模板
      context/                # 规划上下文构建（不发送原始流水账）
      dish/                   # AI 菜品分析器
      nutrition/              # 营养计算、结构评估、校验规则 R01–R08
      domain/                 # 领域模型与餐次版本链
      service/                # 规划、餐次、报表、画像
      adapter/                # JDBC 仓储（PostgreSQL）
      web/                    # REST 控制器、JWT 鉴权、统一错误响应
    src/test/java/            # JUnit 5 单元测试
  mobile/                     # 前端（Expo + React Native + TypeScript）
    src/
      api/                    # 后端接口客户端与 DTO
      lib/                    # Supabase 客户端（会话持久化）
      screens/                # 登录、今日汇总
supabase/
  migrations/                 # 数据库迁移（表、索引、RLS、视图、RAG 函数）
  seed.sql                    # 食物与配方种子数据
package.json                  # 根级脚本
```

## 技术栈

| 层 | 选型 |
| --- | --- |
| 前端 | Expo SDK 51 + React Native + TypeScript |
| 后端 | Java 17 + Spring Boot 3.3（Web / Validation / JDBC）+ Maven |
| 数据库 | PostgreSQL 15+ + pgvector（本地用 Supabase 提供 Auth 与库） |
| 鉴权 | Supabase JWT（后端用 HS256 校验 `sub`） |
| 对话模型 | 智谱 `glm-5.3-flash`（多模态） |
| 向量模型 | 智谱 `embedding-3`，固定 1024 维 |
| 测试 | JUnit 5 + Spring Boot Test |

## 核心设计

1. **AI 菜品分析**：AI 识别菜品、拆解配方、给出区间估算；最终数值由营养引擎按食物库计算，或明确标注为 `ai_estimated`。
2. **双轨营养结果**：定量营养指标（能量、蛋白质、脂肪、碳水等）与膳食结构建议（食物组、推荐区间）分别计算、同时校验。
3. **餐次版本链**：`meal_groups`（餐次组）→ `meal_entries`（提交/修改版本）→ `meal_entry_items`（明细）。支持 `append` 补录与 `replace` 修正；统计只取“最新 replace + 其后所有 append”。
4. **多次加餐**：同一自然日可创建多个 `snack` 实例，分别统计。
5. **结构化校验**：R01–R08 规则独立成模块，失败时返回 `failed_rules`，AI 依此定向重生成，最多 2 次后由规则引擎兜底。
6. **医疗边界**：只提供膳食记录、营养信息与一般性膳食建议，不判断疾病、不做诊断。

## 本地运行

前置：**JDK 17+**、**Maven 3.6.3+**、Node 20+、Docker Desktop（保持运行）、Supabase CLI（`npm i -g supabase`）。

### 0. 安装前端依赖

```bash
npm install
```

### 1. 启动数据库

在仓库根目录：

```bash
supabase init        # 仅首次，生成 supabase/config.toml
supabase start       # 启动 Postgres / Auth / Studio
supabase db reset    # 应用 migrations 与 seed
```

默认端口：

| 服务 | 地址 |
| --- | --- |
| 本地 Postgres | `127.0.0.1:54322` |
| Auth / API | `http://127.0.0.1:54321` |
| Studio（建测试用户、看数据） | `http://127.0.0.1:54323` |

### 2. 启动后端

后端监听 `8080`，所有接口前缀为 `/api`。环境变量可用 IDE 配置，或直接在 PowerShell 中设置：

```powershell
$env:DATABASE_URL="jdbc:postgresql://127.0.0.1:54322/postgres"
$env:DATABASE_USER="postgres"
$env:DATABASE_PASSWORD="postgres"
$env:SUPABASE_JWT_SECRET="super-secret-jwt-token-with-at-least-32-characters-long"
$env:ZHIPU_API_KEY="<你的智谱 Key>"
mvn -f apps/server/pom.xml spring-boot:run
```

`SUPABASE_JWT_SECRET` 必须与 Supabase 项目的 JWT Secret 一致；`supabase start` 输出的默认值就是上面这串。

只跑后端单测（不需要数据库）：

```bash
npm run server:test      # 等价于 mvn -f apps/server/pom.xml test
```

### 3. 启动前端

```bash
cp apps/mobile/.env.example apps/mobile/.env
# 编辑 apps/mobile/.env：
# EXPO_PUBLIC_API_BASE_URL=http://localhost:8080/api
# EXPO_PUBLIC_SUPABASE_URL=http://127.0.0.1:54321
# EXPO_PUBLIC_SUPABASE_ANON_KEY=<supabase start 输出的 anon key>
npm run mobile
```

Android 模拟器访问宿主机用 `http://10.0.2.2:8080/api`；真机调试换成电脑的局域网 IP。前端登录依赖 Supabase Auth，测试账号在 Studio → Authentication → Add user 创建。

## 构建与部署

```bash
npm run server:build     # 产出 apps/server/target/diet-guide-server-0.1.0.jar
java -jar apps/server/target/diet-guide-server-0.1.0.jar
```

部署时通过环境变量提供 `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`、`SUPABASE_JWT_SECRET`、`ZHIPU_API_KEY`。数据库可以是任意带 pgvector 的 PostgreSQL 15+（Supabase 云库最省事）；迁移用 `supabase db push` 或直接执行 `supabase/migrations/*.sql`。

## API

所有接口以 `Authorization: Bearer <supabase_jwt>` 鉴权。前端 `EXPO_PUBLIC_API_BASE_URL` 指向后端根地址（默认 `http://localhost:8080/api`），请求路径直接拼接资源名。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/plan-sessions` | 创建规划会话 |
| POST | `/plan-sessions/{id}/messages` | 换菜/追问，携带 `current_menu` 与 `constraints` |
| POST | `/plan-sessions/{id}/complete` | 结束规划 |
| GET | `/plan-sessions/{id}` | 恢复会话 |
| GET | `/meal-groups?date=` | 当日餐次与有效版本 |
| POST | `/meal-groups` | 创建餐次组（加餐实例） |
| POST | `/meal-groups/{id}/entries` | 提交 `append` / `replace` |
| GET | `/meal-groups/{id}` | 查看版本链 |
| GET | `/reports/{weekly|monthly}?start=` | 周期报告 |
| GET/PUT | `/settings` | 用户明确设置 |
| POST | `/foods-parse` | 文本或照片分析菜品 |

提交记录示例（外层字段为 snake_case，`items` 内为驼峰）：

```json
{
  "mode": "append",
  "idempotency_key": "b0c1f1c4-6c2a-4f0e-9f1e-2b7a9d3c8e11",
  "items": [
    { "rawName": "米饭", "foodId": "f_rice_001", "amountG": 200 }
  ]
}
```

修正整餐需携带当前版本：

```json
{
  "mode": "replace",
  "base_revision": 3,
  "idempotency_key": "5d0e2a77-1a4b-42c9-9a5e-1f0f0c7b2d33",
  "items": [
    { "rawName": "米饭", "foodId": "f_rice_001", "amountG": 150 }
  ]
}
```

`base_revision` 过期返回 `409 MEAL_REVISION_CONFLICT`，并回传当前版本供客户端合并。

## 数据口径

- 统计只读 `v_effective_meal_entries`，禁止直接聚合 `meal_entries` 全表。
- `daily_stats` 保存当日汇总；历史记录变更后将覆盖该日期的 `period_reports` 标记为 `stale`。
- 报告中的数字来自统计结果，AI 只负责文字总结。
- 向量维度固定 1024（pgvector HNSW 对 `vector` 类型上限为 2000 维）。

## 测试

```bash
npm run server:test
```

覆盖：标准食物换算、复合菜品估算、AI 区间估算、动态营养范围、过敏原/忌口/结构/替换校验、补录与修正版本链、幂等、多次加餐、上下文构建、周期统计与趋势状态。

## 安全

- 智谱 API Key 仅存在于服务端环境变量，不进入客户端、日志或仓库。
- 客户端只持有 Supabase `anon key` 与用户 JWT；后端校验 JWT 签名后按 `sub` 隔离数据。
- 所有业务表启用 RLS，按 `auth.uid()` 隔离；服务端使用数据库账号直连，不暴露给客户端。
- `idempotency_keys`、`ai_call_logs`、`outbox_events` 仅服务端可访问。
- 健康与饮食数据按敏感个人信息处理，提供导出与删除能力是上线前必做项。
