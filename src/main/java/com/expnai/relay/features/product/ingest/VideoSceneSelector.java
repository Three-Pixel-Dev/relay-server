package com.expnai.relay.features.product.ingest;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.features.product.ingest.FfmpegAudioService.SampledFrame;

/**
 * Keeps unique video scenes: skip frames ≥ similarity-skip vs the last kept frame,
 * require the change to persist on the next sample, enforce a min gap, cap at max-scenes.
 */
@Service
public class VideoSceneSelector {

	private static final Logger log = LoggerFactory.getLogger(VideoSceneSelector.class);

	@Value("${relay.video.frame-interval-seconds:4}")
	private int frameIntervalSeconds;

	@Value("${relay.video.similarity-skip:0.85}")
	private double similaritySkip;

	@Value("${relay.video.min-scene-gap-seconds:8}")
	private double minSceneGapSeconds;

	@Value("${relay.video.max-scenes:10}")
	private int maxScenes;

	@Value("${relay.video.compare-width:64}")
	private int compareWidth;

	@Value("${relay.video.compare-height:36}")
	private int compareHeight;

	public int frameIntervalSeconds() {
		return Math.max(2, frameIntervalSeconds);
	}

	public List<SampledFrame> select(List<SampledFrame> frames) {
		if (frames == null || frames.size() < 2) {
			return List.of();
		}

		List<GrayFrame> grayed = new ArrayList<>();
		for (SampledFrame frame : frames) {
			byte[] gray = toGrayThumb(frame.path());
			if (gray != null) {
				grayed.add(new GrayFrame(frame, gray));
			}
		}
		if (grayed.size() < 2) {
			return List.of();
		}

		double skipAt = clamp01(similaritySkip);
		double minGap = Math.max(0, minSceneGapSeconds);
		int cap = Math.max(2, maxScenes);

		List<GrayFrame> survivors = new ArrayList<>();
		GrayFrame lastKeep = grayed.get(0);
		survivors.add(lastKeep);

		for (int i = 1; i < grayed.size(); i++) {
			GrayFrame current = grayed.get(i);
			if (current.frame.timeSeconds() - lastKeep.frame.timeSeconds() < minGap) {
				continue;
			}
			if (similarity(current.gray, lastKeep.gray) >= skipAt) {
				continue;
			}
			if (i + 1 >= grayed.size()) {
				survivors.add(current);
				lastKeep = current;
				continue;
			}
			GrayFrame next = grayed.get(i + 1);
			double currentVsNext = similarity(current.gray, next.gray);
			double nextVsKeep = similarity(next.gray, lastKeep.gray);
			if (currentVsNext < skipAt && nextVsKeep < skipAt) {
				continue;
			}
			if (nextVsKeep < skipAt && currentVsNext >= skipAt) {
				survivors.add(current);
				lastKeep = current;
			}
		}

		if (survivors.size() <= 1) {
			log.debug("Video scene selector kept {} frame(s); skipping on-screen images", survivors.size());
			return List.of();
		}

		List<SampledFrame> picked = spread(survivors, cap);
		log.info("Selected {} video scenes from {} sampled frames", picked.size(), frames.size());
		return picked;
	}

	private List<SampledFrame> spread(List<GrayFrame> survivors, int cap) {
		if (survivors.size() <= cap) {
			return survivors.stream().map(g -> g.frame).toList();
		}
		Set<Integer> indexes = new LinkedHashSet<>();
		for (int k = 0; k < cap; k++) {
			int idx = (int) Math.round(k * (survivors.size() - 1) / (double) (cap - 1));
			indexes.add(idx);
		}
		List<SampledFrame> out = new ArrayList<>();
		for (Integer idx : indexes) {
			out.add(survivors.get(idx).frame);
		}
		return out;
	}

	private byte[] toGrayThumb(Path path) {
		if (path == null || !Files.isRegularFile(path)) {
			return null;
		}
		try {
			BufferedImage src = ImageIO.read(path.toFile());
			if (src == null) {
				return null;
			}
			int w = Math.max(8, compareWidth);
			int h = Math.max(8, compareHeight);
			BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
			Graphics2D g = thumb.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, w, h, null);
			g.dispose();
			byte[] pixels = new byte[w * h];
			thumb.getRaster().getDataElements(0, 0, w, h, pixels);
			return pixels;
		}
		catch (Exception ex) {
			log.debug("Could not read frame {}: {}", path.getFileName(), ex.getMessage());
			return null;
		}
	}

	private double similarity(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		if (n == 0) {
			return 1;
		}
		long diff = 0;
		for (int i = 0; i < n; i++) {
			diff += Math.abs((a[i] & 0xff) - (b[i] & 0xff));
		}
		return 1.0 - (diff / (255.0 * n));
	}

	private double clamp01(double value) {
		if (value < 0) {
			return 0;
		}
		if (value > 1) {
			return 1;
		}
		return value;
	}

	private record GrayFrame(SampledFrame frame, byte[] gray) {
	}
}
