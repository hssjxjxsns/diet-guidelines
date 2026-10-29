package com.dietguide.web;

import com.dietguide.domain.Food;
import com.dietguide.nutrition.FoodRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 食物库查询，供记录页选择食物使用。 */
@RestController
@RequestMapping("/foods")
public class FoodsController {

    private final FoodRepository foods;

    public FoodsController(FoodRepository foods) {
        this.foods = foods;
    }

    @GetMapping
    public List<Food> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit
    ) {
        if (q == null || q.isBlank()) {
            return foods.listActive(limit);
        }
        return foods.search(q, limit);
    }
}
