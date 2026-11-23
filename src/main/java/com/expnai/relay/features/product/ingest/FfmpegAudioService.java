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
				ffmpegPath,
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
				ffmpegPath,
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
			run(List.of(ffmpegPath, "-version"));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(
					"Audio sources need FFmpeg on the server. Install FFmpeg and add it to PATH, "
							+ "or set RELAY_FFMPEG_PATH to the ffmpeg executable. "
							+ ex.getMessage());
		}
	}

	private String resolveFfprobePath() {
		if (ffprobePath != null && !ffprobePath.isBlank()) {
			return ffprobePath.trim();
		}
		String path = ffmpegPath != null ? ffmpegPath.trim() : "ffmpeg";
		if (path.toLowerCase(Locale.ROOT).endsWith("ffmpeg.exe")) {
			return path.substring(0, path.length() - "ffmpeg.exe".length()) + "ffprobe.exe";
		}
		if (path.toLowerCase(Locale.ROOT).endsWith("ffmpeg")) {
			return path.substring(0, path.length() - "ffmpeg".length()) + "ffprobe";
		}
		return "ffprobe";
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
