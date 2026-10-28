package com.dietguide.adapter;

import com.dietguide.domain.Food;
import com.dietguide.domain.Recipe;
import com.dietguide.nutrition.FoodRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class JdbcFoodRepository implements FoodRepository {

    private static final String COLUMNS = """
            id, name, aliases, category, nutrients_per_100g as nutrients_json,
            edible_ratio, source, source_version, status, tags, allergens
            """;

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;
    private final RowMapper<Food> foodMapper;

    public JdbcFoodRepository(JdbcTemplate jdbc, JsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.foodMapper = (resultSet, rowNum) -> new Food(
                resultSet.getString("id"),
                resultSet.getString("name"),
                codec.stringList(resultSet.getString("aliases")),
                resultSet.getString("category"),
                codec.nutrients(resultSet.getString("nutrients_json")),
                resultSet.getDouble("edible_ratio"),
                resultSet.getString("source"),
                resultSet.getString("source_version"),
                resultSet.getString("status"),
                codec.stringList(resultSet.getString("tags")),
                codec.stringList(resultSet.getString("allergens"))
        );
    }

    @Override
    public Food findById(String id) {
        List<Food> rows = jdbc.query(
                "select " + COLUMNS + " from nutrition_foods where id = ?",
                foodMapper,
                id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Food findByName(String name) {
        List<Food> rows = jdbc.query(
                "select " + COLUMNS + " from nutrition_foods "
                        + "where status = 'active' and (name = ? or aliases like ?) limit 1",
                foodMapper,
                name,
                "%\"" + name + "\"%"
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<Food> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return listActive(limit);
        }
        List<Food> rows = jdbc.query(
                "select " + COLUMNS + " from nutrition_foods "
                        + "where status = 'active' and lower(name) like lower(?) order by name limit ?",
                foodMapper,
                "%" + query + "%",
                limit
        );
        return rows.isEmpty() ? listActive(limit) : rows;
    }

    @Override
    public List<Food> listActive(int limit) {
        return jdbc.query(
                "select " + COLUMNS + " from nutrition_foods where status = 'active' order by name limit ?",
                foodMapper,
                limit
        );
    }

    @Override
    public Recipe getRecipe(String id) {
        List<Recipe> recipes = jdbc.query(
                """
                select id, name, servings, yield_g, oil_g, cooking_method, source, status, tags, allergens
                from recipes where id = ? and status = 'active'
                """,
                (resultSet, rowNum) -> {
                    List<Recipe.RecipeIngredient> ingredients = new ArrayList<>();
                    jdbc.query(
                            "select food_id, amount_g from recipe_ingredients where recipe_id = ?",
                            (ingredientRow, ingredientNum) -> {
                                ingredients.add(new Recipe.RecipeIngredient(
                                        ingredientRow.getString("food_id"),
                                        ingredientRow.getDouble("amount_g")
                                ));
                                return null;
                            },
                            id
                    );

                    return new Recipe(
                            resultSet.getString("id"),
                            resultSet.getString("name"),
                            resultSet.getDouble("servings"),
                            resultSet.getDouble("yield_g"),
                            resultSet.getDouble("oil_g"),
                            resultSet.getString("cooking_method"),
                            resultSet.getString("source"),
                            resultSet.getString("status"),
                            ingredients,
                            codec.stringList(resultSet.getString("tags")),
                            codec.stringList(resultSet.getString("allergens"))
                    );
                },
                id
        );
        return recipes.isEmpty() ? null : recipes.get(0);
    }
}
