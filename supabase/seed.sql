-- Seed food data (values per 100g, based on the Chinese Food Composition Table style).
-- Replace with the licensed edition before production use.

insert into public.nutrition_foods
  (id, name, aliases, category, nutrients_per_100g, edible_ratio, source, source_version, status, tags, allergens)
values
  ('f_rice_001', '米饭', array['白米饭', '蒸米饭'], 'grains',
   '{"energyKcal":116,"proteinG":2.6,"fatG":0.3,"carbG":25.9,"fiberG":0.3,"sodiumMg":2.4,"calciumMg":7,"ironMg":0.3}', 1,
   '中国食物成分表', 'demo-1', 'active', array['vegetarian'], array[]),
  ('f_broccoli_001', '西兰花', array['绿花菜'], 'vegetables',
   '{"energyKcal":34,"proteinG":2.8,"fatG":0.6,"carbG":6.6,"fiberG":1.6,"sodiumMg":18,"calciumMg":67,"ironMg":1.0}', 1,
   '中国食物成分表', 'demo-1', 'active', array['vegetarian'], array[]),
  ('f_chicken_001', '鸡胸肉', array['鸡胸'], 'protein',
   '{"energyKcal":133,"proteinG":24.6,"fatG":2.5,"carbG":0,"fiberG":0,"sodiumMg":45,"calciumMg":3,"ironMg":0.6}', 1,
   '中国食物成分表', 'demo-1', 'active', array['meat'], array[]),
  ('f_pork_rib_001', '猪肋排', array['排骨'], 'protein',
   '{"energyKcal":278,"proteinG":16.7,"fatG":23.1,"carbG":0.7,"fiberG":0,"sodiumMg":62,"calciumMg":14,"ironMg":1.4}', 1,
   '中国食物成分表', 'demo-1', 'active', array['meat'], array[]),
  ('f_egg_001', '鸡蛋', array['蛋'], 'protein',
   '{"energyKcal":144,"proteinG":13.3,"fatG":8.8,"carbG":2.8,"fiberG":0,"sodiumMg":131,"calciumMg":56,"ironMg":2.0}', 0.88,
   '中国食物成分表', 'demo-1', 'active', array['vegetarian'], array['蛋']),
  ('f_milk_001', '牛奶', array['纯牛奶'], 'dairy',
   '{"energyKcal":54,"proteinG":3.0,"fatG":3.2,"carbG":3.4,"fiberG":0,"sodiumMg":37,"calciumMg":104,"ironMg":0.1}', 1,
   '中国食物成分表', 'demo-1', 'active', array['vegetarian'], array['奶']),
  ('f_tofu_001', '北豆腐', array['豆腐'], 'soy_nuts',
   '{"energyKcal":116,"proteinG":12.2,"fatG":4.8,"carbG":2.0,"fiberG":0.5,"sodiumMg":7,"calciumMg":138,"ironMg":2.5}', 1,
   '中国食物成分表', 'demo-1', 'active', array['vegetarian'], array['大豆']),
  ('f_apple_001', '苹果', array['红富士'], 'fruits',
   '{"energyKcal":53,"proteinG":0.4,"fatG":0.2,"carbG":13.7,"fiberG":1.7,"sodiumMg":1.6,"calciumMg":4,"ironMg":0.6}', 0.85,
   '中国食物成分表', 'demo-1', 'active', array['vegetarian'], array[]),
  ('f_peanut_001', '花生', array['花生米'], 'soy_nuts',
   '{"energyKcal":574,"proteinG":24.8,"fatG":44.3,"carbG":21.7,"fiberG":7.7,"sodiumMg":3.6,"calciumMg":39,"ironMg":2.1}', 1,
   '中国食物成分表', 'demo-1', 'active', array['vegetarian'], array['花生']),
  ('f_oil_001', '植物油', array['食用油'], 'oils',
   '{"energyKcal":899,"proteinG":0,"fatG":99.9,"carbG":0,"fiberG":0,"sodiumMg":0,"calciumMg":9,"ironMg":0.1}', 1,
   '中国食物成分表', 'demo-1', 'active', array['vegetarian'], array[])
on conflict (id) do update set
  name = excluded.name,
  aliases = excluded.aliases,
  category = excluded.category,
  nutrients_per_100g = excluded.nutrients_per_100g,
  edible_ratio = excluded.edible_ratio,
  source = excluded.source,
  source_version = excluded.source_version,
  status = excluded.status,
  tags = excluded.tags,
  allergens = excluded.allergens;

insert into public.recipes
  (id, name, servings, yield_g, oil_g, cooking_method, source, status, tags, allergens)
values
  ('r_sparerib_001', '糖醋排骨', 1, 150, 15, '红烧', '常见家常做法', 'active', array['meat'], array[]),
  ('r_tomato_egg_001', '番茄炒蛋', 1, 220, 10, '炒', '常见家常做法', 'active', array['vegetarian'], array['蛋'])
on conflict (id) do update set
  name = excluded.name,
  servings = excluded.servings,
  yield_g = excluded.yield_g,
  oil_g = excluded.oil_g,
  cooking_method = excluded.cooking_method,
  status = excluded.status,
  tags = excluded.tags,
  allergens = excluded.allergens;

insert into public.recipe_ingredients (recipe_id, food_id, amount_g)
select 'r_sparerib_001', 'f_pork_rib_001', 120
where not exists (
  select 1 from public.recipe_ingredients where recipe_id = 'r_sparerib_001' and food_id = 'f_pork_rib_001'
);

insert into public.recipe_ingredients (recipe_id, food_id, amount_g)
select 'r_tomato_egg_001', 'f_egg_001', 120
where not exists (
  select 1 from public.recipe_ingredients where recipe_id = 'r_tomato_egg_001' and food_id = 'f_egg_001'
);

insert into public.validation_rules (rule_id, severity, threshold, weight, version, enabled)
values
  ('R01_ALLERGEN', 'hard', '{}', '{}', '2026-10-26.1', true),
  ('R02_AVOID', 'hard', '{}', '{}', '2026-10-26.1', true),
  ('R03_FOOD_VALID', 'hard', '{}', '{}', '2026-10-26.1', true),
  ('R04_NUTRIENT_RANGE', 'hard', '{"tolerance":0.2}', '{"breakfast":0.25,"lunch":0.35,"dinner":0.3,"snack":0.1}', '2026-10-26.1', true),
  ('R05_STRUCTURE', 'hard', '{"minVegetableG":{"breakfast":50,"lunch":100,"dinner":100,"snack":0}}', '{}', '2026-10-26.1', true),
  ('R06_REPLACE_REQUEST', 'hard', '{}', '{}', '2026-10-26.1', true),
  ('R07_DUPLICATE', 'soft', '{}', '{}', '2026-10-26.1', true),
  ('R08_SCOPE', 'hard', '{}', '{}', '2026-10-26.1', true)
on conflict (rule_id) do update set
  severity = excluded.severity,
  threshold = excluded.threshold,
  weight = excluded.weight,
  version = excluded.version,
  enabled = excluded.enabled;
