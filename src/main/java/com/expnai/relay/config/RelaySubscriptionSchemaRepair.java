package com.expnai.relay.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate {@code ddl-auto=update} does not refresh PostgreSQL enum CHECK constraints.
 * The plan enum was renamed MONTHLY → SIX_MONTHS after the table was first created, so inserts
 * of SIX_MONTHS fail with {@code relay_subscription_plan_check} until this repair runs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RelaySubscriptionSchemaRepair implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RelaySubscriptionSchemaRepair.class);

	private final JdbcTemplate jdbcTemplate;

	public RelaySubscriptionSchemaRepair(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			jdbcTemplate.execute(
					"ALTER TABLE relay_subscription DROP CONSTRAINT IF EXISTS relay_subscription_plan_check");
			jdbcTemplate.update("UPDATE relay_subscription SET plan = 'SIX_MONTHS' WHERE plan = 'MONTHLY'");
			jdbcTemplate.execute("""
					ALTER TABLE relay_subscription
					ADD CONSTRAINT relay_subscription_plan_check
					CHECK (plan IN ('SIX_MONTHS', 'YEARLY'))
					""");

			jdbcTemplate.execute(
					"ALTER TABLE relay_subscription DROP CONSTRAINT IF EXISTS relay_subscription_status_check");
			jdbcTemplate.execute("""
					ALTER TABLE relay_subscription
					ADD CONSTRAINT relay_subscription_status_check
					CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED'))
					""");

			log.info("Repaired relay_subscription plan/status check constraints");
		} catch (Exception ex) {
			log.warn("Could not repair relay_subscription check constraints: {}", ex.getMessage());
		}
	}
}
