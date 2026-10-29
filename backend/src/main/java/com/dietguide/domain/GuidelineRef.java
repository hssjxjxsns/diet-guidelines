package com.dietguide.domain;

/** 配餐引用的指南条目，供前端展示依据。 */
public record GuidelineRef(String id, String title, Double score) {
}
