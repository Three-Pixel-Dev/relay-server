package com.expnai.relay.features.product.ingest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.features.market.MarketResearchPack.GoogleSearchResult;
import com.expnai.relay.features.market.MarketResearchPack.GoogleTrendsResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/**
 * Shared Playwright Chromium service for WEB sources and Playwright-backed Google Search / Trends.
 * <p>
 * Serializes browser usage with a {@link Semaphore} (default {@code max-concurrent=1} for GCP {@code e2-medium})
 * and executes Playwright calls on dedicated worker thread(s) that lazily initialize and reuse a shared
 * Chromium {@link Browser} instance (remote Docker WebSocket via {@code RELAY_WEB_PLAYWRIGHT_WS_ENDPOINT}
 * or local headless Chromium).
 */
@Service
@RequiredArgsConstructor
public class PlaywrightWebFetchService {

	private static final Logger log = LoggerFactory.getLogger(PlaywrightWebFetchService.class);

	private static final String DEFAULT_USER_AGENT =
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

	private static final String GOOGLE_USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

	private static final List<String> COOKIE_DISMISS_SELECTORS = List.of(
			"button#L2AGLb",
			"button:has-text('Accept all')",
			"button:has-text('Accept All')",
			"button:has-text('I agree')",
			"button:has-text('Accept cookies')",
			"form[action*='consent'] button[type='submit']"
	);

	private static final Pattern TOTAL_RESULTS_PATTERN = Pattern.compile("([\\d,.\\s]+)\\s+results?", Pattern.CASE_INSENSITIVE);
	private static final Pattern XSSI_PREFIX_PATTERN = Pattern.compile("^\\)\\]\\}'\\s*,?\\s*");

	private static final String GOOGLE_SEARCH_EXTRACT_JS = """
			(queryLower) => {
			  const statsEl = document.querySelector('#result-stats');
			  const statsText = statsEl ? (statsEl.textContent || '') : '';

			  const titles = [];
			  const seenTitles = new Set();
			  const h3s = document.querySelectorAll('#search a h3, #rso a h3');
			  for (const h3 of h3s) {
			    if (h3.closest('[jsname="yEVEwb"], [data-q]')) continue;
			    const text = (h3.textContent || '').replace(/\\s+/g, ' ').trim();
			    if (!text || seenTitles.has(text)) continue;
			    seenTitles.add(text);
			    titles.push(text.length > 75 ? text.slice(0, 75) + '…' : text);
			    if (titles.length >= 4) break;
			  }

			  const related = [];
			  const seenRelated = new Set();
			  const relatedSelectors = [
			    '#botstuff a[href*="/search?"]',
			    'a.k8XOCe',
			    'div[data-sncf] a[href*="/search?"]'
			  ];
			  for (const sel of relatedSelectors) {
			    const links = document.querySelectorAll(sel);
			    for (const a of links) {
			      const text = (a.textContent || '').replace(/\\s+/g, ' ').trim();
			      const lower = text.toLowerCase();
			      if (!text || text.length < 3 || text.length > 80 || lower === queryLower
			          || lower.includes('next') || lower.includes('previous')
			          || /^\\d+$/.test(text) || seenRelated.has(lower)) {
			        continue;
			      }
			      seenRelated.add(lower);
			      related.push(text);
			      if (related.length >= 5) break;
			    }
			    if (related.length >= 5) break;
			  }

			  return JSON.stringify({ statsText, titles, related });
			}
			""";

	private final ObjectMapper objectMapper;

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

	public GoogleSearchResult scrapeGoogleSearch(String keyword) {
		String trimmed = keyword == null ? "" : keyword.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalStateException("keyword is required");
		}
		return runSerializedTask(
				"Google search \"" + trimmed + "\"",
				() -> scrapeGoogleSearchOnWorkerThread(trimmed));
	}

	public GoogleTrendsResult scrapeGoogleTrends(String keyword) {
		String trimmed = keyword == null ? "" : keyword.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalStateException("keyword is required");
		}
		return runSerializedTask(
				"Google Trends \"" + trimmed + "\"",
				() -> scrapeGoogleTrendsOnWorkerThread(trimmed));
	}

	private <T> T runSerializedTask(String label, Callable<T> task) {
		boolean acquired = false;
		try {
			long acquireWaitMs = Math.max(120_000L, timeoutMs * 4L);
			acquired = semaphore.tryAcquire(acquireWaitMs, TimeUnit.MILLISECONDS);
			if (!acquired) {
				throw new IllegalStateException("Playwright browser is busy (" + label + ")");
			}
			Future<T> future = playwrightExecutor.submit(task);
			try {
				return future.get(timeoutMs + 20_000L, TimeUnit.MILLISECONDS);
			}
			catch (ExecutionException ex) {
				Throwable cause = ex.getCause();
				if (cause instanceof IllegalStateException ise) {
					throw ise;
				}
				String msg = cause != null && cause.getMessage() != null
						? cause.getMessage().split("\\r?\\n")[0].trim()
						: ex.getMessage();
				throw new IllegalStateException("Playwright " + label + " failed: " + msg, cause != null ? cause : ex);
			}
			catch (java.util.concurrent.TimeoutException ex) {
				future.cancel(true);
				throw new IllegalStateException("Timed out during Playwright " + label + " (" + timeoutMs + "ms)");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted during Playwright " + label, ex);
		}
		finally {
			if (acquired) {
				semaphore.release();
			}
		}
	}

	/**
	 * Loads a server-rendered page quickly: no images, media or fonts, no settle wait. Meant for pages
	 * whose data is already in the initial HTML (search result pages, YouTube's embedded JSON).
	 */
	public String fetchLightHtml(String url, Map<String, String> cookies) {
		return runSerializedTask("page " + url, () -> loadLightHtmlOnWorkerThread(url, cookies));
	}

	private String loadLightHtmlOnWorkerThread(String url, Map<String, String> cookies) {
		Browser browser = ensureSharedBrowser();
		try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
				.setUserAgent(GOOGLE_USER_AGENT)
				.setLocale("en-US")
				.setViewportSize(1366, 900)
				.setIgnoreHTTPSErrors(true))) {
			if (cookies != null && !cookies.isEmpty()) {
				List<Cookie> jar = new ArrayList<>();
				for (Map.Entry<String, String> cookie : cookies.entrySet()) {
					jar.add(new Cookie(cookie.getKey(), cookie.getValue()).setUrl(url));
				}
				context.addCookies(jar);
			}
			context.route("**/*", route -> {
				String type = route.request().resourceType();
				if ("image".equals(type) || "media".equals(type) || "font".equals(type)) {
					route.abort();
				}
				else {
					route.resume();
				}
			});
			try (Page page = context.newPage()) {
				page.setDefaultTimeout(timeoutMs);
				page.setDefaultNavigationTimeout(timeoutMs);
				page.navigate(url, new Page.NavigateOptions()
						.setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
						.setTimeout(timeoutMs));
				String html = page.content();
				if (html == null || html.isBlank()) {
					throw new IllegalStateException("Empty page returned for " + url);
				}
				return html;
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

	private GoogleSearchResult scrapeGoogleSearchOnWorkerThread(String keyword) throws Exception {
		Browser browser = ensureSharedBrowser();
		try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
				.setUserAgent(GOOGLE_USER_AGENT)
				.setLocale("en-US")
				.setTimezoneId("America/New_York")
				.setViewportSize(1366, 900)
				.setIgnoreHTTPSErrors(true));
				Page page = context.newPage()) {

			page.setDefaultTimeout(timeoutMs);
			page.setDefaultNavigationTimeout(timeoutMs);

			String url = "https://www.google.com/search?q="
					+ URLEncoder.encode(keyword, StandardCharsets.UTF_8)
					+ "&hl=en&gl=us&num=10";

			page.navigate(
					url,
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
							.setTimeout(timeoutMs));

			dismissCookieBannersBestEffort(page);

			String currentUrl = page.url();
			if ((currentUrl != null && currentUrl.contains("/sorry/"))
					|| page.locator("form#captcha-form").count() > 0) {
				throw new IllegalStateException("Google returned an automated-traffic block page (/sorry/)");
			}

			try {
				page.waitForSelector("#search, #rso", new Page.WaitForSelectorOptions().setTimeout(8000));
			}
			catch (PlaywrightException ignored) {
			}

			Object rawJson = page.evaluate(GOOGLE_SEARCH_EXTRACT_JS, keyword.toLowerCase());
			JsonNode parsed = objectMapper.readTree(String.valueOf(rawJson));

			long totalResults = parseTotalResults(parsed.path("statsText").asText(""));

			List<String> topTitles = new ArrayList<>();
			JsonNode titlesNode = parsed.path("titles");
			if (titlesNode.isArray()) {
				for (JsonNode item : titlesNode) {
					String t = item.asText("").trim();
					if (!t.isEmpty() && topTitles.size() < 4) {
						topTitles.add(t);
					}
				}
			}

			List<String> relatedQueries = new ArrayList<>();
			JsonNode relatedNode = parsed.path("related");
			if (relatedNode.isArray()) {
				for (JsonNode item : relatedNode) {
					String q = item.asText("").trim();
					if (!q.isEmpty() && relatedQueries.size() < 5) {
						relatedQueries.add(q);
					}
				}
			}

			if (totalResults == 0L && topTitles.isEmpty()) {
				throw new IllegalStateException("Google Search returned no organic results for \"" + keyword + "\"");
			}

			String queriedAt = LocalDate.now(ZoneOffset.UTC).toString();
			return new GoogleSearchResult(
					keyword,
					totalResults,
					List.copyOf(topTitles),
					List.copyOf(relatedQueries),
					queriedAt);
		}
	}

	private GoogleTrendsResult scrapeGoogleTrendsOnWorkerThread(String keyword) throws Exception {
		Browser browser = ensureSharedBrowser();
		try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
				.setUserAgent(GOOGLE_USER_AGENT)
				.setLocale("en-US")
				.setTimezoneId("America/New_York")
				.setViewportSize(1366, 900)
				.setIgnoreHTTPSErrors(true));
				Page page = context.newPage()) {

			page.setDefaultTimeout(timeoutMs);
			page.setDefaultNavigationTimeout(timeoutMs);

			AtomicReference<String> multilineBody = new AtomicReference<>();
			page.onResponse(resp -> {
				try {
					String respUrl = resp.url();
					if (respUrl == null || !respUrl.contains("/trends/api/widgetdata/multiline")) {
						return;
					}
					if (resp.status() != 200) {
						return;
					}
					String text = resp.text();
					if (text != null && !text.isBlank()) {
						multilineBody.compareAndSet(null, text);
					}
				}
				catch (Exception ignored) {
				}
			});

			String url = "https://trends.google.com/trends/explore?date="
					+ URLEncoder.encode("today 12-m", StandardCharsets.UTF_8)
					+ "&q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
					+ "&hl=en-US";

			page.navigate(
					url,
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
							.setTimeout(timeoutMs));

			dismissCookieBannersBestEffort(page);

			long deadline = System.currentTimeMillis() + Math.min(timeoutMs, 15_000L);
			while (multilineBody.get() == null && System.currentTimeMillis() < deadline) {
				page.waitForTimeout(250);
			}

			String rawBody = multilineBody.get();
			if (rawBody == null || rawBody.isBlank()) {
				throw new IllegalStateException("Google Trends returned no timeline response for \"" + keyword + "\"");
			}

			String cleanJson = XSSI_PREFIX_PATTERN.matcher(rawBody).replaceFirst("");
			JsonNode root = objectMapper.readTree(cleanJson);
			JsonNode timeline = root.path("default").path("timelineData");
			if (!timeline.isArray() || timeline.isEmpty()) {
				throw new IllegalStateException("Google Trends returned no timeline points for \"" + keyword + "\"");
			}

			List<Integer> values = new ArrayList<>();
			String firstDate = "";
			String lastDate = "";

			for (JsonNode point : timeline) {
				String dateStr = point.path("formattedTime").asText("").trim();
				if (dateStr.isEmpty()) {
					dateStr = point.path("formattedAxisTime").asText("").trim();
				}
				if (firstDate.isEmpty() && !dateStr.isEmpty()) {
					firstDate = dateStr;
				}
				if (!dateStr.isEmpty()) {
					lastDate = dateStr;
				}
				JsonNode valueArr = point.path("value");
				if (valueArr.isArray() && !valueArr.isEmpty()) {
					int v = valueArr.get(0).asInt(-1);
					if (v >= 0) {
						values.add(v);
					}
				}
			}

			if (values.isEmpty()) {
				throw new IllegalStateException("Google Trends returned no numeric values for \"" + keyword + "\"");
			}

			int sum = 0;
			int peak = 0;
			for (int v : values) {
				sum += v;
				if (v > peak) {
					peak = v;
				}
			}
			int avg = Math.round((float) sum / values.size());
			int latest = values.get(values.size() - 1);
			String direction = computeTrendsDirection(values);
			String dateRange = (!firstDate.isEmpty() && !lastDate.isEmpty())
					? firstDate + " – " + lastDate
					: "past 12 months";

			String queriedAt = LocalDate.now(ZoneOffset.UTC).toString();
			return new GoogleTrendsResult(
					keyword,
					avg,
					latest,
					peak,
					direction,
					dateRange,
					queriedAt);
		}
	}

	private static long parseTotalResults(String statsText) {
		if (statsText == null || statsText.isBlank()) {
			return 0L;
		}
		Matcher m = TOTAL_RESULTS_PATTERN.matcher(statsText);
		if (!m.find()) {
			return 0L;
		}
		String digits = m.group(1).replaceAll("[^\\d]", "");
		if (digits.isEmpty()) {
			return 0L;
		}
		try {
			return Long.parseLong(digits);
		}
		catch (NumberFormatException ex) {
			return 0L;
		}
	}

	private static String computeTrendsDirection(List<Integer> values) {
		if (values.size() < 6) {
			return "steady";
		}
		int window = Math.max(3, values.size() / 4);
		double earlyAvg = averageSlice(values, 0, window);
		double recentAvg = averageSlice(values, values.size() - window, values.size());
		if (recentAvg >= earlyAvg + 8.0) {
			return "rising";
		}
		if (recentAvg <= earlyAvg - 8.0) {
			return "declining";
		}
		return "steady";
	}

	private static double averageSlice(List<Integer> values, int startInclusive, int endExclusive) {
		int count = 0;
		int sum = 0;
		for (int i = startInclusive; i < endExclusive && i < values.size(); i++) {
			sum += values.get(i);
			count++;
		}
		return count == 0 ? 0.0 : (double) sum / count;
	}

	private void dismissCookieBannersBestEffort(Page page) {
		for (String selector : COOKIE_DISMISS_SELECTORS) {
			try {
				Locator locator = page.locator(selector).first();
				if (locator.isVisible()) {
					locator.click(new Locator.ClickOptions().setTimeout(1000));
					page.waitForTimeout(300);
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
