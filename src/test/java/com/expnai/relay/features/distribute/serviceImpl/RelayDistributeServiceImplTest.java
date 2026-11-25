package com.expnai.relay.features.distribute.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayBatch;
import com.expnai.relay.domain.entity.RelayChannel;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayBatchRepository;
import com.expnai.relay.domain.repository.RelayChannelRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.beehiiv.BeehiivClient;
import com.expnai.relay.features.distribute.setup.DistributeChannelSetupStub;
import com.expnai.relay.features.distribute.worker.DistributeOutboundWorker;
import com.expnai.relay.features.pinterest.PinterestApiClient;
import com.expnai.relay.features.pinterest.PinterestTokenService;
import com.expnai.relay.features.pinterest.config.PinterestProperties;
import com.expnai.relay.features.product.service.RelayProductService;
import com.fasterxml.jackson.databind.ObjectMapper;

class RelayDistributeServiceImplTest {

	private RelayProductRepository products;
	private RelayUserRepository users;
	private RelayChannelRepository channels;
	private RelayBatchRepository batches;
	private RelayServiceConnectionRepository connections;
	private RelayWhopProductRepository whopProducts;
	private RelayAuditEventRepository audits;
	private DistributeChannelSetupStub setupStub;
	private DistributeOutboundWorker outboundWorker;
	private RelayProductService productService;
	private RelayDistributeServiceImpl service;

	private RelayUser user;
	private RelayProduct product;
	private AtomicLong batchIds;

	@BeforeEach
	void setUp() {
		products = mock(RelayProductRepository.class);
		users = mock(RelayUserRepository.class);
		channels = mock(RelayChannelRepository.class);
		batches = mock(RelayBatchRepository.class);
		connections = mock(RelayServiceConnectionRepository.class);
		whopProducts = mock(RelayWhopProductRepository.class);
		audits = mock(RelayAuditEventRepository.class);
		setupStub = new DistributeChannelSetupStub(
				mock(com.expnai.relay.domain.repository.RelayBrandRepository.class),
				whopProducts,
				connections,
				mock(CredentialEncryptionService.class),
				mock(PinterestTokenService.class),
				mock(PinterestApiClient.class),
				new PinterestProperties(),
				mock(BeehiivClient.class),
				new ObjectMapper());
		outboundWorker = mock(DistributeOutboundWorker.class);
		doNothing().when(outboundWorker).processBatchAsync(anyLong(), anyLong());
		productService = mock(RelayProductService.class);
		service = new RelayDistributeServiceImpl(
				products, users, channels, batches, connections, whopProducts, audits, setupStub,
				outboundWorker, productService);

		user = mock(RelayUser.class);
		when(user.getId()).thenReturn(7L);
		RelayUserPrincipal principal = new RelayUserPrincipal(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
		when(users.findById(7L)).thenReturn(Optional.of(user));

		product = mock(RelayProduct.class);
		when(product.getId()).thenReturn(5L);
		when(product.getUser()).thenReturn(user);
		when(product.getStage()).thenReturn(RelayProductStage.DISTRIBUTE);
		when(product.getName()).thenReturn("Guide");
		when(products.findByIdAndUserIdForUpdate(5L, 7L)).thenReturn(Optional.of(product));
		when(productService.getProduct(5L)).thenReturn(ApiResponse.ok("OK", null));
		when(channels.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(channels.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
		batchIds = new AtomicLong(50);
		when(batches.save(any())).thenAnswer(inv -> {
			RelayBatch b = inv.getArgument(0);
			if (b.getId() == null) {
				b.setId(batchIds.getAndIncrement());
			}
			return b;
		});
		when(audits.save(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void turnOnSeoCreatesEnabledChannel() {
		when(channels.findByProductIdAndChannelTypeIgnoreCase(5L, "seo")).thenReturn(Optional.empty());

		service.turnOn(5L, "seo");

		ArgumentCaptor<RelayChannel> captor = ArgumentCaptor.forClass(RelayChannel.class);
		verify(channels).saveAndFlush(captor.capture());
		verify(channels).save(any());
		RelayChannel saved = captor.getValue();
		assertEquals("seo", saved.getChannelType());
		verify(audits).save(any());
	}

	@Test
	void turnOnPinterestRequiresConnection() {
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				7L, "pinterest", RelayServiceStatus.CONNECTED)).thenReturn(Optional.empty());

		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.turnOn(5L, "pinterest"));
		assertTrue(ex.getMessage().toLowerCase().contains("pinterest")
				|| ex.getMessage().toLowerCase().contains("connect"));
		verify(channels, never()).saveAndFlush(any());
	}

	@Test
	void turnOnLeadsRequiresBeehiiv() {
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				7L, "beehiiv", RelayServiceStatus.CONNECTED)).thenReturn(Optional.empty());

		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.turnOn(5L, "leads"));
		assertTrue(ex.getMessage().toLowerCase().contains("beehiiv")
				|| ex.getMessage().toLowerCase().contains("connect"));
	}

	@Test
	void turnOnAffiliatesRequiresWhop() {
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				7L, "whop", RelayServiceStatus.CONNECTED)).thenReturn(Optional.empty());

		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.turnOn(5L, "affiliates"));
		assertTrue(ex.getMessage().contains("Whop"));
		verify(channels, never()).saveAndFlush(any());
	}

	@Test
	void approveWeekRequiresEnabledChannel() {
		when(channels.findByProductIdAndEnabledTrueOrderByIdAsc(5L)).thenReturn(List.of());
		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.approveWeek(5L));
		assertTrue(ex.getMessage().toLowerCase().contains("turn on"));
	}

	@Test
	void approveWeekCreatesBatchAndEnqueuesWorker() {
		RelayChannel channel = new RelayChannel();
		channel.setId(9L);
		channel.setProduct(product);
		channel.setChannelType("seo");
		channel.setEnabled(true);
		channel.setWeekNumber(0);
		when(channels.findByProductIdAndEnabledTrueOrderByIdAsc(5L)).thenReturn(List.of(channel));

		service.approveWeek(5L);

		ArgumentCaptor<RelayBatch> captor = ArgumentCaptor.forClass(RelayBatch.class);
		verify(batches).save(captor.capture());
		RelayBatch batch = captor.getValue();
		assertFalse(batch.getItemsJson().isBlank());
		assertNull(batch.getPostedAt());
		assertEquals(1, channel.getWeekNumber());
		verify(outboundWorker).processBatchAsync(batch.getId(), 7L);
	}

	@Test
	void turnOnPaidRequiresPinterest() {
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				7L, "pinterest", RelayServiceStatus.CONNECTED)).thenReturn(Optional.empty());
		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.turnOn(5L, "paid"));
		assertTrue(ex.getMessage().toLowerCase().contains("pinterest")
				|| ex.getMessage().toLowerCase().contains("connect"));
	}

}
