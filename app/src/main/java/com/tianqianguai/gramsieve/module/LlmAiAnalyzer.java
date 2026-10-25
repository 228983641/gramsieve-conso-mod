package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.core.FilterConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pluggable AI analysis backend (Build #19).
 *
 * Default backend is local rules (MessageRuleFactory), which runs
 * entirely on device, sends nothing anywhere, and costs nothing.
 *
 * Optional backend is a user-configured OpenAI-compatible chat
 * completions API (base URL + model + API key from Settings).
 * It is ONLY called when the user taps smart analysis, never for
 * automatic per-message filtering, and only message text / caption /
 * button text is sent (truncated to 2000 chars, no sender name,
 * no dialog id, no user id). The API key is never logged and is
 * never sent anywhere except the configured base URL, in an
 * Authorization: Bearer header.
 *
 * Response handling is deliberately defensive: the model is asked
 * for strict JSON, but code fences, extra prose, or malformed JSON
 * must never crash the dialog - on any failure the caller falls
 * back to local rules (if enabled) and shows the local candidates.
 */
final class LlmAiAnalyzer {
    private LlmAiAnalyzer() {
    }

    static final class Result {
        boolean isGambling;
        double confidence;
        List<String> keywords = new ArrayList<>();
        String reason = "";
        String error = "";
    }

    static boolean isLlmEnabled(FilterConfig config) {
        return config != null
                && config.aiAnalysisMode == 1
                && config.aiApiKey != null && !config.aiApiKey.isBlank()
                && config.aiApiBaseUrl != null && !config.aiApiBaseUrl.isBlank()
                && config.aiApiModel != null && !config.aiApiModel.isBlank();
    }

    static Result analyze(FilterConfig config, String text) {
        Result result = new Result();
        if (config == null || text == null || text.isBlank()) {
            result.error = "empty";
            return result;
        }
        String truncated = text.length() > 2000 ? text.substring(0, 2000) : text;
        try {
            String base = config.aiApiBaseUrl.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            URL url = new URL(base + "/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + config.aiApiKey.trim());
            String prompt = "你是 Telegram 赌博广告识别助手。判断下面消息是否为赌博、博彩、彩票推广，只返回 JSON，不要解释，不要 markdown，不要代码块："
                    + "{\"is_gambling\": true/false, \"confidence\": 0-1, "
                    + "\"keywords\": [\"可作为过滤词的短词，不超过10个，每个2-12字，不要含标点分隔符，不要通用词如官方、下载、注册\"], "
                    + "\"reason\": \"一句话原因\"} 消息：";
            String body = "{\"model\": " + jsonString(config.aiApiModel.trim())
                    + ", \"messages\": [{\"role\": \"user\", \"content\": " + jsonString(prompt + truncated) + "}]"
                    + ", \"temperature\": 0}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", Integer.toString(bytes.length));
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
            }
            conn.disconnect();
            if (code < 200 || code >= 300) {
                result.error = "http " + code;
                return result;
            }
            String content = extractMessageContent(sb.toString());
            if (content == null || content.isBlank()) {
                result.error = "empty content";
                return result;
            }
            return parseModelJson(content, result);
        } catch (Throwable throwable) {
            result.error = throwable.getClass().getSimpleName();
            return result;
        }
    }

    private static Result parseModelJson(String content, Result result) {
        String json = content.trim();
        // Strip code fences if the model added them despite instructions.
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline >= 0) {
                json = json.substring(firstNewline + 1);
            }
            int fence = json.lastIndexOf("```");
            if (fence >= 0) {
                json = json.substring(0, fence);
            }
            json = json.trim();
        }
        // If prose surrounds JSON, take the first {...} block.
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        Matcher gambling = Pattern.compile("\"is_gambling\"\\s*:\\s*(true|false)").matcher(json);
        if (gambling.find()) {
            result.isGambling = Boolean.parseBoolean(gambling.group(1));
        }
        Matcher confidence = Pattern.compile("\"confidence\"\\s*:\\s*([0-9]*\\.?[0-9]+)").matcher(json);
        if (confidence.find()) {
            try {
                result.confidence = Double.parseDouble(confidence.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher reason = Pattern.compile("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (reason.find()) {
            result.reason = unescape(reason.group(1));
        }
        Matcher keywordsBlock = Pattern.compile("\"keywords\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (keywordsBlock.find()) {
            Matcher item = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(keywordsBlock.group(1));
            while (item.find() && result.keywords.size() < 10) {
                String keyword = unescape(item.group(1)).trim();
                if (keyword.length() >= 2 && keyword.length() <= 20
                        && !keyword.contains("、") && !keyword.contains(",") && !keyword.contains("\n")) {
                    result.keywords.add(keyword);
                }
            }
        }
        if (!result.isGambling && result.keywords.isEmpty() && result.reason.isEmpty()) {
            result.error = "unparsable";
        }
        return result;
    }

    private static String extractMessageContent(String responseJson) {
        // OpenAI-compatible: choices[0].message.content
        int choices = responseJson.indexOf("\"choices\"");
        if (choices < 0) {
            return null;
        }
        int contentKey = responseJson.indexOf("\"content\"", choices);
        if (contentKey < 0) {
            return null;
        }
        int colon = responseJson.indexOf(':', contentKey);
        if (colon < 0) {
            return null;
        }
        int quote = responseJson.indexOf('"', colon);
        if (quote < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = quote + 1; i < responseJson.length(); i++) {
            char c = responseJson.charAt(i);
            if (c == '\\' && i + 1 < responseJson.length()) {
                char next = responseJson.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(next); break;
                }
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }
}
