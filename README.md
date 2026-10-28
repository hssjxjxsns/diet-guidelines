# 膳食指南 AI App

基于《中国居民膳食指南（2022）》的膳食规划与记录应用。核心目标是让 **AI 负责理解与解释、计算引擎负责可追溯的数值**，并支持补录、修正与多次加餐。

仓库根目录只放三个东西：后端包、前端包、文档。

```text
backend/        Java 17 + Spring Boot 3（端口 9000，接口前缀 /api）
  src/          领域、营养、服务、JDBC 仓储、REST 控制器
  src/main/resources/schema.sql + seed.sql   内置数据库结构与种子数据
  data/         内置数据库文件（首次启动自动生成，不进 Git）
frontend/       Expo + React Native + TypeScript（Dev Server 端口 3001）
docs/           产品说明书、技术架构、AI 策略
README.md
```

## 技术栈

| 层 | 选型 |
| --- | --- |
| 前端 | Expo SDK 51 + React Native + TypeScript，Dev Server `3001` |
| 后端 | Java 17 + Spring Boot 3.3（Web / Validation / JDBC）+ Maven，端口 `9000` |
| 数据库 | **内置 H2 文件库**（`backend/data/diet-guide.mv.db`），随后端进程启动，无需安装数据库 |
| 鉴权 | 本地单用户模式，所有请求归属内置用户 `local-user`，无需登录 |
| 对话模型 | 智谱 `glm-5.3-flash`（多模态） |
| 测试 | JUnit 5 + `tsc --noEmit` |

## 核心设计

1. **AI 菜品分析**：AI 识别菜品、拆解配方、给出区间估算；最终数值由营养引擎按食物库计算，或明确标注为 `ai_estimated`。
2. **双轨营养结果**：定量营养指标与膳食结构建议（食物组、推荐区间）分别计算、同时校验。
3. **餐次版本链**：`meal_groups` → `meal_entries` → `meal_entry_items`；支持 `append` 补录与 `replace` 修正，统计只取“最新 replace + 其后所有 append”。
4. **多次加餐**：同一自然日可创建多个 `snack` 实例，分别统计。
5. **结构化校验**：R01–R08 规则独立成模块，失败返回 `failed_rules`，AI 定向重生成，最多 2 次后规则引擎兜底。
6. **医疗边界**：只提供膳食记录、营养信息与一般性膳食建议，不判断疾病、不做诊断。

## 本地运行

前置：**JDK 17+**、**Maven 3.6.3+**、Node 20+。不需要 Docker，不需要安装 PostgreSQL / Supabase。

### 1. 启动后端（端口 9000）

```powershell
cd backend
$env:ZHIPU_API_KEY="<你的智谱 Key>"   # 可选，不填则 AI 接口不可用，其余功能正常
mvn spring-boot:run
```

首次启动会自动建库建表并写入种子数据，后续启动幂等执行（`merge`）。

- 数据库文件：`backend/data/diet-guide.mv.db`，删掉即完全重置。
- 如需换路径：设置 `DATABASE_URL=jdbc:h2:file:<路径>;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY`。

后端单测（不需要数据库）：

```bash
cd backend
mvn test
```

### 2. 启动前端（端口 3001）

```bash
cd frontend
npm install
cp .env.example .env
# EXPO_PUBLIC_API_BASE_URL=http://localhost:9000/api
npm start
```

`npm start` 已固定 `--port 3001`。地址按运行环境调整：

| 运行环境 | `EXPO_PUBLIC_API_BASE_URL` |
| --- | --- |
| Web / iOS 模拟器 | `http://localhost:9000/api` |
| Android 模拟器 | `http://10.0.2.2:9000/api` |
| 真机（同一 Wi-Fi） | `http://<电脑局域网 IP>:9000/api` |

## 构建

```bash
cd backend
mvn -DskipTests package     # 产出 target/diet-guide-server-0.1.0.jar
java -jar target/diet-guide-server-0.1.0.jar
```

部署时只需提供 `PORT`（默认 9000）与 `ZHIPU_API_KEY`；数据库文件随包目录自动创建，备份 `backend/data/` 即可完成数据迁移。

## API

本地单用户模式，不需要鉴权头。基地址 `http://localhost:9000/api`。

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

- 统计只按“最新 `replace` + 其后所有 `append`”的有效集合计算，由 `MealVersion.effective()` 统一实现，禁止直接聚合 `meal_entries` 全表。
- `daily_stats` 保存当日汇总；历史记录变更后将覆盖该日期的 `period_reports` 标记为 `stale`。
- 报告中的数字来自统计结果，AI 只负责文字总结。
- 数组类字段（`aliases` / `tags` / `allergens`）与 JSON 字段在 H2 中以 JSON 文本存储，读写统一走 `JsonCodec`。

## 安全

- 智谱 API Key 仅存在于后端环境变量，不进入客户端、日志或仓库。
- 本地单用户模式没有登录体系；若未来对外开放，需要重新引入账号体系与数据隔离（数据表已预留 `user_id`）。
- 健康与饮食数据按敏感个人信息处理，数据全部保存在本机 `backend/data/` 内，不对外上传。
