package com.dietguide.service;

import com.dietguide.domain.GuidelineChunk;

import java.util.List;

public interface GuidelineRetriever {

    List<GuidelineChunk> retrieve(String query, int limit);
}
