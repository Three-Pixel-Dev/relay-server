package com.expnai.relay.features.product.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ProductSseHub {

	private static final Logger log = LoggerFactory.getLogger(ProductSseHub.class);
	private static final long SSE_TIMEOUT_MS = 20 * 60 * 1000L; // 20 minutes

	private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emittersByProduct = new ConcurrentHashMap<>();

	public SseEmitter subscribe(Long productId) {
		SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
		CopyOnWriteArrayList<SseEmitter> list = emittersByProduct.computeIfAbsent(
				productId,
				k -> new CopyOnWriteArrayList<>());
		list.add(emitter);

		Runnable remove = () -> removeEmitter(productId, emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(() -> {
			remove.run();
			emitter.complete();
		});
		emitter.onError(ex -> remove.run());

		return emitter;
	}

	public void sendToEmitter(
			Long productId,
			SseEmitter emitter,
			String eventName,
			Object payload,
			boolean completeAfter) {
		try {
			emitter.send(SseEmitter.event()
					.name(eventName)
					.data(payload, MediaType.APPLICATION_JSON));
			if (completeAfter) {
				removeEmitter(productId, emitter);
				emitter.complete();
			}
		}
		catch (IOException | IllegalStateException ex) {
			removeEmitter(productId, emitter);
			try {
				emitter.completeWithError(ex);
			}
			catch (Exception ignored) {
			}
		}
	}

	public void publish(Long productId, String eventName, Object payload, boolean terminal) {
		List<SseEmitter> list = emittersByProduct.get(productId);
		if (list == null || list.isEmpty()) {
			return;
		}
		for (SseEmitter emitter : list) {
			try {
				emitter.send(SseEmitter.event()
						.name(eventName)
						.data(payload, MediaType.APPLICATION_JSON));
				if (terminal) {
					removeEmitter(productId, emitter);
					emitter.complete();
				}
			}
			catch (IOException | IllegalStateException ex) {
				log.debug("Dropping disconnected SSE emitter for product {}: {}", productId, ex.getMessage());
				removeEmitter(productId, emitter);
			}
		}
	}

	@Scheduled(fixedRate = 15000)
	public void sendKeepaliveHeartbeats() {
		if (emittersByProduct.isEmpty()) {
			return;
		}
		for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : emittersByProduct.entrySet()) {
			Long productId = entry.getKey();
			for (SseEmitter emitter : entry.getValue()) {
				try {
					emitter.send(SseEmitter.event().comment("keepalive"));
				}
				catch (IOException | IllegalStateException ex) {
					removeEmitter(productId, emitter);
				}
			}
		}
	}

	private void removeEmitter(Long productId, SseEmitter emitter) {
		CopyOnWriteArrayList<SseEmitter> list = emittersByProduct.get(productId);
		if (list != null) {
			list.remove(emitter);
			if (list.isEmpty()) {
				emittersByProduct.remove(productId, list);
			}
		}
	}
}

