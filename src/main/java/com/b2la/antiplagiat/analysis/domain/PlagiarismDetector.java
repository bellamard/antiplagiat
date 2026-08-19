package com.b2la.antiplagiat.analysis.domain;

import com.b2la.antiplagiat.entites.Document;

/**
 * Domain port for a document analysis engine.
 * Infrastructure implementations may use Python, a remote API, or another engine.
 */
public interface PlagiarismDetector {

    AnalysisResult analyze(Document document);
}
