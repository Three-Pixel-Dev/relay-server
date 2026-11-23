package com.expnai.relay.features.product.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Converts uploaded audio/video files into 16 kHz mono WAV segments for Gemini transcription.
 * FFmpeg must be installed on the host ({@code RELAY_FFMPEG_PATH}).
 */
@Service
public class FfmpegAudioService {

	private static final Logger log = LoggerFactory.getLogger(FfmpegAudioService.class);

	public static final List<String> MEDIA_EXTENSIONS = List.of(
			".mp3", ".mp4", ".wav", ".m4a", ".aac", ".mov", ".webm", ".ogg", ".flac", ".avi", ".mkv");

	public static final List<String> VIDEO_EXTENSIONS = List.of(
			".mp4", ".mov", ".webm", ".avi", ".mkv");

	public record SampledFrame(Path path, double timeSeconds) {
	}

	@Value("${relay.ffmpeg.path:ffmpeg}")
	private String ffmpegPath;

	@Value("${relay.ffmpeg.ffprobe-path:}")
	private String ffprobePath;

	@Value("${relay.ffmpeg.timeout-seconds:300}")
	private int timeoutSeconds;

	@Value("${relay.ffmpeg.max-duration-seconds:1800}")
	private int maxDurationSeconds;

	@Value("${relay.ffmpeg.segment-seconds:480}")
	private int segmentSeconds;

	private volatile String resolvedFfmpeg;
	private volatile String resolvedFfprobe;

	public static boolean isMediaFilename(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (String ext : MEDIA_EXTENSIONS) {
			if (lower.endsWith(ext)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isVideoFilename(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (String ext : VIDEO_EXTENSIONS) {
			if (lower.endsWith(ext)) {
				return true;
			}
		}
		return false;
	}

	public List<Path> transcodeForTranscription(Path input, Path workDir) {
		if (input == null || !Files.isRegularFile(input)) {
			throw new IllegalArgumentException("Audio file is missing on disk.");
		}
		ensureFfmpegAvailable();
		try {
			Files.createDirectories(workDir);
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Could not create a temp folder for audio conversion.", ex);
		}

		double duration = probeDurationSeconds(input);
		if (duration > maxDurationSeconds) {
			int maxMinutes = Math.max(1, maxDurationSeconds / 60);
			throw new IllegalArgumentException(
					"Audio is longer than " + maxMinutes + " minutes. Trim the file or split it, then upload again.");
		}

		int window = Math.max(60, segmentSeconds);
		boolean split = duration > window + 15;
		if (split) {
			return transcodeSegmented(input, workDir, window);
		}
		Path wav = workDir.resolve("audio.wav");
		run(List.of(
				ffmpegBin(),
				"-y",
				"-i", input.toAbsolutePath().toString(),
				"-vn",
				"-ac", "1",
				"-ar", "16000",
				"-c:a", "pcm_s16le",
				wav.toAbsolutePath().toString()));
		if (!Files.isRegularFile(wav)) {
			throw new IllegalArgumentException("FFmpeg did not write a WAV file for this audio source.");
		}
		return List.of(wav);
	}

	/**
	 * Samples a JPEG every {@code intervalSeconds} (scaled to 640px wide) for scene selection.
	 * Returns an empty list for audio-only files or if FFmpeg cannot decode video.
	 */
	public List<SampledFrame> extractSampledFrames(Path input, Path frameDir, int intervalSeconds) {
		if (input == null || !Files.isRegularFile(input)) {
			return List.of();
		}
		ensureFfmpegAvailable();
		int interval = Math.max(2, intervalSeconds);
		try {
			Files.createDirectories(frameDir);
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Could not create a temp folder for video frames.", ex);
		}
		Path pattern = frameDir.resolve("frame_%04d.jpg");
		try {
			run(List.of(
					ffmpegBin(),
					"-y",
					"-i", input.toAbsolutePath().toString(),
					"-vf", "fps=1/" + interval + ",scale=640:-2",
					"-q:v", "6",
					pattern.toAbsolutePath().toString()));
		}
		catch (IllegalArgumentException ex) {
			log.warn("Could not sample video frames from {}: {}", input.getFileName(), ex.getMessage());
			return List.of();
		}
		List<Path> files = new ArrayList<>();
		try (Stream<Path> stream = Files.list(frameDir)) {
			stream.filter(p -> {
				String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return name.startsWith("frame_") && name.endsWith(".jpg");
			}).sorted().forEach(files::add);
		}
		catch (IOException ex) {
			log.warn("Could not list sampled frames: {}", ex.getMessage());
			return List.of();
		}
		List<SampledFrame> frames = new ArrayList<>(files.size());
		for (int i = 0; i < files.size(); i++) {
			frames.add(new SampledFrame(files.get(i), i * (double) interval));
		}
		return frames;
	}

	public void deleteQuietly(Path workDir) {
		if (workDir == null || !Files.exists(workDir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(workDir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ignored) {
				}
			});
		}
		catch (IOException ex) {
			log.warn("Could not delete ffmpeg temp dir {}: {}", workDir, ex.getMessage());
		}
	}

	private List<Path> transcodeSegmented(Path input, Path workDir, int windowSeconds) {
		Path pattern = workDir.resolve("seg_%03d.wav");
		run(List.of(
				ffmpegBin(),
				"-y",
				"-i", input.toAbsolutePath().toString(),
				"-vn",
				"-ac", "1",
				"-ar", "16000",
				"-c:a", "pcm_s16le",
				"-f", "segment",
				"-segment_time", String.valueOf(windowSeconds),
				"-reset_timestamps", "1",
				pattern.toAbsolutePath().toString()));
		List<Path> segments = new ArrayList<>();
		try (Stream<Path> stream = Files.list(workDir)) {
			stream.filter(p -> {
				String name = p.getFileName().toString();
				return name.startsWith("seg_") && name.endsWith(".wav");
			}).sorted().forEach(segments::add);
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Could not read FFmpeg audio segments.", ex);
		}
		if (segments.isEmpty()) {
			throw new IllegalArgumentException("FFmpeg did not produce audio segments for this file.");
		}
		return segments;
	}

	private double probeDurationSeconds(Path input) {
		try {
			String out = run(List.of(
					resolveFfprobePath(),
					"-v", "error",
					"-show_entries", "format=duration",
					"-of", "default=noprint_wrappers=1:nokey=1",
					input.toAbsolutePath().toString()));
			String first = out.strip().split("\\s+")[0];
			double seconds = Double.parseDouble(first);
			if (seconds < 0 || Double.isNaN(seconds)) {
				return 0;
			}
			return seconds;
		}
		catch (Exception ex) {
			log.warn("ffprobe duration failed for {}: {}", input.getFileName(), ex.getMessage());
			return 0;
		}
	}

	private void ensureFfmpegAvailable() {
		try {
			run(List.of(ffmpegBin(), "-version"));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(
					"Audio sources need FFmpeg on the server. Install FFmpeg and add it to PATH, "
							+ "or set RELAY_FFMPEG_PATH to the ffmpeg executable (ffmpeg on Linux, ffmpeg.exe on Windows). "
							+ ex.getMessage());
		}
	}

	private String ffmpegBin() {
		String cached = resolvedFfmpeg;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (resolvedFfmpeg == null) {
				resolvedFfmpeg = locateTool("ffmpeg", ffmpegPath);
				log.info("Using FFmpeg at {}", resolvedFfmpeg);
			}
			return resolvedFfmpeg;
		}
	}

	private String resolveFfprobePath() {
		String cached = resolvedFfprobe;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (resolvedFfprobe == null) {
				String configured = stripQuotes(ffprobePath);
				if (configured.isEmpty()) {
					Path ffmpegParent = Path.of(ffmpegBin()).toAbsolutePath().getParent();
					if (ffmpegParent != null) {
						for (String name : executableNames("ffprobe")) {
							Path sibling = ffmpegParent.resolve(name);
							if (isRunnableFile(sibling)) {
								resolvedFfprobe = sibling.toAbsolutePath().normalize().toString();
								return resolvedFfprobe;
							}
						}
					}
				}
				resolvedFfprobe = locateTool("ffprobe", ffprobePath);
			}
			return resolvedFfprobe;
		}
	}

	private String locateTool(String toolName, String configured) {
		String trimmed = stripQuotes(configured);
		List<String> names = executableNames(toolName);
		List<Path> candidates = new ArrayList<>();

		if (!trimmed.isEmpty()) {
			Path configuredPath = Path.of(trimmed);
			if (Files.isRegularFile(configuredPath)) {
				return configuredPath.toAbsolutePath().toString();
			}
			if (Files.isDirectory(configuredPath)) {
				for (String name : names) {
					candidates.add(configuredPath.resolve(name));
				}
			}
			else {
				candidates.add(configuredPath);
				for (String name : names) {
					if (!trimmed.equalsIgnoreCase(name)) {
						candidates.add(Path.of(name));
					}
				}
			}
		}
		else {
			for (String name : names) {
				candidates.add(Path.of(name));
			}
		}

		for (String name : names) {
			candidates.addAll(pathEntries(name));
			candidates.addAll(wellKnownBins(name));
			candidates.addAll(winGetPackageBins(name));
		}

		for (Path candidate : candidates) {
			if (isRunnableFile(candidate)) {
				return candidate.toAbsolutePath().normalize().toString();
			}
		}

		throw new IllegalArgumentException(
				"Could not find " + toolName + " on this OS. Install FFmpeg and keep it on PATH, "
						+ "or set RELAY_FFMPEG_PATH (and optionally RELAY_FFPROBE_PATH) to the executable "
						+ "or its bin folder. Tried RELAY_FFMPEG_PATH=" + (trimmed.isEmpty() ? "(default ffmpeg)" : trimmed));
	}

	private List<String> executableNames(String toolName) {
		String base = toolName.toLowerCase(Locale.ROOT);
		if (isWindows()) {
			return List.of(base + ".exe", base);
		}
		return List.of(base, base + ".exe");
	}

	private List<Path> pathEntries(String fileName) {
		List<Path> found = new ArrayList<>();
		String pathEnv = System.getenv("PATH");
		if (pathEnv == null || pathEnv.isBlank()) {
			return found;
		}
		String separator = isWindows() ? ";" : ":";
		for (String dir : pathEnv.split(separator)) {
			if (dir == null || dir.isBlank()) {
				continue;
			}
			found.add(Path.of(dir.trim()).resolve(fileName));
		}
		return found;
	}

	private List<Path> wellKnownBins(String fileName) {
		List<Path> found = new ArrayList<>();
		String home = System.getProperty("user.home", "");
		if (isWindows()) {
			String localAppData = System.getenv("LOCALAPPDATA");
			if (localAppData != null && !localAppData.isBlank()) {
				found.add(Path.of(localAppData, "Microsoft", "WinGet", "Links", fileName));
			}
			String programFiles = System.getenv("ProgramFiles");
			if (programFiles != null && !programFiles.isBlank()) {
				found.add(Path.of(programFiles, "ffmpeg", "bin", fileName));
			}
			found.add(Path.of("C:\\ffmpeg\\bin", fileName));
			if (!home.isBlank()) {
				found.add(Path.of(home, "scoop", "shims", fileName));
				found.add(Path.of(home, "AppData", "Local", "Microsoft", "WinGet", "Links", fileName));
			}
			String chocolatey = System.getenv("ChocolateyInstall");
			if (chocolatey != null && !chocolatey.isBlank()) {
				found.add(Path.of(chocolatey, "bin", fileName));
			}
		}
		else {
			found.add(Path.of("/usr/bin", fileName));
			found.add(Path.of("/usr/local/bin", fileName));
			found.add(Path.of("/opt/homebrew/bin", fileName));
			if (!home.isBlank()) {
				found.add(Path.of(home, ".local", "bin", fileName));
			}
		}
		return found;
	}

	private List<Path> winGetPackageBins(String fileName) {
		List<Path> found = new ArrayList<>();
		if (!isWindows() || fileName == null || fileName.isBlank()) {
			return found;
		}
		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData == null || localAppData.isBlank()) {
			return found;
		}
		Path packages = Path.of(localAppData, "Microsoft", "WinGet", "Packages");
		if (!Files.isDirectory(packages)) {
			return found;
		}
		try (Stream<Path> roots = Files.list(packages)) {
			roots.filter(Files::isDirectory)
					.filter(dir -> dir.getFileName().toString().toLowerCase(Locale.ROOT).contains("ffmpeg"))
					.forEach(pkg -> {
						try (Stream<Path> walk = Files.walk(pkg, 6)) {
							walk.filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
									.filter(this::isRunnableFile)
									.forEach(found::add);
						}
						catch (IOException ignored) {
						}
					});
		}
		catch (IOException ignored) {
		}
		return found;
	}

	private boolean isRunnableFile(Path candidate) {
		try {
			return candidate != null && Files.isRegularFile(candidate) && Files.isReadable(candidate);
		}
		catch (Exception ex) {
			return false;
		}
	}

	private boolean isWindows() {
		String os = System.getProperty("os.name", "");
		return os.toLowerCase(Locale.ROOT).contains("win");
	}

	private String stripQuotes(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		if (trimmed.length() >= 2) {
			char first = trimmed.charAt(0);
			char last = trimmed.charAt(trimmed.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return trimmed.substring(1, trimmed.length() - 1).trim();
			}
		}
		return trimmed;
	}

	private String run(List<String> command) {
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		Process process;
		try {
			process = builder.start();
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Could not start " + command.get(0) + ": " + ex.getMessage(), ex);
		}
		ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
		Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), outputBuffer));
		boolean finished;
		try {
			finished = process.waitFor(Math.max(30, timeoutSeconds), TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IllegalArgumentException("Audio conversion was interrupted.");
		}
		if (!finished) {
			process.destroyForcibly();
			throw new IllegalArgumentException("FFmpeg timed out while converting this audio file.");
		}
		try {
			reader.join(5_000);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		int code = process.exitValue();
		String output = outputBuffer.toString(StandardCharsets.UTF_8);
		if (code != 0) {
			String snippet = output.length() > 800 ? output.substring(output.length() - 800) : output;
			throw new IllegalArgumentException(
					command.get(0) + " failed (exit " + code + "): " + snippet.strip());
		}
		return output;
	}

	private void drain(InputStream in, ByteArrayOutputStream into) {
		try {
			in.transferTo(into);
		}
		catch (IOException ignored) {
		}
	}
}
