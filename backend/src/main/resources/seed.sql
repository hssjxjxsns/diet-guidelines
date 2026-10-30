-- 内置种子数据：首次启动写入，重复启动覆盖为最新值（merge 幂等）。
-- 营养值为每 100g 可食部，参考《中国食物成分表》风格；正式版请替换为授权数据。

merge into profiles (id, username) key (id) values ('local-user', '本地用户');

merge into nutrition_foods
  (id, name, aliases, category, nutrients_per_100g, edible_ratio, source, source_version, status, tags, allergens)
key (id) values
  ('f_rice_001', '米饭', '["白米饭","蒸米饭"]', 'grains',
   '{"energyKcal":116,"proteinG":2.6,"fatG":0.3,"carbG":25.9,"fiberG":0.3,"sodiumMg":2.4,"calciumMg":7,"ironMg":0.3}', 1,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '[]'),
  ('f_broccoli_001', '西兰花', '["绿花菜"]', 'vegetables',
   '{"energyKcal":34,"proteinG":2.8,"fatG":0.6,"carbG":6.6,"fiberG":1.6,"sodiumMg":18,"calciumMg":67,"ironMg":1.0}', 1,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '[]'),
  ('f_tomato_001', '番茄', '["西红柿"]', 'vegetables',
   '{"energyKcal":19,"proteinG":0.9,"fatG":0.2,"carbG":4.0,"fiberG":0.5,"sodiumMg":5,"calciumMg":10,"ironMg":0.4}', 1,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '[]'),
  ('f_chicken_001', '鸡胸肉', '["鸡胸"]', 'protein',
   '{"energyKcal":133,"proteinG":24.6,"fatG":2.5,"carbG":0,"fiberG":0,"sodiumMg":45,"calciumMg":3,"ironMg":0.6}', 1,
   '中国食物成分表', 'demo-1', 'active', '["meat"]', '[]'),
  ('f_pork_rib_001', '猪肋排', '["排骨"]', 'protein',
   '{"energyKcal":278,"proteinG":16.7,"fatG":23.1,"carbG":0.7,"fiberG":0,"sodiumMg":62,"calciumMg":14,"ironMg":1.4}', 1,
   '中国食物成分表', 'demo-1', 'active', '["meat"]', '[]'),
  ('f_egg_001', '鸡蛋', '["蛋"]', 'protein',
   '{"energyKcal":144,"proteinG":13.3,"fatG":8.8,"carbG":2.8,"fiberG":0,"sodiumMg":131,"calciumMg":56,"ironMg":2.0}', 0.88,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '["蛋"]'),
  ('f_milk_001', '牛奶', '["纯牛奶"]', 'dairy',
   '{"energyKcal":54,"proteinG":3.0,"fatG":3.2,"carbG":3.4,"fiberG":0,"sodiumMg":37,"calciumMg":104,"ironMg":0.1}', 1,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '["奶"]'),
  ('f_tofu_001', '北豆腐', '["豆腐"]', 'soy_nuts',
   '{"energyKcal":116,"proteinG":12.2,"fatG":4.8,"carbG":2.0,"fiberG":0.5,"sodiumMg":7,"calciumMg":138,"ironMg":2.5}', 1,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '["大豆"]'),
  ('f_apple_001', '苹果', '["红富士"]', 'fruits',
   '{"energyKcal":53,"proteinG":0.4,"fatG":0.2,"carbG":13.7,"fiberG":1.7,"sodiumMg":1.6,"calciumMg":4,"ironMg":0.6}', 0.85,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '[]'),
  ('f_peanut_001', '花生', '["花生米"]', 'soy_nuts',
   '{"energyKcal":574,"proteinG":24.8,"fatG":44.3,"carbG":21.7,"fiberG":7.7,"sodiumMg":3.6,"calciumMg":39,"ironMg":2.1}', 1,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '["花生"]'),
  ('f_oil_001', '植物油', '["食用油"]', 'oils',
   '{"energyKcal":899,"proteinG":0,"fatG":99.9,"carbG":0,"fiberG":0,"sodiumMg":0,"calciumMg":9,"ironMg":0.1}', 1,
   '中国食物成分表', 'demo-1', 'active', '["vegetarian"]', '[]');

merge into recipes
  (id, name, servings, yield_g, oil_g, cooking_method, source, status, tags, allergens)
key (id) values
  ('r_sparerib_001', '糖醋排骨', 1, 150, 15, '红烧', '常见家常做法', 'active', '["meat"]', '[]'),
  ('r_tomato_egg_001', '番茄炒蛋', 1, 220, 10, '炒', '常见家常做法', 'active', '["vegetarian"]', '["蛋"]');

merge into recipe_ingredients (id, recipe_id, food_id, amount_g) key (id) values
  ('ri_sparerib_001', 'r_sparerib_001', 'f_pork_rib_001', 120),
  ('ri_tomato_egg_001', 'r_tomato_egg_001', 'f_egg_001', 120),
  ('ri_tomato_egg_002', 'r_tomato_egg_001', 'f_tomato_001', 100);

-- 规则硬/软边界（2026-10-30 重构写死）：R01/R02/R03/R06/R08/R09 为硬，R04/R05/R07 为软。
merge into validation_rules (rule_id, severity, threshold, weight, version, enabled) key (rule_id) values
  ('R01_ALLERGEN', 'hard', '{}', '{}', '2026-10-30.1', true),
  ('R02_AVOID', 'hard', '{}', '{}', '2026-10-30.1', true),
  ('R03_FOOD_VALID', 'hard', '{}', '{}', '2026-10-30.1', true),
  ('R04_NUTRIENT_RANGE', 'soft', '{"tolerance":0.2}', '{"breakfast":0.25,"lunch":0.35,"second_main":0.35,"dinner":0.3,"snack":0.1,"night_snack":0.1}', '2026-10-30.1', true),
  ('R05_STRUCTURE', 'soft', '{"minVegetableG":{"breakfast":50,"lunch":100,"second_main":100,"dinner":100,"snack":0,"night_snack":0}}', '{}', '2026-10-30.1', true),
  ('R06_REPLACE_REQUEST', 'hard', '{}', '{}', '2026-10-30.1', true),
  ('R07_DUPLICATE', 'soft', '{}', '{}', '2026-10-30.1', true),
  ('R08_SCOPE', 'hard', '{}', '{}', '2026-10-30.1', true),
  ('R09_AI_UNCONFIRMED', 'hard', '{}', '{}', '2026-10-30.1', true);
