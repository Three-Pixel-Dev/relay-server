package com.expnai.relay.features.promote.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayPromoAsset;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayPromoAssetStatus;
import com.expnai.relay.domain.enums.RelayPromoAssetType;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayPromoAssetRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.product.service.RelayProductService;

class RelayPromoteServiceImplTest {

	private RelayProductRepository products;
	private RelayUserRepository users;
	private RelayPromoAssetRepository promoAssets;
	private RelayServiceConnectionRepository connections;
	private RelayWhopProductRepository whopProducts;
	private RelayAuditEventRepository audits;
	private RelayProductService productService;
	private RelayPromoteServiceImpl service;

	private RelayUser user;
	private RelayProduct product;
	private List<RelayPromoAsset> stored;

	@BeforeEach
	void setUp() {
		products = mock(RelayProductRepository.class);
		users = mock(RelayUserRepository.class);
		promoAssets = mock(RelayPromoAssetRepository.class);
		connections = mock(RelayServiceConnectionRepository.class);
		whopProducts = mock(RelayWhopProductRepository.class);
		audits = mock(RelayAuditEventRepository.class);
		productService = mock(RelayProductService.class);
		service = new RelayPromoteServiceImpl(
				products, users, promoAssets, connections, whopProducts, audits, productService);

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

		stored = new ArrayList<>();
		when(promoAssets.findByProductIdAndAssetTypeOrderByIdAsc(5L, RelayPromoAssetType.VIDEO))
				.thenAnswer(inv -> List.copyOf(stored));
		when(promoAssets.save(any())).thenAnswer(inv -> {
			RelayPromoAsset asset = inv.getArgument(0);
			if (!stored.contains(asset) && asset.isEnable()) {
				stored.add(asset);
			}
			if (!asset.isEnable()) {
				stored.remove(asset);
			}
			return asset;
		});
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void startFailsWhileHiggsfieldComingSoon() {
		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.start(5L));
		assertTrue(ex.getMessage().toLowerCase().contains("coming soon"));
		verify(promoAssets, never()).save(any());
	}

	@Test
	void redoFailsWhileHiggsfieldComingSoon() {
		RelayPromoAsset gate = gateVideo();
		stored.add(gate);
		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.redo(5L));
		assertTrue(ex.getMessage().toLowerCase().contains("coming soon"));
	}

	@Test
	void approveGateCreatesNineMoreAndChargesSpend() {
		stored.add(gateVideo());

		service.approve(5L);

		assertEquals(10, stored.size());
		assertEquals(RelayPromoAssetStatus.DONE, stored.get(0).getStatus());
		assertTrue(stored.stream().skip(1).allMatch(a -> a.getStatus() == RelayPromoAssetStatus.DONE));
		verify(product).setSpend(any());
		verify(audits).save(any());
		verify(productService).getProduct(5L);
	}

	@Test
	void approveRequiresGate() {
		assertThrows(BusinessRuleException.class, () -> service.approve(5L));
	}

	@Test
	void scheduleFailsWhileReachComingSoon() {
		for (int i = 0; i < 10; i++) {
			RelayPromoAsset v = new RelayPromoAsset();
			v.setProduct(product);
			v.setAssetType(RelayPromoAssetType.VIDEO);
			v.setStatus(RelayPromoAssetStatus.DONE);
			v.setTitle("v" + i);
			v.setDuration("0:20");
			stored.add(v);
		}
		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.schedule(5L));
		assertTrue(ex.getMessage().toLowerCase().contains("coming soon"));
		verify(promoAssets, never()).save(any());
	}

	@Test
	void scheduleRequiresTenDoneVideos() {
		stored.add(gateVideo());
		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.schedule(5L));
		assertTrue(ex.getMessage().toLowerCase().contains("approve"));
	}

	private RelayPromoAsset gateVideo() {
		RelayPromoAsset gate = new RelayPromoAsset();
		gate.setProduct(product);
		gate.setAssetType(RelayPromoAssetType.VIDEO);
		gate.setStatus(RelayPromoAssetStatus.GATE);
		gate.setTitle("3 things inside");
		gate.setDuration("0:20");
		return gate;
	}
}
