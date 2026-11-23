package com.expnai.relay.features.whop.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.domain.entity.RelayListing;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayProductFile;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayListingRepository;
import com.expnai.relay.domain.repository.RelayPartnershipRepository;
import com.expnai.relay.domain.repository.RelayProductFileRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.product.service.RelayProductService;
import com.expnai.relay.features.whop.client.WhopApiClient;
import com.expnai.relay.features.whop.dto.request.RelayWhopPublishRequest;

@SuppressWarnings({ "unchecked", "rawtypes" })
class RelayWhopServiceImplTest {

	private WhopApiClient client;
	private CredentialEncryptionService encryption;
	private RelayServiceConnectionRepository connections;
	private RelayProductRepository products;
	private RelayProductFileRepository files;
	private RelayWhopProductRepository whopProducts;
	private RelayListingRepository listings;
	private RelayProductService productService;
	private RelayWhopServiceImpl service;
	private RelayServiceConnection connection;
	private RelayProduct product;
	private RelayWhopProduct currentRow;

	@BeforeEach
	void setUp() {
		client = mock(WhopApiClient.class);
		encryption = mock(CredentialEncryptionService.class);
		connections = mock(RelayServiceConnectionRepository.class);
		products = mock(RelayProductRepository.class);
		files = mock(RelayProductFileRepository.class);
		whopProducts = mock(RelayWhopProductRepository.class);
		listings = mock(RelayListingRepository.class);
		productService = mock(RelayProductService.class);
		TransactionTemplate tx = mock(TransactionTemplate.class);
		when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback) inv.getArgument(0)).doInTransaction(null));
		doAnswer(inv -> {
			((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());

		service = new RelayWhopServiceImpl(
				client, encryption, connections, products, files,
				mock(RelayBrandRepository.class), whopProducts, listings,
				mock(RelayPartnershipRepository.class), mock(RelayThreadMessageRepository.class),
				mock(RelayAuditEventRepository.class), productService, tx);
		ReflectionTestUtils.setField(service, "publicApiUrl", "https://api.example.com/");

		RelayUser user = mock(RelayUser.class);
		when(user.getId()).thenReturn(7L);
		RelayUserPrincipal principal = new RelayUserPrincipal(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

		product = mock(RelayProduct.class);
		when(product.getId()).thenReturn(5L);
		when(product.getName()).thenReturn("Wi-Fi guide");
		when(product.getUser()).thenReturn(user);
		when(products.findByIdAndUserId(5L, 7L)).thenReturn(Optional.of(product));
		when(files.findByProductIdOrderByIdAsc(5L)).thenReturn(List.of(new RelayProductFile()));

		connection = new RelayServiceConnection();
		connection.setStatus(RelayServiceStatus.CONNECTED);
		connection.setEncryptedCredential("enc-key");
		connection.setExternalAccountId("biz_1");
		when(encryption.decrypt("enc-key")).thenReturn("whop-key-123");
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				7L, "whop", RelayServiceStatus.CONNECTED)).thenReturn(Optional.of(connection));

		when(whopProducts.findByProductId(5L)).thenReturn(Optional.empty());
		when(whopProducts.saveAndFlush(any())).thenAnswer(inv -> {
			currentRow = inv.getArgument(0);
			return currentRow;
		});
		when(whopProducts.findById(any())).thenAnswer(inv -> Optional.ofNullable(currentRow));
		when(whopProducts.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(listings.findFirstByProductIdAndPlatformIgnoreCase(5L, "whop")).thenReturn(Optional.empty());
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private static RelayWhopPublishRequest request(String price) {
		RelayWhopPublishRequest request = new RelayWhopPublishRequest();
		request.setPrice(new BigDecimal(price));
		return request;
	}

	@Test
	void connectValidatesKeyRegistersWebhookAndStoresSecrets() {
		RelayServiceConnection fresh = new RelayServiceConnection();
		when(client.getCurrentAccount("whop-key-123")).thenReturn(new WhopApiClient.WhopAccount("biz_9", "Acme"));
		when(client.createWebhook(eq("whop-key-123"), anyString(), anyString(), any()))
				.thenReturn(new WhopApiClient.WhopWebhook("hook_1", "ws_secret"));
		when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

		service.connect(fresh, " whop-key-123 ");

		assertEquals("biz_9", fresh.getExternalAccountId());
		assertEquals("Acme", fresh.getLabel());
		assertEquals(RelayServiceStatus.CONNECTED, fresh.getStatus());
		assertEquals("enc:whop-key-123", fresh.getEncryptedCredential());
		assertEquals("enc:ws_secret", fresh.getEncryptedWebhookSecret());
		assertEquals("hook_1", fresh.getWebhookExternalId());
		ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
		verify(client).createWebhook(eq("whop-key-123"), url.capture(), anyString(), any());
		assertEquals("https://api.example.com/api/webhooks/whop/" + fresh.getWebhookToken(), url.getValue());
	}

	@Test
	void connectRejectsBlankKeyWithoutCallingWhop() {
		assertThrows(BusinessRuleException.class, () -> service.connect(new RelayServiceConnection(), "  "));
		verify(client, never()).getCurrentAccount(anyString());
	}

	@Test
	void publishCreatesProductPlanAndListing() {
		when(client.createProduct(eq("whop-key-123"), eq("Wi-Fi guide"), any(), anyString(), any()))
				.thenReturn(new WhopApiClient.WhopProduct("prod_1", "wifi"));
		when(client.createPlan(eq("whop-key-123"), eq("prod_1"), anyString(), any(), eq("USD")))
				.thenReturn(new WhopApiClient.WhopPlan("plan_1", "https://whop.com/checkout/plan_1"));

		service.publish(5L, request("29"));

		ArgumentCaptor<String> redirect = ArgumentCaptor.forClass(String.class);
		verify(client).createProduct(any(), any(), any(), redirect.capture(), any());
		assertTrue(redirect.getValue().startsWith("https://api.example.com/api/public/deliveries/"));
		ArgumentCaptor<RelayWhopProduct> saved = ArgumentCaptor.forClass(RelayWhopProduct.class);
		verify(whopProducts, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		RelayWhopProduct last = saved.getAllValues().get(saved.getAllValues().size() - 1);
		assertEquals("prod_1", last.getExternalProductId());
		assertEquals("plan_1", last.getExternalPlanId());
		assertEquals("https://whop.com/checkout/plan_1", last.getCheckoutUrl());
		assertNotNull(last.getDeliveryToken());
		ArgumentCaptor<RelayListing> listing = ArgumentCaptor.forClass(RelayListing.class);
		verify(listings).save(listing.capture());
		assertEquals("whop", listing.getValue().getPlatform());
		assertEquals(0, new BigDecimal("29.00").compareTo(listing.getValue().getPrice()));
		verify(productService).getProduct(5L);
	}

	@Test
	void publishIsIdempotentWhenAlreadyLive() {
		RelayWhopProduct live = new RelayWhopProduct();
		live.setCheckoutUrl("https://whop.com/checkout/plan_1");
		when(whopProducts.findByProductId(5L)).thenReturn(Optional.of(live));

		service.publish(5L, request("29"));

		verify(client, never()).createProduct(any(), any(), any(), any(), any());
		verify(client, never()).createPlan(any(), any(), any(), any(), any());
		verify(productService).getProduct(5L);
	}

	@Test
	void publishResumesWithExistingWhopProductWhenPlanFailedBefore() {
		RelayWhopProduct partial = new RelayWhopProduct();
		partial.setExternalProductId("prod_old");
		partial.setDeliveryToken("tok");
		partial.setProduct(product);
		currentRow = partial;
		when(whopProducts.findByProductId(5L)).thenReturn(Optional.of(partial));
		when(client.createPlan(eq("whop-key-123"), eq("prod_old"), anyString(), any(), eq("USD")))
				.thenReturn(new WhopApiClient.WhopPlan("plan_2", "https://whop.com/checkout/plan_2"));

		service.publish(5L, request("15"));

		verify(client, never()).createProduct(any(), any(), any(), any(), any());
		assertEquals("plan_2", partial.getExternalPlanId());
	}

	@Test
	void publishNeedsFilesConnectionAndConfiguredUrl() {
		when(files.findByProductIdOrderByIdAsc(5L)).thenReturn(List.of());
		assertThrows(BusinessRuleException.class, () -> service.publish(5L, request("29")));

		when(files.findByProductIdOrderByIdAsc(5L)).thenReturn(List.of(new RelayProductFile()));
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				7L, "whop", RelayServiceStatus.CONNECTED)).thenReturn(Optional.empty());
		assertThrows(BusinessRuleException.class, () -> service.publish(5L, request("29")));

		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				7L, "whop", RelayServiceStatus.CONNECTED)).thenReturn(Optional.of(connection));
		ReflectionTestUtils.setField(service, "publicApiUrl", "");
		assertThrows(BusinessRuleException.class, () -> service.publish(5L, request("29")));
		verify(client, never()).createProduct(any(), any(), any(), any(), any());
	}
}
