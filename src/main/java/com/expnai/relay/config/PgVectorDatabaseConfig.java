package com.expnai.relay.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dev/staging auto-bootstrapper for pgvector extension, column type migration, and HNSW index.
 * For production (Neon), apply {@code classpath:db/migration/V1__phase1_pgvector_and_sources.sql}
 * and set {@code RELAY_PGVECTOR_AUTO_MIGRATE=false}.
 */
@Configuration
@ConditionalOnProperty(name = "relay.pgvector.auto-migrate", havingValue = "true", matchIfMissing = true)
public class PgVectorDatabaseConfig {

	private static final Logger log = LoggerFactory.getLogger(PgVectorDatabaseConfig.class);

	@Bean
	public static BeanPostProcessor pgVectorDataSourcePreInitializer() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
				if (bean instanceof DataSource dataSource) {
					initializePgVectorBeforeJpa(dataSource);
				}
				return bean;
			}
		};
	}

	private static void initializePgVectorBeforeJpa(DataSource dataSource) {
		try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
			log.info("pgvector extension verified");

			// If relay_passage already exists from earlier schema creation, ensure product_id and vector(1536) match before Hibernate ddl-auto=update
			if (tableExists(conn, "relay_passage")) {
				String embeddingType = columnUdtName(conn, "relay_passage", "embedding");
				if (embeddingType != null && !"vector".equalsIgnoreCase(embeddingType)) {
					log.info("Migrating relay_passage.embedding from {} to vector(1536)", embeddingType);
					stmt.execute("ALTER TABLE relay_passage ALTER COLUMN embedding TYPE vector(1536) USING NULL");
				}
				if (columnUdtName(conn, "relay_passage", "product_id") == null && tableExists(conn, "relay_source")) {
					stmt.execute("ALTER TABLE relay_passage ADD COLUMN IF NOT EXISTS product_id BIGINT");
					stmt.execute("""
							UPDATE relay_passage p
							SET product_id = s.product_id
							FROM relay_source s
							WHERE p.source_id = s.id AND p.product_id IS NULL
							""");
					stmt.execute("DELETE FROM relay_passage WHERE product_id IS NULL");
				}
			}

			if (tableExists(conn, "relay_product")) {
				if (columnUdtName(conn, "relay_product", "step_status") == null) {
					stmt.execute("ALTER TABLE relay_product ADD COLUMN IF NOT EXISTS step_status VARCHAR(20) NOT NULL DEFAULT 'todo'");
				}
				if (columnUdtName(conn, "relay_product", "cancelled") == null) {
					stmt.execute("ALTER TABLE relay_product ADD COLUMN IF NOT EXISTS cancelled BOOLEAN NOT NULL DEFAULT false");
				}
			}
		}
		catch (Exception ex) {
			log.warn("Pre-JPA pgvector check encountered non-fatal issue: {}", ex.getMessage());
		}
	}

	@Bean
	public ApplicationRunner pgVectorIndexInitializer(DataSource dataSource) {
		return args -> {
			try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
				stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
				if (tableExists(conn, "relay_passage")) {
					stmt.execute("CREATE INDEX IF NOT EXISTS idx_relay_passage_product_id ON relay_passage (product_id)");
					stmt.execute("CREATE INDEX IF NOT EXISTS idx_relay_passage_source_id ON relay_passage (source_id)");
					stmt.execute("CREATE INDEX IF NOT EXISTS idx_relay_passage_embedding_hnsw ON relay_passage USING hnsw (embedding vector_cosine_ops)");
					log.info("pgvector HNSW and product_id indexes verified on relay_passage");
				}
			}
			catch (Exception ex) {
				log.warn("Could not initialize pgvector HNSW index: {}", ex.getMessage());
			}
		};
	}

	private static boolean tableExists(Connection conn, String tableName) throws Exception {
		String sql = """
				SELECT 1 FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name = ?
				""";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, tableName);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	private static String columnUdtName(Connection conn, String tableName, String columnName) throws Exception {
		String sql = """
				SELECT udt_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
				""";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, tableName);
			ps.setString(2, columnName);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}
}

