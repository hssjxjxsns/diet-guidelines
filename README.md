# 膳食指南 AI App

基于《中国居民膳食指南（2022）》的膳食规划与记录后端。核心目标是让 **AI 负责理解与解释、计算引擎负责可追溯的数值**，并支持补录、修正与多次加餐。

## 核心设计

1. **AI 菜品分析**：AI 识别菜品、拆解配方、给出区间估算；最终数值由营养引擎按食物库计算，或明确标注为 `ai_estimated`。
2. **双轨营养结果**：定量营养指标（能量、蛋白质、脂肪、碳水等）与膳食结构建议（食物组、推荐区间）分别计算、同时校验。
3. **餐次版本链**：`meal_groups`（餐次组）→ `meal_entries`（提交/修改版本）→ `meal_entry_items`（明细）。支持 `append` 补录与 `replace` 修正；统计只取“最新 replace + 其后所有 append”。
4. **多次加餐**：同一自然日可创建多个 `snack` 实例，分别统计。
5. **结构化校验**：R01–R08 规则独立成模块，失败时返回 `failed_rules`，AI 依此定向重生成，最多 2 次后由规则引擎兜底。
6. **医疗边界**：只提供膳食记录、营养信息与一般性膳食建议，不判断疾病、不做诊断。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | TypeScript（Node 20+ / Deno） |
| 数据库 | Supabase PostgreSQL 15+ + pgvector |
| 后端接口 | Supabase Edge Functions |
| 对话模型 | 智谱 `glm-5.3-flash`（1M 上下文，多模态） |
| 向量模型 | 智谱 `embedding-3`，固定 1024 维 |
| 测试 | Vitest |

## 目录结构

```text
src/
  ai/            # 智谱 Gateway、Schema、Prompt
  dish/          # AI 菜品分析器
  nutrition/     # 营养计算、结构评估、校验规则引擎
  domain/        # 餐次版本规则
  context/       # 规划上下文构建（不发送原始流水账）
  services/      # 规划、餐次、报告、画像
  adapters/      # Supabase 仓储实现
  shared/        # 类型、错误、哈希
supabase/
  migrations/    # 数据库迁移（表、索引、RLS、视图、RAG 函数）
  seed.sql       # 食物与配方种子数据
  functions/     # Edge Functions
tests/           # 单元与集成测试
```

## 本地运行

```bash
npm install
cp .env.example .env
npm run typecheck
npm test
```

当前仓库不包含移动端工程，接口通过 Edge Functions 暴露。

## Supabase

```bash
supabase start
supabase db reset          # 应用 migrations 与 seed
supabase functions serve   # 本地运行 Edge Functions
```

配置服务端密钥（不要提交到仓库）：

```bash
supabase secrets set ZHIPU_API_KEY=...
supabase secrets set ZHIPU_BASE_URL=https://open.bigmodel.cn/api/paas/v4
supabase secrets set ZHIPU_CHAT_MODEL=glm-5.3-flash
supabase secrets set ZHIPU_EMBEDDING_MODEL=embedding-3
supabase secrets set EMBEDDING_DIMENSIONS=1024
```

部署：

```bash
supabase db push
supabase functions deploy plan-sessions
supabase functions deploy meal-groups
supabase functions deploy reports
supabase functions deploy settings
supabase functions deploy foods-parse
```

## API

所有接口以 `Authorization: Bearer <supabase_jwt>` 鉴权。

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

提交记录示例：

```json
{
  "mode": "append",
  "idempotency_key": "b0c1f1c4-6c2a-4f0e-9f1e-2b7a9d3c8e11",
  "items": [
    { "raw_name": "米饭", "food_id": "f_rice_001", "amount_g": 200 }
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
    { "raw_name": "米饭", "food_id": "f_rice_001", "amount_g": 150 }
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
npm test
```

覆盖：标准食物换算、复合菜品估算、AI 区间估算、动态营养范围、过敏原/忌口/结构/替换校验、补录与修正版本链、幂等、多次加餐、上下文构建、周期统计与趋势状态。

## 安全

- 智谱 API Key 仅存在于服务端 Secret，不进入客户端、日志或仓库。
- 所有业务表启用 RLS，按 `auth.uid()` 隔离。
- `idempotency_keys`、`ai_call_logs`、`outbox_events` 仅服务角色可访问。
- 健康与饮食数据按敏感个人信息处理，提供导出与删除能力是上线前必做项。
