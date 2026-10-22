package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.common.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SpeechService {
    private final Path outputDirectory;
    private final Path script;
    private final CosyVoiceClient cosyVoice;
    private final ObjectMapper json = new ObjectMapper();

    public SpeechService(String outputDirectory, String scriptPath) throws IOException {
        this(outputDirectory, scriptPath, null);
    }

    @Autowired
    public SpeechService(@Value("${audiorag.generated-audio-dir}") String outputDirectory,
                         @Value("${audiorag.speech-script:../tools/synthesize.ps1}") String scriptPath,
                         @Value("${audiorag.speech.provider:windows}") String provider,
                         @Value("${audiorag.speech.cosyvoice.base-url:http://127.0.0.1:50000}") String baseUrl,
                         @Value("${audiorag.speech.cosyvoice.timeout-seconds:600}") int timeoutSeconds,
                         RestClient.Builder builder) throws IOException {
        this(outputDirectory, scriptPath, configuredProvider(provider, builder, baseUrl, timeoutSeconds));
    }

    SpeechService(String outputDirectory, String scriptPath, CosyVoiceClient cosyVoice) throws IOException {
        this.outputDirectory = Path.of(outputDirectory).toAbsolutePath().normalize();
        Files.createDirectories(this.outputDirectory);
        this.script = Path.of(scriptPath).toAbsolutePath().normalize();
        this.cosyVoice = cosyVoice;
    }

    private static CosyVoiceClient configuredProvider(String provider, RestClient.Builder builder, String baseUrl, int timeoutSeconds) {
        if ("windows".equalsIgnoreCase(provider)) return null;
        if ("cosyvoice".equalsIgnoreCase(provider)) return new CosyVoiceClient(builder, baseUrl, timeoutSeconds);
        throw new IllegalArgumentException("语音服务仅支持 windows 或 cosyvoice");
    }

    public String synthesize(String lessonId, String text, String voice) {
        return synthesize(lessonId, text, voice, null);
    }

    /** 只有 CosyVoice 支持零样本音色录入；Windows SAPI 不能克隆音色，直接说明而不是假装成功。 */
    public Map<String, Object> importVoice(Map<String, Object> request) {
        if (cosyVoice == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "当前语音提供方是 Windows SAPI，不支持录入样例音色；切换到 CosyVoice 后可用");
        return cosyVoice.importVoice(request);
    }

    public void deleteVoice(String name) {
        if (cosyVoice == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "当前语音提供方是 Windows SAPI，没有可删除的录入音色");
        cosyVoice.deleteVoice(name);
    }

    /** Returns the active provider's real inventory; never fabricates voices or silently changes providers. */
    public List<Map<String, Object>> voices() {
        if (cosyVoice != null) return cosyVoice.voices();
        Path logFile = null;
        Process process = null;
        try {
            if (!Files.isRegularFile(script)) throw new IOException("本地语音脚本不存在");
            logFile = Files.createTempFile(outputDirectory, "voices-", ".log");
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-ListVoices")
                    .redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IOException("本地语音能力检测超时");
            String output = Files.readString(logFile, StandardCharsets.UTF_8).strip().replace("\uFEFF", "");
            if (process.exitValue() != 0) throw new IOException(output.isBlank() ? "System.Speech 不可用" : output);
            JsonNode inventory = json.readTree(output);
            if (inventory == null || !inventory.isArray()) throw new IOException("本地语音能力返回格式无效");
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode entry : inventory) {
                String name = entry.path("name").asText("");
                String locale = entry.path("locale").asText("");
                if (name.isBlank() || locale.isBlank()) throw new IOException("本地语音能力缺少名称或语言");
                Map<String, Object> capability = new LinkedHashMap<>();
                capability.put("name", name);
                capability.put("locale", locale);
                capability.put("culture", locale);
                capability.put("enabled", entry.path("enabled").asBoolean(false));
                result.add(capability);
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "无法检测本地语音能力：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "本地语音能力检测被中断");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (logFile != null) {
                try { Files.deleteIfExists(logFile); } catch (IOException ignored) { }
            }
        }
    }

    /** Empty means automatic switching for bilingual content; otherwise the returned voice is installed and enabled. */
    public String selectVoice(String requested, String language) {
        return selectVoice(voices(), requested, language);
    }

    static String selectVoice(List<Map<String, Object>> inventory, String requested, String language) {
        String selection = requested == null ? "" : requested.strip();
        String locale = normalizeLanguage(language);
        List<Map<String, Object>> enabled = inventory.stream()
                .filter(voice -> Boolean.TRUE.equals(voice.get("enabled"))).toList();
        Map<String, Object> selected = null;
        if (!selection.isBlank()) {
            selected = enabled.stream().filter(voice -> selection.equals(voice.get("name"))).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "所选音色未安装或不可用：" + selection));
        }
        if ("bilingual".equals(locale)) {
            // CosyVoice 用同一个音色念整段中英混排文本，所以这个音色必须同时支持中英两种语言；
            // 只支持其中一种的音色在这里就拒绝，不要等适配器返回一句看不懂的报错。
            List<Map<String, Object>> both = enabled.stream()
                    .filter(voice -> matchesLanguage(voice, "zh") && matchesLanguage(voice, "en")).toList();
            if (both.isEmpty()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "中英双语内容需要一个同时支持中文和英文的音色，当前没有可用音色");
            }
            if (selected != null) {
                if (!matchesLanguage(selected, "zh") || !matchesLanguage(selected, "en")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "所选音色不支持中英双语，请改选同时支持中文和英文的音色，或使用自动选择");
                }
                return selection;
            }
            return String.valueOf(both.get(0).get("name"));
        }
        if ("auto".equals(locale)) {
            if (selected != null) return selection;
            // With no text yet, Chinese is the application's historical content language.
            locale = "zh";
        }
        if (selected != null) {
            if (!matchesLanguage(selected, locale)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "所选音色不支持请求的语言：" + locale);
            }
            return selection;
        }
        return String.valueOf(requireVoice(enabled, locale).get("name"));
    }

    private static Map<String, Object> requireVoice(List<Map<String, Object>> inventory, String language) {
        return inventory.stream().filter(voice -> matchesLanguage(voice, language)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "当前语音服务没有可用的 " + language + " 音色，请检查音色配置后重试"));
    }

    private static boolean matchesLanguage(Map<String, Object> voice, String language) {
        if (voice.get("languages") instanceof Iterable<?> supported)
            for (Object value : supported)
                if (value instanceof String candidate && matchesLocale(candidate, language)) return true;
        String locale = String.valueOf(voice.getOrDefault("locale", voice.getOrDefault("culture", "")))
                .replace('_', '-').toLowerCase(Locale.ROOT);
        if ("bilingual".equals(locale) && List.of("zh", "en").contains(language)) return true;
        return matchesLocale(locale, language);
    }

    private static boolean matchesLocale(String candidate, String language) {
        String locale = candidate.replace('_', '-').toLowerCase(Locale.ROOT);
        return locale.equals(language) || (!language.contains("-") && locale.startsWith(language + "-"));
    }

    private static String normalizeLanguage(String language) {
        String locale = language == null || language.isBlank() ? "auto" : language.strip().replace('_', '-').toLowerCase(Locale.ROOT);
        if (List.of("auto", "bilingual").contains(locale)) return locale;
        if (!locale.matches("[a-z]{2,3}(-[a-z0-9]{2,8})*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "语音语言无效：" + locale);
        }
        return locale;
    }

    public static String detectLanguage(String text) {
        boolean otherScript = text.codePoints().anyMatch(codePoint -> Character.isLetter(codePoint)
                && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN
                && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN);
        if (otherScript) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无法自动识别该文本的语音语言，请明确指定本机支持的语言");
        }
        boolean chinese = text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        boolean latin = text.codePoints().anyMatch(codePoint -> Character.isLetter(codePoint)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN);
        if (chinese && latin) return "bilingual";
        if (latin) return "en";
        if (chinese || text.codePoints().noneMatch(Character::isLetter)) return "zh";
        throw new ApiException(HttpStatus.BAD_REQUEST, "无法自动识别该文本的语音语言，请明确指定本机支持的语言");
    }

    public String synthesize(String lessonId, String text, String voice, String language) {
        String safeId = lessonId == null || lessonId.isBlank() ? UUID.randomUUID().toString() : lessonId;
        if (!safeId.matches("[0-9a-fA-F-]{36}")) throw new ApiException(HttpStatus.BAD_REQUEST, "音频编号无效");
        if (text == null || text.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "语音文本不能为空");
        String locale = normalizeLanguage(language);
        if ("auto".equals(locale)) locale = detectLanguage(text);
        String selectedVoice = selectVoice(voice, locale);
        if (cosyVoice != null) return synthesizeWithCosyVoice(safeId, text, selectedVoice, locale);
        Path textFile = outputDirectory.resolve(safeId + ".txt");
        Path audioFile = outputDirectory.resolve(safeId + ".wav");
        Path logFile = outputDirectory.resolve(safeId + ".log");
        Process process = null;
        try {
            Files.writeString(textFile, text, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                    "-InputFile", textFile.toString(), "-OutputFile", audioFile.toString(),
                    "-Language", locale));
            if (!selectedVoice.isBlank()) command.addAll(List.of("-Voice", selectedVoice));
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("语音合成超时");
            }
            String output = Files.readString(logFile, StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || !Files.exists(audioFile) || Files.size(audioFile) <= 44) {
                throw new IOException(output.isBlank() ? "系统语音合成失败" : output);
            }
            return "/media/" + audioFile.getFileName();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "语音生成失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "语音生成被中断");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            try {
                Files.deleteIfExists(textFile);
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {
                // Temporary source text does not affect the generated audio.
            }
        }
    }

    private String synthesizeWithCosyVoice(String id, String text, String voice, String language) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(outputDirectory, id + "-", ".wav.part");
            cosyVoice.synthesize(text, voice, language, temporary);
            Path audioFile = outputDirectory.resolve(id + ".wav");
            Files.move(temporary, audioFile, StandardCopyOption.REPLACE_EXISTING);
            return "/media/" + audioFile.getFileName();
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CosyVoice 音频保存或校验失败，没有发布不完整音频");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public Path audioFile(String filename) {
        Path file = outputDirectory.resolve(filename).normalize();
        if (!file.startsWith(outputDirectory) || !Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "音频不存在");
        }
        return file;
    }

    public void remove(String audioUrl) {
        if (audioUrl == null || !audioUrl.startsWith("/media/")) {
            return;
        }
        Path file = outputDirectory.resolve(audioUrl.substring("/media/".length())).normalize();
        if (!file.startsWith(outputDirectory)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException("删除项目音频失败", exception);
        }
    }
}
