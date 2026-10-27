package com.dietguide.adapter;

import com.dietguide.domain.DishAnalysis;
import com.dietguide.service.RecipeCandidateStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRecipeCandidateStore implements RecipeCandidateStore {

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;

    public JdbcRecipeCandidateStore(JdbcTemplate jdbc, JsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public void save(String userId, DishAnalysis analysis) {
        jdbc.update(
                """
                insert into recipe_candidates (user_id, dish_name, proposed_recipe, confidence, source, status)
                values (?, ?, cast(? as jsonb), ?, 'ai_inferred', 'pending_review')
                """,
                userId,
                analysis.dishName(),
                codec.write(analysis.recipeProposal()),
                analysis.confidence()
        );
    }
}
