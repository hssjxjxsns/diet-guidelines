package com.dietguide.ai;

import java.util.List;

public interface AiGateway {

    <T> T chatJson(ChatRequest request, Class<T> type);

    List<double[]> embed(List<String> texts);

    record ChatRequest(
            String scene,
            String system,
            String user,
            int maxOutputTokens,
            Double temperature,
            List<String> images
    ) {
    }
}
