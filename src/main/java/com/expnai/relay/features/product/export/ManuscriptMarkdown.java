package com.expnai.relay.features.product.export;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

/**
 * Export-only Markdown helper backed by flexmark-java.
 * Converts manuscript Markdown into valid XHTML fragments (for EPUB & HTML pack)
 * and structured layout blocks/runs (for PDF & DOCX).
 */
@Component
public class ManuscriptMarkdown {

	private static final Pattern HIGHLIGHT_PATTERN = Pattern.compile("==([^=\\n][^\\n]*?)==");

	private final Parser parser;
	private final HtmlRenderer htmlRenderer;

	public ManuscriptMarkdown() {
		MutableDataSet options = new MutableDataSet();
		options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
		options.set(HtmlRenderer.ESCAPE_HTML, true);
		this.parser = Parser.builder(options).build();
		this.htmlRenderer = HtmlRenderer.builder(options).build();
	}

	public record TextRun(String text, boolean bold, boolean italic, boolean code, boolean highlight) {
	}

	public sealed interface MdBlock permits HeadingBlock, ParagraphBlock, ListItemBlock, QuoteBlock, RuleBlock, MdTableBlock, CodeBlock {
	}

	public record HeadingBlock(int level, List<TextRun> runs) implements MdBlock {
	}

	public record ParagraphBlock(List<TextRun> runs) implements MdBlock {
	}

	public record ListItemBlock(boolean ordered, int index, int depth, List<TextRun> runs) implements MdBlock {
	}

	public record QuoteBlock(List<MdBlock> inner) implements MdBlock {
	}

	public record RuleBlock() implements MdBlock {
	}

	public record MdTableBlock(List<List<TextRun>> headers, List<List<List<TextRun>>> rows) implements MdBlock {
	}

	public record CodeBlock(List<String> lines) implements MdBlock {
	}

	public Document parse(String md) {
		String input = md != null ? md.replace("\r\n", "\n") : "";
		return parser.parse(input);
	}

	public String toHtmlFragment(String md) {
		if (md == null || md.isBlank()) {
			return "";
		}
		Document doc = parse(md);
		String html = htmlRenderer.render(doc);
		// Ensure XHTML self-closing tags for EPUB 3 XML parsers
		html = html.replace("<hr>", "<hr />").replace("<br>", "<br />");
		// Convert ==marked== outside HTML tags and <code> blocks into <mark class="highlight">
		return applyHtmlHighlights(html);
	}

	public List<MdBlock> toPdfBlocks(String md) {
		if (md == null || md.isBlank()) {
			return List.of();
		}
		Document doc = parse(md);
		List<MdBlock> blocks = new ArrayList<>();
		collectBlocks(doc, blocks, 0);
		return blocks;
	}

	private void collectBlocks(Node container, List<MdBlock> out, int listDepth) {
		for (Node child = container.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof Heading heading) {
				List<TextRun> runs = collectInlineRuns(heading);
				if (!runs.isEmpty()) {
					out.add(new HeadingBlock(heading.getLevel(), runs));
				}
			}
			else if (child instanceof Paragraph paragraph) {
				List<TextRun> runs = collectInlineRuns(paragraph);
				if (!runs.isEmpty()) {
					out.add(new ParagraphBlock(runs));
				}
			}
			else if (child instanceof BulletList bulletList) {
				collectListItems(bulletList, false, 1, listDepth, out);
			}
			else if (child instanceof OrderedList orderedList) {
				int start = orderedList.getStartNumber() > 0 ? orderedList.getStartNumber() : 1;
				collectListItems(orderedList, true, start, listDepth, out);
			}
			else if (child instanceof BlockQuote quote) {
				List<MdBlock> inner = new ArrayList<>();
				collectBlocks(quote, inner, listDepth);
				if (!inner.isEmpty()) {
					out.add(new QuoteBlock(inner));
				}
			}
			else if (child instanceof com.vladsch.flexmark.ext.tables.TableBlock flexTable) {
				MdTableBlock table = extractTable(flexTable);
				if (table != null) {
					out.add(table);
				}
			}
			else if (child instanceof ThematicBreak) {
				out.add(new RuleBlock());
			}
			else if (child instanceof FencedCodeBlock codeBlock) {
				List<String> lines = extractCodeLines(codeBlock.getContentChars().toString());
				if (!lines.isEmpty()) {
					out.add(new CodeBlock(lines));
				}
			}
			else if (child instanceof IndentedCodeBlock codeBlock) {
				List<String> lines = extractCodeLines(codeBlock.getContentChars().toString());
				if (!lines.isEmpty()) {
					out.add(new CodeBlock(lines));
				}
			}
			else if (child.hasChildren()) {
				collectBlocks(child, out, listDepth);
			}
		}
	}

	private List<String> extractCodeLines(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
		String[] split = normalized.split("\n", -1);
		int start = 0;
		int end = split.length - 1;
		while (start <= end && split[start].isBlank()) {
			start++;
		}
		while (end >= start && split[end].isBlank()) {
			end--;
		}
		if (start > end) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(end - start + 1);
		for (int i = start; i <= end; i++) {
			// Preserve leading/internal spaces; strip only trailing spaces
			lines.add(split[i].replaceFirst("[ \\t]+$", ""));
		}
		return List.copyOf(lines);
	}

	private MdTableBlock extractTable(com.vladsch.flexmark.ext.tables.TableBlock flexTable) {
		List<List<TextRun>> headers = new ArrayList<>();
		List<List<List<TextRun>>> rows = new ArrayList<>();

		for (Node part = flexTable.getFirstChild(); part != null; part = part.getNext()) {
			if (part instanceof TableHead head) {
				for (Node rowNode = head.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
					if (rowNode instanceof TableRow row && headers.isEmpty()) {
						headers.addAll(extractTableRow(row));
					}
				}
			}
			else if (part instanceof TableBody body) {
				for (Node rowNode = body.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
					if (rowNode instanceof TableRow row) {
						List<List<TextRun>> cells = extractTableRow(row);
						if (!cells.isEmpty()) {
							rows.add(cells);
						}
					}
				}
			}
		}

		if (headers.isEmpty() && rows.isEmpty()) {
			return null;
		}
		return new MdTableBlock(headers, rows);
	}

	private List<List<TextRun>> extractTableRow(TableRow row) {
		List<List<TextRun>> cells = new ArrayList<>();
		for (Node cellNode = row.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
			if (cellNode instanceof TableCell cell) {
				cells.add(collectInlineRuns(cell));
			}
		}
		return cells;
	}

	private void collectListItems(ListBlock list, boolean ordered, int startIndex, int depth, List<MdBlock> out) {
		int idx = startIndex;
		for (Node itemNode = list.getFirstChild(); itemNode != null; itemNode = itemNode.getNext()) {
			if (!(itemNode instanceof ListItem item)) {
				continue;
			}
			boolean emittedPrimary = false;
			for (Node sub = item.getFirstChild(); sub != null; sub = sub.getNext()) {
				if (sub instanceof Paragraph p) {
					List<TextRun> runs = collectInlineRuns(p);
					if (!runs.isEmpty()) {
						if (!emittedPrimary) {
							out.add(new ListItemBlock(ordered, idx, depth, runs));
							emittedPrimary = true;
						}
						else {
							out.add(new ParagraphBlock(runs));
						}
					}
				}
				else if (sub instanceof BulletList nestedBullet) {
					collectListItems(nestedBullet, false, 1, depth + 1, out);
				}
				else if (sub instanceof OrderedList nestedOrdered) {
					int nestedStart = nestedOrdered.getStartNumber() > 0 ? nestedOrdered.getStartNumber() : 1;
					collectListItems(nestedOrdered, true, nestedStart, depth + 1, out);
				}
				else if (sub instanceof BlockQuote quote) {
					List<MdBlock> inner = new ArrayList<>();
					collectBlocks(quote, inner, depth + 1);
					if (!inner.isEmpty()) {
						out.add(new QuoteBlock(inner));
					}
				}
			}
			idx++;
		}
	}

	private List<TextRun> collectInlineRuns(Node parent) {
		List<TextRun> raw = new ArrayList<>();
		walkInline(parent, false, false, raw);
		// Merge adjacent identical-style runs and trim empty runs
		List<TextRun> merged = new ArrayList<>();
		for (TextRun run : raw) {
			if (run.text().isEmpty()) {
				continue;
			}
			if (!merged.isEmpty()) {
				TextRun prev = merged.get(merged.size() - 1);
				if (prev.bold() == run.bold()
						&& prev.italic() == run.italic()
						&& prev.code() == run.code()
						&& prev.highlight() == run.highlight()) {
					merged.set(merged.size() - 1, new TextRun(
							prev.text() + run.text(), prev.bold(), prev.italic(), prev.code(), prev.highlight()));
					continue;
				}
			}
			merged.add(run);
		}
		return merged;
	}

	private void walkInline(Node parent, boolean bold, boolean italic, List<TextRun> out) {
		for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof Text textNode) {
				appendWithHighlights(textNode.getChars().toString(), bold, italic, out);
			}
			else if (child instanceof StrongEmphasis) {
				walkInline(child, true, italic, out);
			}
			else if (child instanceof Emphasis) {
				walkInline(child, bold, true, out);
			}
			else if (child instanceof Code codeNode) {
				String codeText = codeNode.getText().toString();
				if (!codeText.isEmpty()) {
					out.add(new TextRun(codeText, false, false, true, false));
				}
			}
			else if (child instanceof SoftLineBreak) {
				out.add(new TextRun(" ", bold, italic, false, false));
			}
			else if (child instanceof HardLineBreak) {
				out.add(new TextRun(" ", bold, italic, false, false));
			}
			else if (child instanceof Link link) {
				if (link.hasChildren()) {
					walkInline(link, bold, italic, out);
				}
				else {
					appendWithHighlights(link.getText().toString(), bold, italic, out);
				}
			}
			else if (child.hasChildren()) {
				walkInline(child, bold, italic, out);
			}
			else {
				String chars = child.getChars().toString();
				if (!chars.isEmpty()) {
					appendWithHighlights(chars, bold, italic, out);
				}
			}
		}
	}

	private void appendWithHighlights(String text, boolean bold, boolean italic, List<TextRun> out) {
		if (text == null || text.isEmpty()) {
			return;
		}
		Matcher m = HIGHLIGHT_PATTERN.matcher(text);
		int last = 0;
		while (m.find()) {
			if (m.start() > last) {
				out.add(new TextRun(text.substring(last, m.start()), bold, italic, false, false));
			}
			String marked = m.group(1);
			if (!marked.isEmpty()) {
				out.add(new TextRun(marked, bold, italic, false, true));
			}
			last = m.end();
		}
		if (last < text.length()) {
			out.add(new TextRun(text.substring(last), bold, italic, false, false));
		}
	}

	private String applyHtmlHighlights(String html) {
		// Split by HTML tags so we only replace ==...== inside text nodes (and not inside <code>...</code>)
		StringBuilder result = new StringBuilder(html.length());
		int i = 0;
		boolean inCode = false;
		while (i < html.length()) {
			int tagStart = html.indexOf('<', i);
			if (tagStart < 0) {
				String chunk = html.substring(i);
				result.append(inCode ? chunk : replaceMarkTags(chunk));
				break;
			}
			if (tagStart > i) {
				String chunk = html.substring(i, tagStart);
				result.append(inCode ? chunk : replaceMarkTags(chunk));
			}
			int tagEnd = html.indexOf('>', tagStart);
			if (tagEnd < 0) {
				result.append(html.substring(tagStart));
				break;
			}
			String tag = html.substring(tagStart, tagEnd + 1);
			String lower = tag.toLowerCase();
			if (lower.startsWith("<code") || lower.startsWith("<pre")) {
				inCode = true;
			}
			else if (lower.startsWith("</code") || lower.startsWith("</pre")) {
				inCode = false;
			}
			result.append(tag);
			i = tagEnd + 1;
		}
		return result.toString();
	}

	private String replaceMarkTags(String text) {
		if (!text.contains("==")) {
			return text;
		}
		return HIGHLIGHT_PATTERN.matcher(text).replaceAll("<mark class=\"highlight\">$1</mark>");
	}
}

