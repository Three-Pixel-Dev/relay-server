package com.expnai.relay.features.promote.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayPromoAsset;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayPromoAssetStatus;
import com.expnai.relay.domain.enums.RelayPromoAssetType;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayPromoAssetRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.product.service.RelayProductService;
import com.expnai.relay.features.promote.higgsfield.HiggsfieldClient;
import com.expnai.relay.features.promote.higgsfield.RelayHiggsfieldCredentialService;
import com.expnai.relay.features.promote.worker.PromoteVideoWorker;

class RelayPromoteServiceImplTest {

	private RelayProductRepository products;
	private RelayUserRepository users;
	private RelayPromoAssetRepository promoAssets;
	private RelayServiceConnectionRepository connections;
	private RelayWhopProductRepository whopProducts;
	private RelayAuditEventRepository audits;
	private RelayProductService productService;
	private RelayHiggsfieldCredentialService higgsfieldCredentials;
	private PromoteVideoWorker worker;
	private RelayPromoteServiceImpl service;

	private RelayUser user;
	private RelayProduct product;
	private List<RelayPromoAsset> stored;
	private AtomicLong ids;

	@BeforeEach
	void setUp() {
		products = mock(RelayProductRepository.class);
		users = mock(RelayUserRepository.class);
		promoAssets = mock(RelayPromoAssetRepository.class);
		connections = mock(RelayServiceConnectionRepository.class);
		whopProducts = mock(RelayWhopProductRepository.class);
		audits = mock(RelayAuditEventRepository.class);
		productService = mock(RelayProductService.class);
		higgsfieldCredentials = mock(RelayHiggsfieldCredentialService.class);
		worker = mock(PromoteVideoWorker.class);
		service = new RelayPromoteServiceImpl(
				products, users, promoAssets, connections, whopProducts, audits, productService,
				higgsfieldCredentials, worker);

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
		when(product.getSpend()).thenReturn(BigDecimal.ZERO);
		when(products.findByIdAndUserIdForUpdate(5L, 7L)).thenReturn(Optional.of(product));
		when(products.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(productService.getProduct(5L)).thenReturn(ApiResponse.ok("OK", null));
		when(audits.save(any())).thenAnswer(inv -> inv.getArgument(0));
		doNothing().when(worker).generateAsync(anyLong(), anyLong(), anyLong(), anyBoolean());

		ids = new AtomicLong(100);
		stored = new ArrayList<>();
		when(promoAssets.findByProductIdAndAssetTypeOrderByIdAsc(5L, RelayPromoAssetType.VIDEO))
				.thenAnswer(inv -> List.copyOf(stored));
		when(promoAssets.save(any())).thenAnswer(inv -> {
			RelayPromoAsset asset = inv.getArgument(0);
			if (asset.getId() == null) {
				asset.setId(ids.getAndIncrement());
			}
			if (!stored.contains(asset) && asset.isEnable()) {
				stored.add(asset);
			}
			if (!asset.isEnable()) {
				stored.remove(asset);
			}
			return asset;
		});

		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				eq(7L), eq("higgsfield"), eq(RelayServiceStatus.CONNECTED)))
				.thenReturn(Optional.of(mock(RelayServiceConnection.class)));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void startEnqueuesRunningFirstVideo() {
		service.start(5L);

		assertEquals(1, stored.size());
		assertEquals(RelayPromoAssetStatus.RUNNING, stored.get(0).getStatus());
		verify(worker).generateAsync(5L, 7L, stored.get(0).getId(), true);
		verify(product, never()).setSpend(any());
	}

	@Test
	void startFailsWithoutHiggsfield() {
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				eq(7L), eq("higgsfield"), eq(RelayServiceStatus.CONNECTED)))
				.thenReturn(Optional.empty());
		when(higgsfieldCredentials.requireCredentials(7L))
				.thenThrow(new BusinessRuleException("Add your Higgsfield key in Connections first."));

		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.start(5L));
		assertTrue(ex.getMessage().toLowerCase().contains("higgsfield"));
		verify(promoAssets, never()).save(any());
	}

	@Test
	void approveGateEnqueuesNineRunning() {
		stored.add(gateVideo());

		service.approve(5L);

		assertEquals(10, stored.size());
		assertEquals(RelayPromoAssetStatus.DONE, stored.get(0).getStatus());
		assertTrue(stored.stream().skip(1).allMatch(a -> a.getStatus() == RelayPromoAssetStatus.RUNNING));
		verify(worker, org.mockito.Mockito.times(9)).generateAsync(eq(5L), eq(7L), anyLong(), eq(false));
		verify(audits).save(any());
		verify(productService).getProduct(5L);
	}

	@Test
	void approveRequiresGate() {
		assertThrows(BusinessRuleException.class, () -> service.approve(5L));
	}

	@Test
	void scheduleFailsWithoutReachAccount() {
		for (int i = 0; i < 10; i++) {
			RelayPromoAsset v = new RelayPromoAsset();
			v.setProduct(product);
			v.setAssetType(RelayPromoAssetType.VIDEO);
			v.setStatus(RelayPromoAssetStatus.DONE);
			v.setTitle("v" + i);
			v.setDuration("0:20");
			v.setUrl("https://cdn.example.com/v" + i + ".mp4");
			stored.add(v);
		}
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				eq(7L), org.mockito.ArgumentMatchers.anyString(), eq(RelayServiceStatus.CONNECTED)))
				.thenReturn(Optional.empty());
		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.schedule(5L));
		assertTrue(ex.getMessage().toLowerCase().contains("connect"));
		verify(promoAssets, never()).save(any());
	}

	@Test
	void scheduleSetsOnePerDayWhenYoutubeConnected() {
		for (int i = 0; i < 10; i++) {
			RelayPromoAsset v = new RelayPromoAsset();
			v.setId((long) (i + 1));
			v.setProduct(product);
			v.setAssetType(RelayPromoAssetType.VIDEO);
			v.setStatus(RelayPromoAssetStatus.DONE);
			v.setTitle("v" + i);
			v.setDuration("0:20");
			v.setUrl("https://cdn.example.com/v" + i + ".mp4");
			stored.add(v);
		}
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				eq(7L), eq("youtube"), eq(RelayServiceStatus.CONNECTED)))
				.thenReturn(Optional.of(mock(RelayServiceConnection.class)));
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				eq(7L), eq("tiktok"), eq(RelayServiceStatus.CONNECTED)))
				.thenReturn(Optional.empty());
		when(connections.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
				eq(7L), eq("instagram"), eq(RelayServiceStatus.CONNECTED)))
				.thenReturn(Optional.empty());

		service.schedule(5L);

		assertTrue(stored.stream().allMatch(a -> a.getStatus() == RelayPromoAssetStatus.SCHEDULED));
		assertTrue(stored.stream().allMatch(a -> a.getScheduledFor() != null));
		assertEquals(10, stored.stream().map(RelayPromoAsset::getScheduledFor).distinct().count());
	}

	@Test
	void scheduleRequiresTenDoneVideos() {
		stored.add(gateVideo());
		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.schedule(5L));
		assertTrue(ex.getMessage().toLowerCase().contains("approve"));
	}

	@Test
	void promptMentionsProductAndClip() {
		String prompt = PromoteVideoWorker.buildPrompt("Calmer Walks", "3 things inside", "walk routine");
		assertTrue(prompt.contains("Calmer Walks"));
		assertTrue(prompt.contains("3 things inside"));
		assertTrue(prompt.contains("9:16"));
	}

	@Test
	void parseHiggsfieldCredentials() {
		var creds = HiggsfieldClient.parseCredentials("abc123:secret-value-here");
		assertEquals("abc123", creds.keyId());
		assertEquals("secret-value-here", creds.keySecret());
		assertEquals("Key abc123:secret-value-here", creds.authorizationHeader());
	}

	private RelayPromoAsset gateVideo() {
		RelayPromoAsset gate = new RelayPromoAsset();
		gate.setId(1L);
		gate.setProduct(product);
		gate.setAssetType(RelayPromoAssetType.VIDEO);
		gate.setStatus(RelayPromoAssetStatus.GATE);
		gate.setTitle("3 things inside");
		gate.setDuration("0:20");
		return gate;
	}
}
