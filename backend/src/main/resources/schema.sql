-- Diet Guidelines AI - 内置 H2 数据库结构
-- 首次启动自动建表；每次启动执行 if not exists，可重复执行。
-- 数据文件默认位于 backend/data/diet-guide.mv.db。

create table if not exists profiles (
  id varchar(64) primary key,
  username varchar(128),
  age integer,
  gender varchar(16),
  height_cm numeric,
  weight_kg numeric,
  activity_level varchar(32),
  created_at timestamp with time zone not null default current_timestamp,
  updated_at timestamp with time zone not null default current_timestamp
);

create table if not exists user_settings (
  user_id varchar(64) primary key,
  daily_targets clob not null default '{}',
  preferences clob not null default '[]',
  allergens clob not null default '[]',
  avoid clob not null default '[]',
  updated_at timestamp with time zone not null default current_timestamp
);

create table if not exists nutrition_targets (
  id varchar(64) primary key,
  age_min integer not null,
  age_max integer not null,
  gender varchar(16),
  activity_level varchar(32),
  nutrients clob not null,
  version varchar(32) not null,
  created_at timestamp with time zone not null default current_timestamp
);

create table if not exists nutrition_foods (
  id varchar(64) primary key,
  name varchar(128) not null,
  aliases clob not null default '[]',
  category varchar(32) not null,
  nutrients_per_100g clob not null default '{}',
  edible_ratio numeric not null default 1,
  source varchar(128),
  source_version varchar(64),
  status varchar(32) not null default 'active',
  tags clob not null default '[]',
  allergens clob not null default '[]',
  unit_conversion clob,
  created_at timestamp with time zone not null default current_timestamp,
  updated_at timestamp with time zone not null default current_timestamp
);

create table if not exists recipes (
  id varchar(64) primary key,
  name varchar(128) not null,
  servings numeric not null default 1,
  yield_g numeric not null default 0,
  oil_g numeric not null default 0,
  cooking_method varchar(64),
  source varchar(128),
  status varchar(32) not null default 'active',
  tags clob not null default '[]',
  allergens clob not null default '[]',
  created_at timestamp with time zone not null default current_timestamp
);

create table if not exists recipe_ingredients (
  id varchar(64) primary key,
  recipe_id varchar(64) not null,
  food_id varchar(64) not null,
  amount_g numeric not null
);

create table if not exists recipe_candidates (
  id varchar(64) primary key,
  user_id varchar(64),
  dish_name varchar(128) not null,
  proposed_recipe clob not null,
  confidence numeric not null,
  source varchar(32) not null default 'ai_inferred',
  status varchar(32) not null default 'pending_review',
  reviewed_by varchar(64),
  created_at timestamp with time zone not null default current_timestamp
);

create table if not exists plan_sessions (
  id varchar(64) primary key,
  user_id varchar(64) not null,
  meal_date date not null,
  meal_type varchar(16) not null,
  status varchar(32) not null default 'planning',
  context_snapshot clob not null default '{}',
  validation_result clob,
  created_at timestamp with time zone not null default current_timestamp,
  updated_at timestamp with time zone not null default current_timestamp
);

create table if not exists plan_items (
  id varchar(64) primary key,
  session_id varchar(64) not null,
  food_id varchar(64),
  recipe_id varchar(64),
  name varchar(128) not null,
  amount_g numeric not null,
  food_group varchar(32) not null,
  tags clob not null default '[]',
  allergens clob not null default '[]',
  reason clob,
  citations clob not null default '[]',
  rejected boolean not null default false
);

create table if not exists meal_groups (
  id varchar(64) primary key,
  user_id varchar(64) not null,
  meal_date date not null,
  meal_type varchar(16) not null,
  instance_seq integer not null default 1,
  status varchar(16) not null default 'active',
  created_at timestamp with time zone not null default current_timestamp,
  constraint uq_meal_groups unique (user_id, meal_date, meal_type, instance_seq)
);

create table if not exists meal_entries (
  id varchar(64) primary key,
  group_id varchar(64) not null,
  user_id varchar(64) not null,
  mode varchar(16) not null,
  revision integer not null,
  base_revision integer,
  supersedes_entry_id varchar(64),
  is_current boolean not null default true,
  totals clob not null default '{}',
  value_type varchar(16) not null default 'measured',
  nutrition_data_version varchar(64),
  idempotency_key varchar(128),
  created_at timestamp with time zone not null default current_timestamp,
  constraint uq_meal_entries_revision unique (group_id, revision)
);

create table if not exists meal_entry_items (
  id varchar(64) primary key,
  entry_id varchar(64) not null,
  food_id varchar(64),
  recipe_id varchar(64),
  raw_name varchar(128) not null,
  amount_g numeric not null,
  value_type varchar(16) not null,
  nutrients clob not null default '{}',
  analysis clob
);

create table if not exists daily_stats (
  user_id varchar(64) not null,
  stat_date date not null,
  metrics clob not null default '{}',
  structure clob not null default '{}',
  value_type varchar(16) not null default 'measured',
  source_hash varchar(128),
  computed_at timestamp with time zone not null default current_timestamp,
  primary key (user_id, stat_date)
);

create table if not exists period_reports (
  id varchar(64) primary key,
  user_id varchar(64) not null,
  period_type varchar(16) not null,
  period_start date not null,
  period_end date not null,
  stats clob not null default '{}',
  ai_summary clob,
  status varchar(16) not null default 'fresh',
  input_hash varchar(128),
  generated_at timestamp with time zone not null default current_timestamp,
  constraint uq_period unique (user_id, period_type, period_start)
);

create table if not exists inferred_trends (
  id varchar(64) primary key,
  user_id varchar(64) not null,
  metric varchar(64) not null,
  conclusion clob not null,
  status varchar(16) not null default 'candidate',
  evidence clob not null default '{}',
  window_start date,
  window_end date,
  sample_meals integer not null default 0,
  first_seen timestamp with time zone not null default current_timestamp,
  last_verified timestamp with time zone,
  invalidated_at timestamp with time zone,
  constraint uq_trend_metric unique (user_id, metric)
);

create table if not exists validation_rules (
  rule_id varchar(64) primary key,
  severity varchar(16) not null,
  threshold clob not null default '{}',
  weight clob not null default '{}',
  version varchar(32) not null,
  enabled boolean not null default true
);

create table if not exists guideline_chunks (
  id varchar(64) primary key,
  content clob not null,
  metadata clob not null default '{}',
  content_hash varchar(128),
  status varchar(16) not null default 'active',
  created_at timestamp with time zone not null default current_timestamp
);

-- 内置向量：直接把 embedding 存在同一个库里，检索时在内存算余弦相似度
alter table guideline_chunks add column if not exists embedding clob;
alter table guideline_chunks add column if not exists embedding_model varchar(64);
alter table guideline_chunks add column if not exists embedded_at timestamp with time zone;

create table if not exists ai_call_logs (
  id varchar(64) primary key,
  trace_id varchar(64),
  user_id varchar(64),
  scene varchar(64) not null,
  model varchar(64) not null,
  prompt_tokens integer not null default 0,
  completion_tokens integer not null default 0,
  latency_ms integer not null default 0,
  status varchar(16) not null,
  prompt_version varchar(32),
  created_at timestamp with time zone not null default current_timestamp
);

create table if not exists idempotency_keys (
  user_id varchar(64) not null,
  key varchar(128) not null,
  scope varchar(32) not null,
  request_hash varchar(128) not null,
  response clob,
  status varchar(16) not null default 'processing',
  created_at timestamp with time zone not null default current_timestamp,
  primary key (user_id, key)
);

create table if not exists outbox_events (
  id varchar(64) primary key,
  aggregate_type varchar(64) not null,
  aggregate_id varchar(64) not null,
  event_type varchar(64) not null,
  payload clob not null default '{}',
  processed_at timestamp with time zone,
  created_at timestamp with time zone not null default current_timestamp
);

create index if not exists idx_plan_sessions_user_date on plan_sessions (user_id, meal_date);
create index if not exists idx_meal_entries_group on meal_entries (group_id, revision);
create index if not exists idx_meal_entry_items_entry on meal_entry_items (entry_id);
