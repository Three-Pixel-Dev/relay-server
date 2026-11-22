package com.expnai.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.expnai.relay.common.security.TokenEncryptionService;

/**
 * Lightweight smoke: AES token encryption wiring without a database or full servlet context.
 */
@SpringBootTest(
		classes = TokenEncryptionService.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
		"relay.token-encryption.key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
		"relay.token-encryption.key-version=1"
})
class RelayApplicationTests {

	@Autowired
	private TokenEncryptionService tokenEncryptionService;

	@Test
	void contextLoads() {
		assertTrue(tokenEncryptionService.isConfigured());
		String cipher = tokenEncryptionService.encrypt("smoke-token");
		assertTrue(cipher.startsWith("enc:v1:"));
		assertEquals("smoke-token", tokenEncryptionService.decrypt(cipher));
	}

}
