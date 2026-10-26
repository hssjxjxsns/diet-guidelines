-- Diet Guidelines AI - initial schema
-- Requires PostgreSQL 15+, pgvector and pg_trgm.

create extension if not exists vector;
create extension if not exists pg_trgm;

-- ---------------------------------------------------------------------------
-- 1. Identity and user settings
-- ---------------------------------------------------------------------------

create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  username text,
  age integer check (age is null or age between 0 and 120),
  gender text check (gender in ('male', 'female', 'other')),
  height_cm numeric check (height_cm is null or height_cm > 0),
  weight_kg numeric check (weight_kg is null or weight_kg > 0),
  activity_level text check (
    activity_level in ('sedentary', 'lightly_active', 'moderately_active', 'very_active')
  ),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.user_settings (
  user_id uuid primary key references public.profiles (id) on delete cascade,
  daily_targets jsonb not null default '{}'::jsonb,
  preferences text[] not null default '{}',
  allergens text[] not null default '{}',
  avoid text[] not null default '{}',
  updated_at timestamptz not null default now()
);

create table if not exists public.nutrition_targets (
  id uuid primary key default gen_random_uuid(),
  age_min integer not null,
  age_max integer not null,
  gender text check (gender in ('male', 'female', 'other', 'any')),
  activity_level text,
  nutrients jsonb not null,
  version text not null,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- 2. Food and recipe database
-- ---------------------------------------------------------------------------

create table if not exists public.nutrition_foods (
  id text primary key,
  name text not null,
  aliases text[] not null default '{}',
  category text not null check (
    category in ('grains', 'vegetables', 'fruits', 'protein', 'dairy', 'soy_nuts', 'oils', 'other')
  ),
  nutrients_per_100g jsonb not null default '{}'::jsonb,
  edible_ratio numeric not null default 1 check (edible_ratio > 0 and edible_ratio <= 1),
  source text,
  source_version text,
  status text not null default 'active' check (status in ('active', 'deprecated', 'pending_review')),
  tags text[] not null default '{}',
  allergens text[] not null default '{}',
  unit_conversion jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.recipes (
  id text primary key,
  name text not null,
  servings numeric not null default 1 check (servings > 0),
  yield_g numeric not null default 0 check (yield_g >= 0),
  oil_g numeric not null default 0 check (oil_g >= 0),
  cooking_method text,
  source text,
  status text not null default 'active' check (status in ('active', 'deprecated', 'pending_review')),
  tags text[] not null default '{}',
  allergens text[] not null default '{}',
  created_at timestamptz not null default now()
);

create table if not exists public.recipe_ingredients (
  id uuid primary key default gen_random_uuid(),
  recipe_id text not null references public.recipes (id) on delete cascade,
  food_id text not null references public.nutrition_foods (id),
  amount_g numeric not null check (amount_g > 0)
);

create table if not exists public.recipe_candidates (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.profiles (id) on delete cascade,
  dish_name text not null,
  proposed_recipe jsonb not null,
  confidence numeric not null check (confidence >= 0 and confidence <= 1),
  source text not null default 'ai_inferred',
  status text not null default 'pending_review'
    check (status in ('pending_review', 'approved', 'rejected')),
  reviewed_by uuid,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- 3. Planning sessions (never counted as intake)
-- ---------------------------------------------------------------------------

create table if not exists public.plan_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  meal_date date not null,
  meal_type text not null check (meal_type in ('breakfast', 'lunch', 'dinner', 'snack')),
  status text not null default 'planning'
    check (status in ('planning', 'awaiting_log', 'logged', 'analyzed', 'abandoned')),
  context_snapshot jsonb not null default '{}'::jsonb,
  validation_result jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.plan_items (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.plan_sessions (id) on delete cascade,
  food_id text references public.nutrition_foods (id),
  recipe_id text references public.recipes (id),
  name text not null,
  amount_g numeric not null check (amount_g > 0),
  food_group text not null,
  tags text[] not null default '{}',
  allergens text[] not null default '{}',
  reason text,
  citations jsonb not null default '[]'::jsonb,
  rejected boolean not null default false
);

create index if not exists idx_plan_sessions_user_date
  on public.plan_sessions (user_id, meal_date desc);

-- ---------------------------------------------------------------------------
-- 4. Meal groups + versioned entries + items
-- ---------------------------------------------------------------------------

create table if not exists public.meal_groups (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  meal_date date not null,
  meal_type text not null check (meal_type in ('breakfast', 'lunch', 'dinner', 'snack')),
  instance_seq integer not null default 1 check (instance_seq > 0),
  status text not null default 'active' check (status in ('active', 'closed')),
  created_at timestamptz not null default now(),
  constraint uq_meal_groups unique (user_id, meal_date, meal_type, instance_seq)
);

create table if not exists public.meal_entries (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.meal_groups (id) on delete cascade,
  user_id uuid not null references public.profiles (id) on delete cascade,
  mode text not null check (mode in ('append', 'replace')),
  revision integer not null check (revision > 0),
  base_revision integer,
  supersedes_entry_id uuid references public.meal_entries (id),
  is_current boolean not null default true,
  totals jsonb not null default '{}'::jsonb,
  value_type text not null default 'measured'
    check (value_type in ('measured', 'estimated', 'ai_estimated')),
  nutrition_data_version text,
  idempotency_key text,
  created_at timestamptz not null default now(),
  constraint uq_meal_entries_revision unique (group_id, revision)
);

create index if not exists idx_meal_entries_group
  on public.meal_entries (group_id, revision desc);

create unique index if not exists uq_meal_entries_current_replace
  on public.meal_entries (group_id)
  where mode = 'replace' and is_current;

create table if not exists public.meal_entry_items (
  id uuid primary key default gen_random_uuid(),
  entry_id uuid not null references public.meal_entries (id) on delete cascade,
  food_id text references public.nutrition_foods (id),
  recipe_id text references public.recipes (id),
  raw_name text not null,
  amount_g numeric not null check (amount_g > 0),
  value_type text not null check (value_type in ('measured', 'estimated', 'ai_estimated')),
  nutrients jsonb not null default '{}'::jsonb,
  analysis jsonb
);

create index if not exists idx_meal_entry_items_entry
  on public.meal_entry_items (entry_id);

-- 统计口径：最新 replace + 其后所有 append。禁止直接聚合整张 meal_entries。
create or replace view public.v_effective_meal_entries
with (security_invoker = true) as
select entry.*
from public.meal_entries entry
where entry.revision >= coalesce(
  (
    select max(replace_entry.revision)
    from public.meal_entries replace_entry
    where replace_entry.group_id = entry.group_id
      and replace_entry.mode = 'replace'
  ),
  0
);

-- ---------------------------------------------------------------------------
-- 5. Statistics, reports and memory
-- ---------------------------------------------------------------------------

create table if not exists public.daily_stats (
  user_id uuid not null references public.profiles (id) on delete cascade,
  stat_date date not null,
  metrics jsonb not null default '{}'::jsonb,
  structure jsonb not null default '{}'::jsonb,
  value_type text not null default 'measured'
    check (value_type in ('measured', 'estimated', 'ai_estimated')),
  source_hash text,
  computed_at timestamptz not null default now(),
  primary key (user_id, stat_date)
);

create table if not exists public.period_reports (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  period_type text not null check (period_type in ('weekly', 'monthly')),
  period_start date not null,
  period_end date not null,
  stats jsonb not null default '{}'::jsonb,
  ai_summary text,
  status text not null default 'fresh' check (status in ('fresh', 'stale')),
  input_hash text,
  generated_at timestamptz not null default now(),
  constraint uq_period unique (user_id, period_type, period_start)
);

create table if not exists public.inferred_trends (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  metric text not null,
  conclusion text not null,
  status text not null default 'candidate'
    check (status in ('candidate', 'verified', 'invalidated')),
  evidence jsonb not null default '{}'::jsonb,
  window_start date,
  window_end date,
  sample_meals integer not null default 0,
  first_seen timestamptz not null default now(),
  last_verified timestamptz,
  invalidated_at timestamptz,
  constraint uq_trend_metric unique (user_id, metric)
);

-- ---------------------------------------------------------------------------
-- 6. Validation rules, guidelines, observability, idempotency
-- ---------------------------------------------------------------------------

create table if not exists public.validation_rules (
  rule_id text primary key,
  severity text not null check (severity in ('hard', 'soft')),
  threshold jsonb not null default '{}'::jsonb,
  weight jsonb not null default '{}'::jsonb,
  version text not null,
  enabled boolean not null default true
);

create table if not exists public.guideline_chunks (
  id uuid primary key default gen_random_uuid(),
  content text not null,
  metadata jsonb not null default '{}'::jsonb,
  embedding vector(1024),
  content_hash text unique,
  status text not null default 'active' check (status in ('active', 'deprecated')),
  created_at timestamptz not null default now()
);

create index if not exists idx_guideline_chunks_embedding
  on public.guideline_chunks
  using hnsw (embedding vector_cosine_ops)
  with (m = 16, ef_construction = 64);

create table if not exists public.ai_call_logs (
  id uuid primary key default gen_random_uuid(),
  trace_id text,
  user_id uuid references public.profiles (id) on delete set null,
  scene text not null,
  model text not null,
  prompt_tokens integer not null default 0,
  completion_tokens integer not null default 0,
  latency_ms integer not null default 0,
  status text not null check (status in ('ok', 'error')),
  prompt_version text,
  created_at timestamptz not null default now()
);

create table if not exists public.idempotency_keys (
  user_id uuid not null references public.profiles (id) on delete cascade,
  key text not null,
  scope text not null,
  request_hash text not null,
  response jsonb,
  status text not null default 'processing' check (status in ('processing', 'completed', 'failed')),
  created_at timestamptz not null default now(),
  primary key (user_id, key)
);

create table if not exists public.outbox_events (
  id uuid primary key default gen_random_uuid(),
  aggregate_type text not null,
  aggregate_id text not null,
  event_type text not null,
  payload jsonb not null default '{}'::jsonb,
  processed_at timestamptz,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- 7. Updated-at triggers and new-user bootstrap
-- ---------------------------------------------------------------------------

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists trg_profiles_updated_at on public.profiles;
create trigger trg_profiles_updated_at
before update on public.profiles
for each row execute function public.set_updated_at();

drop trigger if exists trg_user_settings_updated_at on public.user_settings;
create trigger trg_user_settings_updated_at
before update on public.user_settings
for each row execute function public.set_updated_at();

drop trigger if exists trg_foods_updated_at on public.nutrition_foods;
create trigger trg_foods_updated_at
before update on public.nutrition_foods
for each row execute function public.set_updated_at();

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, username)
  values (new.id, new.raw_user_meta_data ->> 'username')
  on conflict (id) do nothing;

  insert into public.user_settings (user_id)
  values (new.id)
  on conflict (user_id) do nothing;

  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------------
-- 8. Retrieval function for RAG
-- ---------------------------------------------------------------------------

create or replace function public.match_guideline_chunks(
  query_embedding vector(1024),
  match_count integer default 4
)
returns table (
  id uuid,
  content text,
  metadata jsonb,
  similarity double precision
)
language sql
stable
as $$
  select
    chunk.id,
    chunk.content,
    chunk.metadata,
    1 - (chunk.embedding <=> query_embedding) as similarity
  from public.guideline_chunks chunk
  where chunk.status = 'active'
  order by chunk.embedding <=> query_embedding
  limit greatest(match_count, 1);
$$;

-- ---------------------------------------------------------------------------
-- 9. Row Level Security
-- ---------------------------------------------------------------------------

alter table public.profiles enable row level security;
alter table public.user_settings enable row level security;
alter table public.nutrition_targets enable row level security;
alter table public.nutrition_foods enable row level security;
alter table public.recipes enable row level security;
alter table public.recipe_ingredients enable row level security;
alter table public.recipe_candidates enable row level security;
alter table public.plan_sessions enable row level security;
alter table public.plan_items enable row level security;
alter table public.meal_groups enable row level security;
alter table public.meal_entries enable row level security;
alter table public.meal_entry_items enable row level security;
alter table public.daily_stats enable row level security;
alter table public.period_reports enable row level security;
alter table public.inferred_trends enable row level security;
alter table public.validation_rules enable row level security;
alter table public.guideline_chunks enable row level security;
alter table public.ai_call_logs enable row level security;
alter table public.idempotency_keys enable row level security;
alter table public.outbox_events enable row level security;

drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own" on public.profiles
  for select to authenticated using (auth.uid() = id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
  for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);

drop policy if exists "settings_all_own" on public.user_settings;
create policy "settings_all_own" on public.user_settings
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "targets_read_all" on public.nutrition_targets;
create policy "targets_read_all" on public.nutrition_targets
  for select to authenticated using (true);

drop policy if exists "foods_read_all" on public.nutrition_foods;
create policy "foods_read_all" on public.nutrition_foods
  for select to authenticated using (true);

drop policy if exists "recipes_read_all" on public.recipes;
create policy "recipes_read_all" on public.recipes
  for select to authenticated using (true);

drop policy if exists "recipe_ingredients_read_all" on public.recipe_ingredients;
create policy "recipe_ingredients_read_all" on public.recipe_ingredients
  for select to authenticated using (true);

drop policy if exists "recipe_candidates_select_own" on public.recipe_candidates;
create policy "recipe_candidates_select_own" on public.recipe_candidates
  for select to authenticated using (auth.uid() = user_id);

drop policy if exists "plan_sessions_all_own" on public.plan_sessions;
create policy "plan_sessions_all_own" on public.plan_sessions
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "plan_items_all_own" on public.plan_items;
create policy "plan_items_all_own" on public.plan_items
  for all to authenticated
  using (
    exists (
      select 1 from public.plan_sessions session
      where session.id = plan_items.session_id and session.user_id = auth.uid()
    )
  )
  with check (
    exists (
      select 1 from public.plan_sessions session
      where session.id = plan_items.session_id and session.user_id = auth.uid()
    )
  );

drop policy if exists "meal_groups_all_own" on public.meal_groups;
create policy "meal_groups_all_own" on public.meal_groups
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "meal_entries_all_own" on public.meal_entries;
create policy "meal_entries_all_own" on public.meal_entries
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "meal_entry_items_all_own" on public.meal_entry_items;
create policy "meal_entry_items_all_own" on public.meal_entry_items
  for all to authenticated
  using (
    exists (
      select 1 from public.meal_entries entry
      where entry.id = meal_entry_items.entry_id and entry.user_id = auth.uid()
    )
  )
  with check (
    exists (
      select 1 from public.meal_entries entry
      where entry.id = meal_entry_items.entry_id and entry.user_id = auth.uid()
    )
  );

drop policy if exists "daily_stats_all_own" on public.daily_stats;
create policy "daily_stats_all_own" on public.daily_stats
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "period_reports_all_own" on public.period_reports;
create policy "period_reports_all_own" on public.period_reports
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "inferred_trends_all_own" on public.inferred_trends;
create policy "inferred_trends_all_own" on public.inferred_trends
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "validation_rules_read_all" on public.validation_rules;
create policy "validation_rules_read_all" on public.validation_rules
  for select to authenticated using (enabled);

drop policy if exists "guideline_chunks_read_all" on public.guideline_chunks;
create policy "guideline_chunks_read_all" on public.guideline_chunks
  for select to authenticated using (status = 'active');

-- ai_call_logs / idempotency_keys / outbox_events: service role only (no policies).
