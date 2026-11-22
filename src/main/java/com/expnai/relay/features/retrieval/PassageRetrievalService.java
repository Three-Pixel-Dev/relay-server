package com.expnai.relay.features.retrieval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.features.ai.GeminiEmbeddingService;
import com.expnai.relay.features.ai.RelayAiCredentialService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PassageRetrievalService {

	private final JdbcTemplate jdbcTemplate;
	private final GeminiEmbeddingService geminiEmbeddingService;
	private final RelayAiCredentialService aiCredentialService;
	private final RelayProductRepository productRepository;

	@Transactional(readOnly = true)
	public List<RetrievedPassage> retrieve(Long productId, String queryText, int topK) {
		RelayProduct product = productRepository.findById(productId)
				.orElseThrow(() -> new ResourceNotFoundException("Product not found"));
		String apiKey = aiCredentialService.requireGeminiEmbeddingKey(product.getUser().getId());
		return retrieveWithApiKey(productId, queryText, topK, apiKey);
	}

	@Transactional(readOnly = true)
	public List<RetrievedPassage> retrieveWithApiKey(
			Long productId,
			String queryText,
			int topK,
			String geminiApiKey) {
		return retrieveWithApiKey(productId, queryText, topK, geminiApiKey, null);
	}

	@Transactional(readOnly = true)
	public List<RetrievedPassage> retrieveWithApiKey(
			Long productId,
			String queryText,
			int topK,
			String geminiApiKey,
			Integer buildStep) {
		int limit = Math.max(1, Math.min(topK, 100));
		String queryVector = geminiEmbeddingService.embedQueryAsVectorLiteral(
				geminiApiKey, queryText, productId, buildStep);

		String sql = """
				SELECT p.id AS passage_id,
				       p.text AS text,
				       p.locator AS locator,
				       p.source_id AS source_id,
				       s.name AS source_name,
				       s.kind AS source_kind,
				       1.0 - (p.embedding <=> CAST(? AS vector)) AS score
				FROM relay_passage p
				JOIN relay_source s ON s.id = p.source_id
				WHERE p.product_id = ?
				  AND p.is_enable = true
				  AND s.is_enable = true
				  AND p.embedding IS NOT NULL
				ORDER BY p.embedding <=> CAST(? AS vector)
				LIMIT ?
				""";

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new RetrievedPassage(
						rs.getLong("passage_id"),
						rs.getString("text"),
						rs.getString("locator"),
						rs.getLong("source_id"),
						rs.getString("source_name"),
						rs.getString("source_kind"),
						rs.getDouble("score")),
				queryVector,
				productId,
				queryVector,
				limit);
	}

	@Transactional
	public void insertPassage(
			Long productId,
			Long sourceId,
			Long userId,
			String text,
			String locator,
			String vectorLiteral) {
		Instant now = Instant.now();
		Timestamp ts = Timestamp.from(now);
		String sql = """
				INSERT INTO relay_passage (
				    product_id, source_id, text, locator, embedding,
				    created_at, updated_at, created_by, updated_by, is_enable
				) VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, ?, ?, true)
				""";
		jdbcTemplate.update(sql, productId, sourceId, text, locator, vectorLiteral, ts, ts, userId, userId);
	}
}

