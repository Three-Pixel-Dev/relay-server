package com.expnai.relay.features.product.ingest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * In-process Playwright Chromium fetcher for WEB sources.
 * <p>
 * Serializes browser usage with a {@link Semaphore} (default {@code max-concurrent=1} for GCP {@code e2-medium})
 * and executes Playwright calls on dedicated worker thread(s) that lazily initialize and reuse a shared
 * Chromium {@link Browser} instance.
 */
@Service
public class PlaywrightWebFetchService {

	private static final Logger log = LoggerFactory.getLogger(PlaywrightWebFetchService.class);

	private static final String DEFAULT_USER_AGENT =
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

	private static final List<String> COOKIE_DISMISS_SELECTORS = List.of(
			"button:has-text('Accept all')",
			"button:has-text('Accept All')",
			"button:has-text('I agree')",
			"button:has-text('Accept cookies')"
	);

	@Value("${relay.web.playwright.headless:true}")
	private boolean headless;

	@Value("${relay.web.playwright.ws-endpoint:}")
	private String wsEndpoint;

	@Value("${relay.web.playwright.timeout-ms:30000}")
	private int timeoutMs;

	@Value("${relay.web.playwright.max-concurrent:1}")
	private int maxConcurrent;

	@Value("${relay.web.playwright.settle-ms:1500}")
	private int settleMs;

	private Semaphore semaphore;
	private ExecutorService playwrightExecutor;
	private final List<BrowserHolder> browserHolders = new CopyOnWriteArrayList<>();
	private final ThreadLocal<BrowserHolder> threadBrowserHolder = new ThreadLocal<>();

	@PostConstruct
	public void init() {
		int concurrency = Math.max(1, maxConcurrent);
		this.semaphore = new Semaphore(concurrency, true);
		AtomicInteger threadIdx = new AtomicInteger(1);
		this.playwrightExecutor = Executors.newFixedThreadPool(concurrency, runnable -> {
			Thread t = new Thread(runnable, "relay-playwright-" + threadIdx.getAndIncrement());
			t.setDaemon(true);
			return t;
		});
	}

	public String fetchRenderedHtml(String url) {
		boolean acquired = false;
		try {
			long acquireWaitMs = Math.max(60_000L, timeoutMs * 2L);
			acquired = semaphore.tryAcquire(acquireWaitMs, TimeUnit.MILLISECONDS);
			if (!acquired) {
				throw new IllegalArgumentException(
						"Web reader is busy processing another page. Please try again in a moment.");
			}

			Future<String> future = playwrightExecutor.submit(() -> renderPageOnWorkerThread(url));
			try {
				return future.get(timeoutMs + settleMs + 15_000L, TimeUnit.MILLISECONDS);
			}
			catch (ExecutionException ex) {
				Throwable cause = ex.getCause();
				if (cause instanceof IllegalArgumentException iae) {
					throw iae;
				}
				throw mapPlaywrightError(url, cause != null ? cause : ex);
			}
			catch (java.util.concurrent.TimeoutException ex) {
				future.cancel(true);
				throw new IllegalArgumentException(
						"Timed out waiting for web page to render (" + timeoutMs + "ms): " + url);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalArgumentException("Interrupted while reading web page: " + url, ex);
		}
		finally {
			if (acquired) {
				semaphore.release();
			}
		}
	}

	private String renderPageOnWorkerThread(String url) {
		Browser browser = ensureSharedBrowser();
		try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
				.setUserAgent(DEFAULT_USER_AGENT)
				.setViewportSize(1280, 800)
				.setIgnoreHTTPSErrors(true));
				Page page = context.newPage()) {

			page.setDefaultTimeout(timeoutMs);
			page.setDefaultNavigationTimeout(timeoutMs);

			Response response = page.navigate(
					url,
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
							.setTimeout(timeoutMs));

			if (response != null && response.status() >= 400) {
				throw new IllegalArgumentException(
						"Web page returned HTTP " + response.status() + " for " + url);
			}

			// Wait briefly for network activity and client-rendered JS to settle
			if (settleMs > 0) {
				try {
					page.waitForLoadState(
							LoadState.NETWORKIDLE,
							new Page.WaitForLoadStateOptions().setTimeout(Math.min(settleMs * 2.0, 4000.0)));
				}
				catch (PlaywrightException ignored) {
					// Many pages keep background analytics/websockets open; fall back to fixed settle wait
				}
				page.waitForTimeout(settleMs);
			}

			dismissCookieBannersBestEffort(page);

			String html = page.content();
			if (html == null || html.isBlank()) {
				throw new IllegalArgumentException("Rendered web page returned empty HTML for " + url);
			}
			return html;
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw mapPlaywrightError(url, ex);
		}
	}

	private void dismissCookieBannersBestEffort(Page page) {
		for (String selector : COOKIE_DISMISS_SELECTORS) {
			try {
				Locator locator = page.locator(selector).first();
				if (locator.isVisible()) {
					locator.click(new Locator.ClickOptions().setTimeout(500));
					page.waitForTimeout(200);
					break;
				}
			}
			catch (Exception ignored) {
			}
		}
	}

	private Browser ensureSharedBrowser() {
		BrowserHolder holder = threadBrowserHolder.get();
		if (holder != null && holder.browser != null && holder.browser.isConnected()) {
			return holder.browser;
		}
		if (holder != null) {
			holder.closeQuietly();
			browserHolders.remove(holder);
			threadBrowserHolder.remove();
		}

		// Remote WS mode must skip local Chromium download/host validation — otherwise
		// Playwright.create() blocks downloading ~160MB browsers even though we only connect().
		boolean remote = wsEndpoint != null && !wsEndpoint.isBlank();
		Playwright.CreateOptions createOptions = new Playwright.CreateOptions();
		if (remote) {
			createOptions.setEnv(Map.of(
					"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1",
					"PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "1"));
		}
		Playwright pw = Playwright.create(createOptions);
		try {
			Browser browser;
			if (remote) {
				String endpoint = wsEndpoint.trim();
				log.info("Connecting to remote Playwright Chromium server at {}", endpoint);
				browser = pw.chromium().connect(
						endpoint,
						new BrowserType.ConnectOptions().setTimeout((double) timeoutMs));
			}
			else {
				log.info("Launching shared Playwright Chromium browser (headless={})", headless);
				browser = pw.chromium().launch(new BrowserType.LaunchOptions()
						.setHeadless(headless)
						.setArgs(List.of(
								"--no-sandbox",
								"--disable-setuid-sandbox",
								"--disable-dev-shm-usage",
								"--disable-gpu")));
			}
			BrowserHolder newHolder = new BrowserHolder(pw, browser);
			threadBrowserHolder.set(newHolder);
			browserHolders.add(newHolder);
			return browser;
		}
		catch (Exception ex) {
			try {
				pw.close();
			}
			catch (Exception ignored) {
			}
			throw ex;
		}
	}

	private IllegalArgumentException mapPlaywrightError(String url, Throwable error) {
		if (error instanceof TimeoutError) {
			return new IllegalArgumentException(
					"Timed out loading web page (" + timeoutMs + "ms): " + url
							+ ". You can also save the page as a PDF or text file and upload it directly.");
		}
		String msg = error != null && error.getMessage() != null ? error.getMessage() : "Unknown browser error";
		if (wsEndpoint != null && !wsEndpoint.isBlank()
				&& (msg.contains("ECONNREFUSED") || msg.contains("WebSocket") || msg.contains("connect"))) {
			log.error("Could not connect to Playwright WS server at {}: {}", wsEndpoint, msg);
			return new IllegalArgumentException(
					"Could not connect to Playwright browser server at " + wsEndpoint.trim() + " (" + msg.split("\\r?\\n")[0].trim() + ")");
		}
		if (msg.contains("Executable doesn't exist") || msg.contains("playwright install")) {
			log.error("Playwright Chromium binary is not installed on this host: {}", msg);
			return new IllegalArgumentException(
					"Playwright Chromium browser is not installed on the server. Run: mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install --with-deps chromium\"");
		}
		String firstLine = msg.split("\\r?\\n")[0].trim();
		return new IllegalArgumentException(
				"Could not render web page " + url + " (" + firstLine + "). You can upload a document instead.");
	}

	@PreDestroy
	public void shutdown() {
		if (playwrightExecutor != null) {
			try {
				playwrightExecutor.submit(() -> {
					for (BrowserHolder holder : browserHolders) {
						holder.closeQuietly();
					}
					browserHolders.clear();
				}).get(5, TimeUnit.SECONDS);
			}
			catch (Exception ignored) {
				for (BrowserHolder holder : browserHolders) {
					holder.closeQuietly();
				}
				browserHolders.clear();
			}
			playwrightExecutor.shutdownNow();
		}
	}

	private record BrowserHolder(Playwright playwright, Browser browser) {
		void closeQuietly() {
			try {
				if (browser != null) {
					browser.close();
				}
			}
			catch (Exception ignored) {
			}
			try {
				if (playwright != null) {
					playwright.close();
				}
			}
			catch (Exception ignored) {
			}
		}
	}
}

