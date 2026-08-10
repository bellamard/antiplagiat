package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.entites.Document;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Service
public class AnalysisEngineService {

    private final ObjectMapper mapper = new ObjectMapper();

    public AnalysisResult analyze(Document document) {
        String text = "";
        // try to read stored file first
        File file = new File(document.getStoredFileName() == null ? "" : document.getStoredFileName());
        if (!file.exists()) {
            // fall back to urlFile if it's a local path
            file = new File(document.getUrlFile() == null ? "" : document.getUrlFile());
        }

        if (file.exists()) {
            try (InputStream is = new FileInputStream(file)) {
                AutoDetectParser parser = new AutoDetectParser();
                BodyContentHandler handler = new BodyContentHandler(-1);
                Metadata metadata = new Metadata();
                parser.parse(is, handler, metadata, new ParseContext());
                text = handler.toString();
            } catch (Exception e) {
                text = "";
            }
        }

        // Call Python analyzer via stdin; if not available, fallback to simple heuristics
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "src/main/resources/python/ai_analyzer.py");
            Process p = pb.start();

            try (OutputStream os = p.getOutputStream(); Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                w.write(text);
            }

            String stdout;
            try (InputStream is = p.getInputStream(); Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                stdout = new BufferedReader(r).lines().collect(Collectors.joining("\n"));
            }

            int exit = p.waitFor();
            if (exit == 0 && stdout != null && !stdout.isBlank()) {
                JsonNode node = mapper.readTree(stdout);
                double overall = node.has("overallScore") ? node.get("overallScore").asDouble() : 0.0;
                double ai = node.has("aiScore") ? node.get("aiScore").asDouble() : 0.0;
                String details = node.has("details") ? node.get("details").toString() : null;
                return new AnalysisResult(overall, ai, details);
            }
        } catch (Exception ignored) {
            // fallback
        }

        // Simple heuristic fallback
        double overall = Math.min(100.0, Math.max(0.0, text.length() > 0 ? Math.min(90.0, text.length() / 10.0) : 0.0));
        double ai = text.contains("AI") || text.contains("ai") ? 50.0 : 0.0;
        String details = "{\"extractedLength\": " + text.length() + " }";
        return new AnalysisResult(overall, ai, details);
    }
}
