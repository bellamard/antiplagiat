package com.b2la.antiplagiat.analysis.infrastructure;

import com.b2la.antiplagiat.analysis.domain.AnalysisResult;
import com.b2la.antiplagiat.analysis.domain.PlagiarismDetector;
import com.b2la.antiplagiat.entites.Document;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Service
public class PythonPlagiarismDetector implements PlagiarismDetector {

    private static final Logger logger = LoggerFactory.getLogger(PythonPlagiarismDetector.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${analysis.python.executable:python}")
    private String pythonExecutable;

    @Value("${analysis.python.script:src/main/resources/python/ai_analyzer.py}")
    private String pythonScriptPath;

    @Value("${analysis.python.timeout.seconds:30}")
    private int pythonTimeoutSeconds;

    @Value("${analysis.python.model:all-MiniLM-L6-v2}")
    private String pythonModel;

    @Value("${analysis.pg.enabled:false}")
    private boolean pgEnabled;

    @Value("${analysis.pg.uri:}")
    private String pgUri;

    @Value("${analysis.pg.table:embeddings}")
    private String pgTable;

    @Value("${app.documents.storage-dir:uploads/documents}")
    private String storageDirectory;

    @Value("${analysis.pg.chunk-max-chars:1200}")
    private int chunkMaxChars;

    @Value("${analysis.pg.chunk-overlap-sentences:1}")
    private int chunkOverlapSentences;

    @Value("${analysis.ocr.min-text-length:80}")
    private int ocrMinTextLength;

    private CompletableFuture<String> readProcessOutput(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (inputStream) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private CompletableFuture<Void> writeProcessInput(Process process, String input) {
        return CompletableFuture.runAsync(() -> {
            try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(input);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private String awaitOutput(CompletableFuture<String> output) {
        try {
            return output.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Failed to read process output: {}", e.getMessage());
            return "";
        }
    }

    private String extractJsonPayload(String output) {
        if (output == null) {
            return "";
        }

        String trimmed = output.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        for (int start = 0; start < trimmed.length(); start++) {
            if (trimmed.charAt(start) != '{') {
                continue;
            }

            int depth = 0;
            boolean inString = false;
            boolean escaped = false;

            for (int index = start; index < trimmed.length(); index++) {
                char current = trimmed.charAt(index);

                if (escaped) {
                    escaped = false;
                    continue;
                }

                if (current == '\\' && inString) {
                    escaped = true;
                    continue;
                }

                if (current == '"') {
                    inString = !inString;
                    continue;
                }

                if (inString) {
                    continue;
                }

                if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                    if (depth == 0) {
                        String candidate = trimmed.substring(start, index + 1);
                        try {
                            mapper.readTree(candidate);
                            return candidate;
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                }
            }
        }

        return trimmed;
    }

    private File resolveDocumentFile(Document document) {
        Path storagePath = Path.of(storageDirectory).toAbsolutePath().normalize();
        for (String candidate : List.of(
                document.getStoredFileName() == null ? "" : document.getStoredFileName(),
                document.getUrlFile() == null ? "" : document.getUrlFile())) {
            if (candidate.isBlank()) {
                continue;
            }
            Path path = Path.of(candidate).normalize();
            if (Files.isRegularFile(path)) {
                return path.toFile();
            }
            Path storedPath = storagePath.resolve(candidate).normalize();
            if (storedPath.startsWith(storagePath) && Files.isRegularFile(storedPath)) {
                return storedPath.toFile();
            }
        }
        return null;
    }

    private byte[] readDocumentBytes(Document document, File file) throws IOException {
        if (document.getCompressedBase64Content() != null && !document.getCompressedBase64Content().isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(document.getCompressedBase64Content());
            if (!document.isContentCompressed()) {
                return decoded;
            }
            try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
                return gzipInputStream.readAllBytes();
            }
        }

        if (file != null) {
            return Files.readAllBytes(file.toPath());
        }

        throw new FileNotFoundException("Document content not found");
    }

    private boolean isImageDocument(Document document) {
        String contentType = document.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return true;
        }

        String name = document.getOriginalFileName() != null ? document.getOriginalFileName() : document.getStoredFileName();
        if (name == null) {
            return false;
        }

        String lowered = name.toLowerCase();
        return lowered.endsWith(".png")
                || lowered.endsWith(".jpg")
                || lowered.endsWith(".jpeg")
                || lowered.endsWith(".tiff")
                || lowered.endsWith(".tif")
                || lowered.endsWith(".bmp")
                || lowered.endsWith(".gif")
                || lowered.endsWith(".webp");
    }

    private boolean isPdfDocument(Document document) {
        String contentType = document.getContentType();
        if (contentType != null && contentType.equalsIgnoreCase("application/pdf")) {
            return true;
        }

        String name = document.getOriginalFileName() != null ? document.getOriginalFileName() : document.getStoredFileName();
        return name != null && name.toLowerCase().endsWith(".pdf");
    }

    private String tempFileSuffix(Document document) {
        String name = document.getOriginalFileName() != null ? document.getOriginalFileName() : document.getStoredFileName();
        if (name == null) {
            return ".bin";
        }

        int extensionIndex = name.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == name.length() - 1) {
            return ".bin";
        }

        return name.substring(extensionIndex);
    }

    private AnalysisResult fallbackResult(String text, String reason) {
        logger.warn("Using degraded analysis result: {}", reason);
        String details;
        try {
            details = mapper.writeValueAsString(java.util.Map.of(
                    "status", "DEGRADED",
                    "reason", reason,
                    "extractedLength", text.length()
            ));
        } catch (IOException e) {
            details = "{\"status\":\"DEGRADED\"}";
        }
        return new AnalysisResult(0.0, 0.0, details);
    }

    @Override
    public AnalysisResult analyze(Document document) {
        logger.info("Starting analysis for document id={} name={}", document.getId(), document.getName());

        String text = "";
        byte[] documentBytes;
        // try to read stored file first
        File file = resolveDocumentFile(document);

        try {
            documentBytes = readDocumentBytes(document, file);
            logger.debug("Reading document for analysis: id={} bytes={}", document.getId(), documentBytes.length);
            try (InputStream is = new ByteArrayInputStream(documentBytes)) {
                AutoDetectParser parser = new AutoDetectParser();
                BodyContentHandler handler = new BodyContentHandler(-1);
                Metadata metadata = new Metadata();
                parser.parse(is, handler, metadata, new ParseContext());
                text = handler.toString();
                logger.debug("Extracted text length: {}", text.length());
            } catch (Exception e) {
                logger.warn("Failed to extract text from file: {}", e.getMessage());
                text = "";
            }
        } catch (IOException e) {
            return fallbackResult(text, "Document file not found");
        }

        // Call Python analyzer; support OCR for images and optional pgvector storage/query
        Path temporaryOcrFile = null;
        try {
            logger.debug("Preparing python analyzer command: {} {}", pythonExecutable, pythonScriptPath);
            List<String> cmd = new ArrayList<>(Arrays.asList(pythonExecutable, pythonScriptPath));

            boolean isImage = isImageDocument(document);
            boolean shouldRunOcrFallback = isImage || (isPdfDocument(document) && text.trim().length() < ocrMinTextLength);

            if (isImage) {
                temporaryOcrFile = Files.createTempFile("antiplagiat-ocr-", tempFileSuffix(document));
                Files.write(temporaryOcrFile, documentBytes);
                cmd.addAll(Arrays.asList("--image", temporaryOcrFile.toAbsolutePath().toString()));
            } else if (shouldRunOcrFallback) {
                temporaryOcrFile = Files.createTempFile("antiplagiat-ocr-", tempFileSuffix(document));
                Files.write(temporaryOcrFile, documentBytes);
                cmd.addAll(Arrays.asList("--file", temporaryOcrFile.toAbsolutePath().toString(), "--ocr"));
            } else {
                // send text via stdin; nothing to add to cmd for text input
            }

            // pgvector args if configured
            if (pgEnabled && pgUri != null && !pgUri.isBlank() && pgTable != null && !pgTable.isBlank()) {
                cmd.addAll(Arrays.asList("--pg-uri", pgUri, "--pg-table", pgTable, "--store-doc", document.getId().toString()));
                cmd.addAll(Arrays.asList("--chunk-max-chars", Integer.toString(chunkMaxChars)));
                cmd.addAll(Arrays.asList("--chunk-overlap-sentences", Integer.toString(chunkOverlapSentences)));
            }

            // model argument
            if (pythonModel != null && !pythonModel.isBlank()) {
                cmd.addAll(Arrays.asList("--model", pythonModel));
            }

            logger.debug("Command: {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> environment = pb.environment();
            environment.putIfAbsent("HF_HUB_OFFLINE", "1");
            environment.putIfAbsent("TRANSFORMERS_OFFLINE", "1");
            environment.putIfAbsent("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True");
            environment.putIfAbsent("ANALYSIS_USE_PADDLEOCR", "false");
            Process p = pb.start();
            CompletableFuture<String> stdoutFuture = readProcessOutput(p.getInputStream());
            CompletableFuture<String> stderrFuture = readProcessOutput(p.getErrorStream());
            CompletableFuture<Void> stdinFuture = isImage
                    ? CompletableFuture.completedFuture(null)
                    : writeProcessInput(p, text);

            boolean finished = p.waitFor(pythonTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                logger.warn("Python analyzer did not finish in {}s — killing process", pythonTimeoutSeconds);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }

            stdinFuture.cancel(true);
            String stdout = awaitOutput(stdoutFuture);
            String stderr = awaitOutput(stderrFuture);

            int exit = finished ? p.exitValue() : -1;
            logger.debug("Python analyzer exit code: {}", exit);
            if (exit == 0 && stdout != null && !stdout.isBlank()) {
                logger.info("Python analyzer returned valid output (len={})", stdout.length());
                String jsonPayload = extractJsonPayload(stdout);
                JsonNode node = mapper.readTree(jsonPayload);
                double overall = node.has("overallScore") ? node.get("overallScore").asDouble() : 0.0;
                double ai = node.has("aiScore") ? node.get("aiScore").asDouble() : 0.0;
                String details = node.has("details") ? node.get("details").toString() : null;
                logger.debug("Parsed analysis result overall={} ai={}", overall, ai);
                return new AnalysisResult(overall, ai, details);
            } else {
                logger.warn("Python analyzer did not produce usable output. exit={} stderr={}", exit,
                        stderr.length() > 1000 ? stderr.substring(0, 1000) + "..." : stderr);
            }
        } catch (Exception e) {
            logger.error("Error while running python analyzer: {}", e.getMessage(), e);
        } finally {
            if (temporaryOcrFile != null) {
                try {
                    Files.deleteIfExists(temporaryOcrFile);
                } catch (IOException e) {
                    logger.debug("Failed to delete OCR temp file {}: {}", temporaryOcrFile, e.getMessage());
                }
            }
        }

        return fallbackResult(text, "Python analyzer unavailable or invalid response");
    }
}



