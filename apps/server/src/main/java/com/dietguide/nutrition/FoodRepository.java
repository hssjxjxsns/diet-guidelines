package com.dietguide.nutrition;

import com.dietguide.domain.Food;
import com.dietguide.domain.Recipe;

import java.util.List;

public interface FoodRepository {

    Food findById(String id);

    Food findByName(String name);

    List<Food> search(String query, int limit);

    List<Food> listActive(int limit);

    Recipe getRecipe(String id);
}
