package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.entites.Document;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class AnalysisEngineService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${analysis.python.executable:python}")
    private String pythonExecutable;

    @Value("${analysis.python.script:src/main/resources/python/ai_analyzer.py}")
    private String pythonScriptPath;

    @Value("${analysis.python.timeout.seconds:30}")
    private int pythonTimeoutSeconds;

    // Run once to ensure python deps and spacy model
    private final AtomicBoolean pythonEnvInitialized = new AtomicBoolean(false);

    private void ensurePythonEnvironment() {
        if (pythonEnvInitialized.get()) return;
        synchronized (pythonEnvInitialized) {
            if (pythonEnvInitialized.get()) return;
            // try pip install -r requirements.txt
            try {
                ProcessBuilder pipPb = new ProcessBuilder(pythonExecutable, "-m", "pip", "install", "-r", "src/main/resources/python/requirements.txt");
                pipPb.redirectErrorStream(true);
                Process pipProc = pipPb.start();
                boolean finished = pipProc.waitFor(300, TimeUnit.SECONDS);
                if (!finished) {
                    pipProc.destroyForcibly();
                }
            } catch (Exception ignored) {
                // non-fatal
            }

            // try to download spacy model
            try {
                ProcessBuilder spacyPb = new ProcessBuilder(pythonExecutable, "-m", "spacy", "download", "en_core_web_sm");
                spacyPb.redirectErrorStream(true);
                Process spacyProc = spacyPb.start();
                boolean finished = spacyProc.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    spacyProc.destroyForcibly();
                }
            } catch (Exception ignored) {
                // non-fatal
            }

            pythonEnvInitialized.set(true);
        }
    }

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
            ensurePythonEnvironment();

            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, pythonScriptPath);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (OutputStream os = p.getOutputStream(); Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                w.write(text);
                w.flush();
                os.flush();
            }

            String stdout = "";
            try (InputStream is = p.getInputStream(); Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                stdout = new BufferedReader(r).lines().collect(Collectors.joining("\n"));
            }

            boolean finished = p.waitFor(pythonTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
            }

            int exit = finished ? p.exitValue() : -1;
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
