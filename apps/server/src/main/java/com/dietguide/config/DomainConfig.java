package com.dietguide.config;

import com.dietguide.nutrition.FoodRepository;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.nutrition.PlanValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfig {

    @Bean
    public NutritionEngine nutritionEngine(FoodRepository foods) {
        return new NutritionEngine(foods);
    }

    @Bean
    public PlanValidator planValidator(FoodRepository foods, NutritionEngine engine) {
        return new PlanValidator(foods, engine);
    }
}
