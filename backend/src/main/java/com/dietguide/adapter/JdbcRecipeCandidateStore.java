package com.dietguide.adapter;

import com.dietguide.domain.DishAnalysis;
import com.dietguide.service.RecipeCandidateStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcRecipeCandidateStore implements RecipeCandidateStore {

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;

    public JdbcRecipeCandidateStore(JdbcTemplate jdbc, JsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public String save(String userId, DishAnalysis analysis) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                insert into recipe_candidates (id, user_id, dish_name, proposed_recipe, confidence, source, status)
                values (?, ?, ?, ?, ?, 'ai_inferred', 'pending_review')
                """,
                id,
                userId,
                analysis.dishName(),
                codec.write(analysis.recipeProposal()),
                analysis.confidence()
        );
        return id;
    }
}
