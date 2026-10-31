package com.dietguide.config;

import com.dietguide.nutrition.FoodRepository;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.nutrition.PlanValidator;
import com.dietguide.nutrition.RuleEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfig {

    @Bean
    public NutritionEngine nutritionEngine(FoodRepository foods) {
        return new NutritionEngine(foods);
    }

    /** 共享规则引擎：记录侧（RecordValidationService）与配餐侧（PlanValidator）同一实例。 */
    @Bean
    public RuleEngine ruleEngine(FoodRepository foods, NutritionEngine engine) {
        return new RuleEngine(foods, engine);
    }

    @Bean
    public PlanValidator planValidator(RuleEngine ruleEngine) {
        return new PlanValidator(ruleEngine);
    }
}
