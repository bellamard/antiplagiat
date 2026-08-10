package com.b2la.antiplagiat.service;

public class AnalysisResult {
    private final double overallScore;
    private final double aiScore;
    private final String details;

    public AnalysisResult(double overallScore, double aiScore, String details) {
        this.overallScore = overallScore;
        this.aiScore = aiScore;
        this.details = details;
    }

    public double getOverallScore() {
        return overallScore;
    }

    public double getAiScore() {
        return aiScore;
    }

    public String getDetails() {
        return details;
    }
}
