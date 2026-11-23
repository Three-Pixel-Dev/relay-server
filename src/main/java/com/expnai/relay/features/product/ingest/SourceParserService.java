package com.expnai.relay.features.product.ingest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.enums.RelaySourceKind;
import com.expnai.relay.features.ai.GeminiAudioTranscribeClient;
import com.expnai.relay.features.ai.GeminiPdfOcrClient;
import com.expnai.relay.features.ai.GeminiUrlContextFetchService;
import com.expnai.relay.features.ai.GeminiVideoNotesClient;
import com.expnai.relay.features.ai.GeminiVideoNotesClient.SceneImage;
import com.expnai.relay.features.product.ingest.FfmpegAudioService.SampledFrame;
import com.expnai.relay.features.product.storage.FileStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SourceParserService {

	private static final Logger log = LoggerFactory.getLogger(SourceParserService.class);

	private static final int CHUNK_TARGET_CHARS = 2200; // ~550 tokens
	private static final int CHUNK_OVERLAP_CHARS = 220;
	private static final int MAX_YOUTUBE_VIDEOS = 30;
	private static final int PDF_OCR_MIN_CHARS = 40;
	private static final float PDF_OCR_DPI = 150f;
	private static final int PDF_OCR_MAX_PAGES = 40;

	private static final Pattern SRT_TIME_PATTERN = Pattern.compile(
			"(\\d{2}):(\\d{2}):(\\d{2})[,.]\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}");
	private static final Pattern YT_VIDEO_ID_PATTERN = Pattern.compile(
			"(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})");
	private static final Pattern YT_CHANNEL_ID_META = Pattern.compile(
			"(?:\"externalId\"|\"channelId\"|itemprop=\"channelId\"\\s+content)\\s*[:=]\\s*\"(UC[A-Za-z0-9_-]{20,})\"");
	private static final Pattern YT_CAPTION_BASE_URL = Pattern.compile(
			"\"captionTracks\":\\[\\{[^\\]]*?\"baseUrl\":\"([^\"]+)\"");

	private final FileStorageService fileStorageService;
	private final PlaywrightWebFetchService playwrightWebFetchService;
	private final GeminiUrlContextFetchService geminiUrlContextFetchService;
	private final GeminiPdfOcrClient geminiPdfOcrClient;
	private final GeminiAudioTranscribeClient geminiAudioTranscribeClient;
	private final GeminiVideoNotesClient geminiVideoNotesClient;
	private final FfmpegAudioService ffmpegAudioService;
	private final VideoSceneSelector videoSceneSelector;
	private final ObjectMapper objectMapper;

	@Value("${youtube.api.key:}")
	private String youtubeApiKey;

	public record PassageChunk(String text, String locator) {
	}

	public record ParseResult(List<PassageChunk> chunks, int videosRead, int docsRead) {
	}

	public ParseResult parseSource(RelaySource source) {
		return parseSource(source, null, null);
	}

	public ParseResult parseSource(RelaySource source, String chatApiKey) {
		return parseSource(source, chatApiKey, null);
	}

	public ParseResult parseSource(RelaySource source, String chatApiKey, Consumer<String> progressCallback) {
		Long productId = source != null && source.getProduct() != null ? source.getProduct().getId() : null;
		return parseSource(source, chatApiKey, progressCallback, productId);
	}

	public ParseResult parseSource(
			RelaySource source,
			String chatApiKey,
			Consumer<String> progressCallback,
			Long productId) {
		RelaySourceKind kind = source.getKind();
		if (kind == RelaySourceKind.FILE) {
			return parseFileSource(source, chatApiKey, progressCallback, productId);
		}
		if (kind == RelaySourceKind.WEB) {
			return parseWebSource(source, chatApiKey, progressCallback, productId);
		}
		if (kind == RelaySourceKind.YOUTUBE) {
			return parseYoutubeSource(source);
		}
		return new ParseResult(List.of(), 0, 0);
	}

	private ParseResult parseFileSource(
			RelaySource source,
			String chatApiKey,
			Consumer<String> progressCallback,
			Long productId) {
		String name = source.getName() != null ? source.getName() : "document.txt";
		String lower = name.toLowerCase(Locale.ROOT);

		Path path = fileStorageService.resolvePath(source.getStorageKey());
		if (!Files.exists(path)) {
			throw new IllegalArgumentException("Stored file not found for " + name);
		}

		if (FfmpegAudioService.isMediaFilename(name)) {
			return parseMediaSource(path, name, chatApiKey, progressCallback, productId);
		}

		try {
			byte[] header = readHeaderBytes(path, 8);
			if (lower.endsWith(".pdf") && isPdfMagic(header)) {
				List<PassageChunk> chunks = parsePdf(path, name, chatApiKey, progressCallback, productId);
				return new ParseResult(chunks, 0, 1);
			}
			if (lower.endsWith(".docx") && isZipMagic(header)) {
				List<PassageChunk> chunks = parseDocx(path, name);
				return new ParseResult(chunks, 0, 1);
			}
			String rawText = Files.readString(path, StandardCharsets.UTF_8);
			if (lower.endsWith(".srt")) {
				List<PassageChunk> chunks = parseSrt(rawText, name);
				return new ParseResult(chunks, 0, 1);
			}
			List<PassageChunk> chunks = chunkTextWithPageLocators(rawText, name);
			return new ParseResult(chunks, 0, 1);
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Failed to read file " + name + ": " + ex.getMessage(), ex);
		}
	}

	private ParseResult parseMediaSource(
			Path path,
			String sourceName,
			String chatApiKey,
			Consumer<String> progressCallback,
			Long productId) {
		if (progressCallback != null) {
			progressCallback.accept("Transcribing audio from " + sourceName);
		}
		Path workDir = path.getParent().resolve("ffmpeg-" + System.nanoTime());
		try {
			List<Path> wavs = ffmpegAudioService.transcodeForTranscription(path, workDir);
			StringBuilder transcript = new StringBuilder();
			int segmentIndex = 0;
			for (Path wav : wavs) {
				segmentIndex++;
				if (progressCallback != null && wavs.size() > 1) {
					progressCallback.accept(
							"Transcribing audio from " + sourceName + " (" + segmentIndex + "/" + wavs.size() + ")");
				}
				byte[] wavBytes = Files.readAllBytes(wav);
				String part = geminiAudioTranscribeClient.transcribeWav(
						chatApiKey, wavBytes, productId, sourceName);
				if (part != null && !part.isBlank()) {
					if (!transcript.isEmpty()) {
						transcript.append("\n\n");
					}
					transcript.append(part.strip());
				}
			}
			String combined = transcript.toString().strip();
			if (combined.length() < 40) {
				throw new IllegalArgumentException(
						"No speech could be transcribed from " + sourceName + ". Try a clearer recording.");
			}

			if (FfmpegAudioService.isVideoFilename(sourceName)) {
				combined = mergeVideoScenes(
						path, sourceName, chatApiKey, progressCallback, productId, workDir, combined);
			}

			List<String> windows = splitTextIntoWindows(combined);
			List<PassageChunk> chunks = new ArrayList<>(windows.size());
			for (int i = 0; i < windows.size(); i++) {
				String locator = FfmpegAudioService.isVideoFilename(sourceName)
						? sourceName + ", video " + (i + 1)
						: sourceName + ", audio " + (i + 1);
				chunks.add(new PassageChunk(windows.get(i), locator));
			}
			return new ParseResult(chunks, 0, 1);
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Failed to transcribe " + sourceName + ": " + ex.getMessage(), ex);
		}
		finally {
			ffmpegAudioService.deleteQuietly(workDir);
		}
	}

	private String mergeVideoScenes(
			Path path,
			String sourceName,
			String chatApiKey,
			Consumer<String> progressCallback,
			Long productId,
			Path workDir,
			String transcript) {
		if (chatApiKey == null || chatApiKey.isBlank() || chatApiKey.trim().startsWith("sk-")) {
			return transcript;
		}
		if (progressCallback != null) {
			progressCallback.accept("Selecting unique scenes from " + sourceName);
		}
		try {
			Path frameDir = workDir.resolve("frames");
			List<SampledFrame> sampled = ffmpegAudioService.extractSampledFrames(
					path, frameDir, videoSceneSelector.frameIntervalSeconds());
			List<SampledFrame> scenes = videoSceneSelector.select(sampled);
			if (scenes.size() < 2) {
				return transcript;
			}
			List<SceneImage> images = new ArrayList<>();
			for (SampledFrame scene : scenes) {
				images.add(GeminiVideoNotesClient.fromFrame(scene, Files.readAllBytes(scene.path())));
			}
			if (progressCallback != null) {
				progressCallback.accept("Reading " + images.size() + " on-screen scenes from " + sourceName);
			}
			return geminiVideoNotesClient.combineTranscriptAndScenes(
					chatApiKey, transcript, images, productId, sourceName);
		}
		catch (Exception ex) {
			log.warn("Video scene notes skipped for {}: {}", sourceName, ex.getMessage());
			return transcript;
		}
	}

	private List<PassageChunk> parsePdf(
			Path path,
			String sourceName,
			String chatApiKey,
			Consumer<String> progressCallback,
			Long productId) throws IOException {
		List<PassageChunk> chunks = new ArrayList<>();
		boolean missingKeyForOcr = false;
		int ocrPagesDone = 0;
		boolean announcedOcrCap = false;

		try (PDDocument doc = Loader.loadPDF(path.toFile())) {
			PDFTextStripper stripper = new PDFTextStripper();
			PDFRenderer renderer = null;
			int totalPages = doc.getNumberOfPages();
			for (int page = 1; page <= totalPages; page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				String pageText = normalizeWhitespace(stripper.getText(doc));

				if (pageText.length() < PDF_OCR_MIN_CHARS) {
					if (ocrPagesDone >= PDF_OCR_MAX_PAGES) {
						if (!announcedOcrCap) {
							announcedOcrCap = true;
							log.info("Reached {}-page OCR cap for {} (total pages={})",
									PDF_OCR_MAX_PAGES, sourceName, totalPages);
							if (progressCallback != null) {
								progressCallback.accept(
										"Reached " + PDF_OCR_MAX_PAGES + "-page OCR limit for " + sourceName
												+ "; keeping extracted pages");
							}
						}
					}
					else if (chatApiKey == null || chatApiKey.isBlank()) {
						missingKeyForOcr = true;
					}
					else {
						if (progressCallback != null) {
							progressCallback.accept("OCR page " + page + "/" + totalPages + " of " + sourceName + "…");
						}
						try {
							if (renderer == null) {
								renderer = new PDFRenderer(doc);
							}
							BufferedImage image = renderer.renderImageWithDPI(page - 1, PDF_OCR_DPI);
							ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
							ImageIO.write(image, "png", pngOut);
							String ocrText = normalizeWhitespace(
									geminiPdfOcrClient.extractPageText(
											chatApiKey, pngOut.toByteArray(), page, productId));
							ocrPagesDone++;
							if (ocrText.length() > pageText.length()) {
								pageText = ocrText;
							}
						}
						catch (Exception ocrEx) {
							ocrPagesDone++;
							log.warn("Gemini OCR failed on page {}/{} of {}: {}",
									page, totalPages, sourceName, ocrEx.getMessage());
						}
					}
				}

				if (pageText.isBlank()) {
					continue;
				}
				String locator = sourceName + ", p. " + page;
				for (String part : splitTextIntoWindows(pageText)) {
					chunks.add(new PassageChunk(part, locator));
				}
			}
		}
		if (chunks.isEmpty()) {
			if (missingKeyForOcr) {
				throw new IllegalArgumentException(
						"Scanned PDF " + sourceName + " requires a Google Gemini API key (AIza…) for OCR.");
			}
			throw new IllegalArgumentException("No extractable text found in PDF " + sourceName);
		}
		return chunks;
	}

	private List<PassageChunk> parseDocx(Path path, String sourceName) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (InputStream in = Files.newInputStream(path); XWPFDocument doc = new XWPFDocument(in)) {
			for (XWPFParagraph p : doc.getParagraphs()) {
				String text = p.getText();
				if (text != null && !text.isBlank()) {
					sb.append(text.trim()).append("\n\n");
				}
			}
			for (XWPFTable table : doc.getTables()) {
				for (XWPFTableRow row : table.getRows()) {
					List<String> cells = new ArrayList<>();
					for (XWPFTableCell cell : row.getTableCells()) {
						String cellText = cell.getText();
						if (cellText != null && !cellText.isBlank()) {
							cells.add(cellText.trim());
						}
					}
					if (!cells.isEmpty()) {
						sb.append(String.join(" | ", cells)).append("\n");
					}
				}
			}
		}
		List<PassageChunk> chunks = chunkTextWithPageLocators(sb.toString(), sourceName);
		if (chunks.isEmpty()) {
			throw new IllegalArgumentException("No extractable text found in DOCX " + sourceName);
		}
		return chunks;
	}

	private List<PassageChunk> parseSrt(String rawSrt, String sourceName) {
		String[] blocks = rawSrt.split("\\r?\\n\\r?\\n");
		List<PassageChunk> chunks = new ArrayList<>();
		StringBuilder currentText = new StringBuilder();
		String currentTimestamp = "0:00";

		for (String block : blocks) {
			String[] lines = block.trim().split("\\r?\\n");
			String blockTime = null;
			StringBuilder blockLines = new StringBuilder();
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.matches("^\\d+$")) {
					continue;
				}
				Matcher m = SRT_TIME_PATTERN.matcher(trimmed);
				if (m.find()) {
					int hours = Integer.parseInt(m.group(1));
					int mins = Integer.parseInt(m.group(2));
					int secs = Integer.parseInt(m.group(3));
					int totalMins = hours * 60 + mins;
					blockTime = totalMins + ":" + String.format("%02d", secs);
					continue;
				}
				if (!trimmed.isEmpty()) {
					if (blockLines.length() > 0) {
						blockLines.append(' ');
					}
					blockLines.append(trimmed);
				}
			}
			if (blockLines.length() == 0) {
				continue;
			}
			if (currentText.length() == 0 && blockTime != null) {
				currentTimestamp = blockTime;
			}
			if (currentText.length() > 0) {
				currentText.append(' ');
			}
			currentText.append(blockLines);

			if (currentText.length() >= CHUNK_TARGET_CHARS) {
				chunks.add(new PassageChunk(currentText.toString().trim(), sourceName + " at " + currentTimestamp));
				currentText.setLength(0);
			}
		}
		if (currentText.length() > 0) {
			chunks.add(new PassageChunk(currentText.toString().trim(), sourceName + " at " + currentTimestamp));
		}
		if (chunks.isEmpty()) {
			return chunkTextWithPageLocators(rawSrt, sourceName);
		}
		return chunks;
	}

	private ParseResult parseWebSource(
			RelaySource source,
			String chatApiKey,
			Consumer<String> progressCallback,
			Long productId) {
		String rawUrl = source.getUrl() != null ? source.getUrl().trim() : "";
		if (rawUrl.isEmpty()) {
			throw new IllegalArgumentException("Missing URL for web source " + source.getName());
		}
		String fullUrl = normalizeHttpUrl(rawUrl);
		URI uri = URI.create(fullUrl);
		if (!isAllowedByRobotsTxt(uri)) {
			throw new IllegalArgumentException("Page disallowed by site's robots.txt: " + fullUrl);
		}

		String displayName = (source.getName() != null && !source.getName().isBlank())
				? source.getName()
				: uri.getHost();

		String combined = null;
		String playwrightFailureReason = null;

		try {
			String html = playwrightWebFetchService.fetchRenderedHtml(fullUrl);
			Document doc = Jsoup.parse(html, fullUrl);
			doc.select("script, style, noscript, iframe, svg, nav, footer, header, aside").remove();

			Element mainEl = doc.selectFirst("article, main, [role=main]");
			String bodyText = normalizeWhitespace(
					mainEl != null ? mainEl.text() : doc.body() != null ? doc.body().text() : "");
			String title = doc.title() != null ? doc.title().trim() : "";
			String candidate = (title.isEmpty() ? "" : title + "\n\n") + bodyText;
			if (!candidate.isBlank()) {
				combined = candidate;
			}
			else {
				playwrightFailureReason = "No readable text content found in rendered HTML";
			}
		}
		catch (Exception pwEx) {
			playwrightFailureReason = pwEx.getMessage() != null ? pwEx.getMessage() : "Browser render failed";
		}

		if (combined == null || combined.isBlank()) {
			log.warn("Playwright could not read {} ({}); falling back to Gemini URL Context",
					fullUrl, playwrightFailureReason);
			if (progressCallback != null) {
				progressCallback.accept("Browser could not read " + displayName + "; trying Gemini URL read");
			}
			try {
				Long resolvedProductId = productId != null
						? productId
						: (source.getProduct() != null ? source.getProduct().getId() : null);
				String geminiText = geminiUrlContextFetchService.extractPageText(chatApiKey, fullUrl, resolvedProductId, 0);
				combined = normalizeWhitespace(geminiText);
			}
			catch (Exception geminiEx) {
				String geminiReason = geminiEx.getMessage() != null ? geminiEx.getMessage() : "Gemini URL read failed";
				throw new IllegalArgumentException(
						"Could not read web page " + fullUrl + " (Browser: " + playwrightFailureReason
								+ "; Gemini fallback: " + geminiReason
								+ "). You can save the page as a document and upload it instead.",
						geminiEx);
			}
		}

		if (combined == null || combined.isBlank()) {
			throw new IllegalArgumentException("No readable text content found at " + fullUrl);
		}

		List<String> windows = splitTextIntoWindows(combined);
		List<PassageChunk> chunks = new ArrayList<>(windows.size());
		for (String w : windows) {
			chunks.add(new PassageChunk(w, displayName));
		}
		return new ParseResult(chunks, 0, 1);
	}

	private ParseResult parseYoutubeSource(RelaySource source) {
		String rawUrl = source.getUrl() != null ? source.getUrl().trim() : (source.getName() != null ? source.getName().trim() : "");
		if (rawUrl.isEmpty()) {
			throw new IllegalArgumentException("Missing YouTube URL or handle");
		}
		String sourceLabel = source.getName() != null && !source.getName().isBlank() ? source.getName() : rawUrl;
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		String singleVideoId = extractYoutubeVideoId(rawUrl);
		List<VideoMeta> videos = new ArrayList<>();

		if (singleVideoId != null) {
			VideoMeta meta = fetchSingleVideoMeta(http, singleVideoId);
			videos.add(meta);
		}
		else {
			videos.addAll(fetchChannelVideos(http, rawUrl));
		}

		if (videos.isEmpty()) {
			throw new IllegalArgumentException(
					"Could not find public YouTube videos for " + sourceLabel + ". Check the link or YOUTUBE_API_KEY.");
		}

		List<PassageChunk> allChunks = new ArrayList<>();
		int videosRead = 0;
		int videoIdx = 0;
		for (VideoMeta video : videos) {
			videoIdx++;
			List<PassageChunk> videoChunks = extractVideoChunks(http, sourceLabel, video, videoIdx);
			if (!videoChunks.isEmpty()) {
				allChunks.addAll(videoChunks);
				videosRead++;
			}
		}

		if (allChunks.isEmpty()) {
			throw new IllegalArgumentException("No captions or descriptions could be extracted from YouTube source " + sourceLabel);
		}
		return new ParseResult(allChunks, Math.max(1, videosRead), 0);
	}

	private record VideoMeta(String videoId, String title, String description) {
	}

	private VideoMeta fetchSingleVideoMeta(HttpClient http, String videoId) {
		if (youtubeApiKey != null && !youtubeApiKey.isBlank()) {
			try {
				String apiUrl = "https://www.googleapis.com/youtube/v3/videos?part=snippet&id="
						+ URLEncoder.encode(videoId, StandardCharsets.UTF_8)
						+ "&key=" + URLEncoder.encode(youtubeApiKey.trim(), StandardCharsets.UTF_8);
				JsonNode root = getJson(http, apiUrl);
				JsonNode items = root.path("items");
				if (items.isArray() && !items.isEmpty()) {
					JsonNode snippet = items.get(0).path("snippet");
					return new VideoMeta(
							videoId,
							snippet.path("title").asText("Video"),
							snippet.path("description").asText(""));
				}
			}
			catch (Exception ex) {
				log.warn("YouTube Data API single video lookup failed for {}: {}", videoId, ex.getMessage());
			}
		}
		try {
			String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
			Document doc = Jsoup.connect(watchUrl).userAgent("Mozilla/5.0").timeout(12000).get();
			String title = doc.title().replace(" - YouTube", "").trim();
			Element descMeta = doc.selectFirst("meta[name=description]");
			String desc = descMeta != null ? descMeta.attr("content") : "";
			return new VideoMeta(videoId, title.isEmpty() ? "Video " + videoId : title, desc);
		}
		catch (Exception ex) {
			return new VideoMeta(videoId, "Video " + videoId, "");
		}
	}

	private List<VideoMeta> fetchChannelVideos(HttpClient http, String rawUrl) {
		List<VideoMeta> result = new ArrayList<>();
		String handleOrName = extractHandleOrChannelToken(rawUrl);

		if (youtubeApiKey != null && !youtubeApiKey.isBlank()) {
			try {
				String keyParam = "&key=" + URLEncoder.encode(youtubeApiKey.trim(), StandardCharsets.UTF_8);
				String uploadsPlaylistId = null;

				if (handleOrName.startsWith("UC") && handleOrName.length() >= 22) {
					String chUrl = "https://www.googleapis.com/youtube/v3/channels?part=contentDetails&id="
							+ URLEncoder.encode(handleOrName, StandardCharsets.UTF_8) + keyParam;
					JsonNode chRoot = getJson(http, chUrl);
					uploadsPlaylistId = chRoot.path("items").path(0).path("contentDetails")
							.path("relatedPlaylists").path("uploads").asText(null);
				}
				else {
					String handle = handleOrName.startsWith("@") ? handleOrName : "@" + handleOrName;
					String chUrl = "https://www.googleapis.com/youtube/v3/channels?part=contentDetails&forHandle="
							+ URLEncoder.encode(handle, StandardCharsets.UTF_8) + keyParam;
					JsonNode chRoot = getJson(http, chUrl);
					uploadsPlaylistId = chRoot.path("items").path(0).path("contentDetails")
							.path("relatedPlaylists").path("uploads").asText(null);

					if (uploadsPlaylistId == null || uploadsPlaylistId.isBlank()) {
						String searchUrl = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&maxResults=1&q="
								+ URLEncoder.encode(handleOrName, StandardCharsets.UTF_8) + keyParam;
						JsonNode sRoot = getJson(http, searchUrl);
						String channelId = sRoot.path("items").path(0).path("snippet").path("channelId").asText(null);
						if (channelId != null && !channelId.isBlank()) {
							String byIdUrl = "https://www.googleapis.com/youtube/v3/channels?part=contentDetails&id="
									+ URLEncoder.encode(channelId, StandardCharsets.UTF_8) + keyParam;
							JsonNode byIdRoot = getJson(http, byIdUrl);
							uploadsPlaylistId = byIdRoot.path("items").path(0).path("contentDetails")
									.path("relatedPlaylists").path("uploads").asText(null);
						}
					}
				}

				if (uploadsPlaylistId != null && !uploadsPlaylistId.isBlank()) {
					String plUrl = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults="
							+ MAX_YOUTUBE_VIDEOS + "&playlistId="
							+ URLEncoder.encode(uploadsPlaylistId, StandardCharsets.UTF_8) + keyParam;
					JsonNode plRoot = getJson(http, plUrl);
					for (JsonNode item : plRoot.path("items")) {
						JsonNode snippet = item.path("snippet");
						String vid = snippet.path("resourceId").path("videoId").asText("");
						String title = snippet.path("title").asText("");
						String desc = snippet.path("description").asText("");
						if (!vid.isBlank()) {
							result.add(new VideoMeta(vid, title, desc));
						}
					}
				}
			}
			catch (Exception ex) {
				log.warn("YouTube Data API channel fetch failed for {}: {}", rawUrl, ex.getMessage());
			}
		}

		if (!result.isEmpty()) {
			return result;
		}

		// Public Atom feed fallback if YOUTUBE_API_KEY is not set
		try {
			String channelId = (handleOrName.startsWith("UC") && handleOrName.length() >= 22)
					? handleOrName
					: resolveChannelIdFromWebPage(http, rawUrl, handleOrName);
			if (channelId != null && !channelId.isBlank()) {
				String feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId;
				HttpRequest req = HttpRequest.newBuilder()
						.uri(URI.create(feedUrl))
						.timeout(Duration.ofSeconds(12))
						.header("User-Agent", "Mozilla/5.0")
						.GET()
						.build();
				HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (resp.statusCode() == 200) {
					Document xml = Jsoup.parse(resp.body(), "", Parser.xmlParser());
					for (Element entry : xml.select("entry")) {
						if (result.size() >= MAX_YOUTUBE_VIDEOS) {
							break;
						}
						String vid = entry.selectFirst("yt\\:videoId, videoId") != null
								? entry.selectFirst("yt\\:videoId, videoId").text()
								: "";
						String title = entry.selectFirst("title") != null ? entry.selectFirst("title").text() : "";
						String desc = entry.selectFirst("media\\:description, description") != null
								? entry.selectFirst("media\\:description, description").text()
								: "";
						if (!vid.isBlank()) {
							result.add(new VideoMeta(vid, title, desc));
						}
					}
				}
			}
		}
		catch (Exception ex) {
			log.warn("YouTube public feed fallback failed for {}: {}", rawUrl, ex.getMessage());
		}

		return result;
	}

	private String resolveChannelIdFromWebPage(HttpClient http, String rawUrl, String handleOrName) {
		try {
			String pageUrl = rawUrl.startsWith("http")
					? rawUrl
					: "https://www.youtube.com/" + (handleOrName.startsWith("@") ? handleOrName : "@" + handleOrName);
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(pageUrl))
					.timeout(Duration.ofSeconds(12))
					.header("User-Agent", "Mozilla/5.0")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() == 200) {
				Matcher m = YT_CHANNEL_ID_META.matcher(resp.body());
				if (m.find()) {
					return m.group(1);
				}
			}
		}
		catch (Exception ignored) {
		}
		return null;
	}

	private List<PassageChunk> extractVideoChunks(
			HttpClient http,
			String sourceLabel,
			VideoMeta video,
			int videoIdx) {
		List<PassageChunk> chunks = new ArrayList<>();

		// Try fetching timed captions for the video
		try {
			String watchUrl = "https://www.youtube.com/watch?v=" + video.videoId();
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(watchUrl))
					.timeout(Duration.ofSeconds(12))
					.header("User-Agent", "Mozilla/5.0")
					.GET()
					.build();
			HttpResponse<String> watchResp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (watchResp.statusCode() == 200) {
				Matcher m = YT_CAPTION_BASE_URL.matcher(watchResp.body());
				if (m.find()) {
					String captionUrl = m.group(1).replace("\\u0026", "&");
					HttpRequest capReq = HttpRequest.newBuilder()
							.uri(URI.create(captionUrl))
							.timeout(Duration.ofSeconds(12))
							.header("User-Agent", "Mozilla/5.0")
							.GET()
							.build();
					HttpResponse<String> capResp = http.send(capReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
					if (capResp.statusCode() == 200 && !capResp.body().isBlank()) {
						Document capXml = Jsoup.parse(capResp.body(), "", Parser.xmlParser());
						StringBuilder buf = new StringBuilder();
						String startMmSs = "0:00";
						for (Element textEl : capXml.select("text")) {
							String piece = Jsoup.parse(textEl.text()).text().trim();
							if (piece.isEmpty()) {
								continue;
							}
							if (buf.length() == 0) {
								startMmSs = formatSeconds(textEl.attr("start"));
								buf.append("Video: ").append(video.title()).append("\n");
							}
							buf.append(piece).append(' ');
							if (buf.length() >= CHUNK_TARGET_CHARS) {
								String locator = sourceLabel + ", video " + videoIdx + " at " + startMmSs;
								chunks.add(new PassageChunk(buf.toString().trim(), locator));
								buf.setLength(0);
							}
						}
						if (buf.length() > 0) {
							String locator = sourceLabel + ", video " + videoIdx + " at " + startMmSs;
							chunks.add(new PassageChunk(buf.toString().trim(), locator));
						}
					}
				}
			}
		}
		catch (Exception ex) {
			log.debug("Could not fetch timed captions for video {}: {}", video.videoId(), ex.getMessage());
		}

		// Fallback to title + description if captions were unavailable
		if (chunks.isEmpty()) {
			String combined = normalizeWhitespace(
					(video.title() != null ? video.title() : "")
							+ "\n\n"
							+ (video.description() != null ? video.description() : ""));
			if (!combined.isBlank()) {
				String locator = sourceLabel + ", video " + videoIdx + " at 0:00";
				for (String part : splitTextIntoWindows(combined)) {
					chunks.add(new PassageChunk(part, locator));
				}
			}
		}
		return chunks;
	}

	private String formatSeconds(String startAttr) {
		try {
			int totalSecs = (int) Math.floor(Double.parseDouble(startAttr));
			int mins = totalSecs / 60;
			int secs = totalSecs % 60;
			return mins + ":" + String.format("%02d", secs);
		}
		catch (Exception ex) {
			return "0:00";
		}
	}

	private String extractYoutubeVideoId(String url) {
		Matcher m = YT_VIDEO_ID_PATTERN.matcher(url);
		return m.find() ? m.group(1) : null;
	}

	private String extractHandleOrChannelToken(String url) {
		String trimmed = url.replaceFirst("^https?://(www\\.)?", "").replaceFirst("/+$", "");
		if (trimmed.contains("youtube.com/channel/")) {
			return trimmed.substring(trimmed.indexOf("youtube.com/channel/") + "youtube.com/channel/".length())
					.split("[/?#]")[0];
		}
		String[] parts = trimmed.split("/");
		for (String p : parts) {
			if (p.startsWith("@")) {
				return p.split("[?#]")[0];
			}
		}
		String last = parts[parts.length - 1].split("[?#]")[0];
		return last.isBlank() ? trimmed : last;
	}

	private JsonNode getJson(HttpClient http, String url) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() >= 400) {
			throw new IllegalArgumentException("YouTube API returned HTTP " + resp.statusCode());
		}
		return objectMapper.readTree(resp.body());
	}

	private boolean isAllowedByRobotsTxt(URI targetUri) {
		try {
			URI robotsUri = new URI(targetUri.getScheme(), targetUri.getUserInfo(), targetUri.getHost(),
					targetUri.getPort(), "/robots.txt", null, null);
			HttpClient http = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(4))
					.followRedirects(HttpClient.Redirect.NORMAL)
					.build();
			HttpRequest req = HttpRequest.newBuilder()
					.uri(robotsUri)
					.timeout(Duration.ofSeconds(4))
					.header("User-Agent", "RelayBot/1.0")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() != 200 || resp.body() == null) {
				return true;
			}
			String path = (targetUri.getRawPath() == null || targetUri.getRawPath().isEmpty())
					? "/"
					: targetUri.getRawPath();
			boolean matchingAgent = false;
			for (String rawLine : resp.body().split("\\r?\\n")) {
				String line = rawLine.split("#", 2)[0].trim();
				if (line.isEmpty()) {
					continue;
				}
				String lower = line.toLowerCase(Locale.ROOT);
				if (lower.startsWith("user-agent:")) {
					String ua = lower.substring("user-agent:".length()).trim();
					matchingAgent = "*".equals(ua) || ua.contains("relaybot");
				}
				else if (matchingAgent && lower.startsWith("disallow:")) {
					String disallow = line.substring("disallow:".length()).trim();
					if (!disallow.isEmpty() && path.startsWith(disallow)) {
						return false;
					}
				}
			}
			return true;
		}
		catch (Exception ignored) {
			return true;
		}
	}

	private String normalizeHttpUrl(String rawUrl) {
		if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
			return rawUrl;
		}
		return "https://" + rawUrl;
	}

	private List<PassageChunk> chunkTextWithPageLocators(String rawText, String sourceName) {
		String normalized = normalizeWhitespace(rawText);
		if (normalized.isBlank()) {
			return List.of();
		}
		List<String> windows = splitTextIntoWindows(normalized);
		List<PassageChunk> chunks = new ArrayList<>(windows.size());
		for (int i = 0; i < windows.size(); i++) {
			chunks.add(new PassageChunk(windows.get(i), sourceName + ", p. " + (i + 1)));
		}
		return chunks;
	}

	private List<String> splitTextIntoWindows(String text) {
		String cleaned = text.trim();
		if (cleaned.isEmpty()) {
			return List.of();
		}
		if (cleaned.length() <= CHUNK_TARGET_CHARS) {
			return List.of(cleaned);
		}
		List<String> windows = new ArrayList<>();
		int start = 0;
		int len = cleaned.length();
		while (start < len) {
			int end = Math.min(len, start + CHUNK_TARGET_CHARS);
			if (end < len) {
				int lastBreak = findNaturalBreak(cleaned, start + (CHUNK_TARGET_CHARS / 2), end);
				if (lastBreak > start) {
					end = lastBreak;
				}
			}
			String slice = cleaned.substring(start, end).trim();
			if (!slice.isEmpty()) {
				windows.add(slice);
			}
			if (end >= len) {
				break;
			}
			start = Math.max(start + 1, end - CHUNK_OVERLAP_CHARS);
		}
		return windows;
	}

	private int findNaturalBreak(String text, int minPos, int maxPos) {
		for (int i = maxPos; i > minPos; i--) {
			char c = text.charAt(i - 1);
			if (c == '\n' || c == '.' || c == '!' || c == '?') {
				return i;
			}
		}
		for (int i = maxPos; i > minPos; i--) {
			if (Character.isWhitespace(text.charAt(i - 1))) {
				return i;
			}
		}
		return maxPos;
	}

	private String normalizeWhitespace(String input) {
		if (input == null) {
			return "";
		}
		return input.replace("\r\n", "\n")
				.replaceAll("[ \\t]+", " ")
				.replaceAll("\\n{3,}", "\n\n")
				.trim();
	}

	private byte[] readHeaderBytes(Path path, int count) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			return in.readNBytes(count);
		}
	}

	private boolean isPdfMagic(byte[] b) {
		return b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
	}

	private boolean isZipMagic(byte[] b) {
		return b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
	}
}

