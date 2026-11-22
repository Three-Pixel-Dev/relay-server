package com.expnai.relay.features.product.export;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.enums.RelayProductType;
import com.expnai.relay.features.product.export.ManuscriptMarkdown.HeadingBlock;
import com.expnai.relay.features.product.export.ManuscriptMarkdown.ListItemBlock;
import com.expnai.relay.features.product.export.ManuscriptMarkdown.MdBlock;
import com.expnai.relay.features.product.export.ManuscriptMarkdown.MdTableBlock;
import com.expnai.relay.features.product.export.ManuscriptMarkdown.ParagraphBlock;
import com.expnai.relay.features.product.export.ManuscriptMarkdown.QuoteBlock;
import com.expnai.relay.features.product.export.ManuscriptMarkdown.RuleBlock;
import com.expnai.relay.features.product.export.ManuscriptMarkdown.TextRun;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProductDocumentRenderer {

	// Matches relay-web/src/shared/catalog/relay-catalog.ts
	private static final String[][] PALETTES = {
			{ "#f4f1ea", "#25262b", "#8a3b2e", "#dccdbf", "#5f6168" }, // 0 Editorial
			{ "#f7f6f2", "#1f2a44", "#3b7a6e", "#cfe0d8", "#6b7280" }, // 1 Calm
			{ "#fffdf5", "#14121c", "#ffcf5a", "#ffe9b0", "#4b4563" }  // 2 Bold
	};

	private final ManuscriptMarkdown manuscriptMarkdown;

	public byte[] renderPdf(
			RelayBrand brand,
			List<RelaySection> sections,
			RelayProductType productType,
			PDRectangle pageSize) throws IOException {
		return renderPdf(brand, sections, productType, pageSize, null, Map.of());
	}

	public byte[] renderPdf(
			RelayBrand brand,
			List<RelaySection> sections,
			RelayProductType productType,
			PDRectangle pageSize,
			byte[] coverImageBytes,
			Map<Long, byte[]> sectionGraphics) throws IOException {

		String title = safeTitle(brand);
		String[] cols = paletteColors(brand);
		Color bg = parseHex(cols[0]);
		Color ink = parseHex(cols[1]);
		Color accent = parseHex(cols[2]);
		Color soft = parseHex(cols[3]);
		Color muted = parseHex(cols[4]);
		Map<Long, byte[]> safeGraphics = sectionGraphics != null ? sectionGraphics : Map.of();

		try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDFont fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			PDFont fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
			PDFont fontOblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
			PDFont fontBoldOblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE);
			PDFont fontCode = new PDType1Font(Standard14Fonts.FontName.COURIER);
			PdfFonts fonts = new PdfFonts(fontRegular, fontBold, fontOblique, fontBoldOblique, fontCode);
			PdfPalette palette = new PdfPalette(bg, ink, accent, soft, muted);

			float pageW = pageSize.getWidth();
			float pageH = pageSize.getHeight();
			float margin = 56f;
			float contentW = pageW - margin * 2f;

			// Cover page: full-bleed generated cover image when available, else vector fallback
			PDPage coverPage = new PDPage(pageSize);
			doc.addPage(coverPage);
			try (PDPageContentStream cs = new PDPageContentStream(doc, coverPage)) {
				boolean drewImageCover = false;
				if (coverImageBytes != null && coverImageBytes.length > 0) {
					try {
						PDImageXObject coverImg = PDImageXObject.createFromByteArray(doc, coverImageBytes, "cover");
						cs.drawImage(coverImg, 0, 0, pageW, pageH);
						drewImageCover = true;
					}
					catch (Exception ignored) {
						drewImageCover = false;
					}
				}
				if (!drewImageCover) {
					cs.setNonStrokingColor(bg);
					cs.addRect(0, 0, pageW, pageH);
					cs.fill();

					// Accent top bar + soft bottom band
					cs.setNonStrokingColor(accent);
					cs.addRect(0, pageH - 28f, pageW, 28f);
					cs.fill();

					cs.setNonStrokingColor(soft);
					cs.addRect(0, 0, pageW, 92f);
					cs.fill();

					String typeLabel = productType != null ? productType.getCode().toUpperCase() : "DIGITAL PRODUCT";
					cs.beginText();
					cs.setFont(fontBold, 11f);
					cs.setNonStrokingColor(accent);
					cs.newLineAtOffset(margin, pageH - 110f);
					cs.showText(toPdfSafeText(typeLabel));
					cs.endText();

					float titleSize = 30f;
					List<String> titleLines = wrapText(toPdfSafeText(title), fontBold, titleSize, contentW);
					float ty = pageH - 165f;
					cs.setNonStrokingColor(ink);
					for (String line : titleLines) {
						cs.beginText();
						cs.setFont(fontBold, titleSize);
						cs.newLineAtOffset(margin, ty);
						cs.showText(line);
						cs.endText();
						ty -= 38f;
					}

					cs.setNonStrokingColor(accent);
					cs.addRect(margin, ty - 10f, 72f, 4f);
					cs.fill();

					cs.beginText();
					cs.setFont(fontRegular, 11f);
					cs.setNonStrokingColor(muted);
					cs.newLineAtOffset(margin, 44f);
					cs.showText(toPdfSafeText(sections.size() + " sections"));
					cs.endText();
				}
			}

			// Section pages
			int sectionNum = 0;
			for (RelaySection section : sections) {
				sectionNum++;
				PDPage page = new PDPage(pageSize);
				doc.addPage(page);
				PDPageContentStream cs = new PDPageContentStream(doc, page);
				try {
					float y = pageH - margin;

					// Section eyebrow
					cs.beginText();
					cs.setFont(fontBold, 10f);
					cs.setNonStrokingColor(accent);
					cs.newLineAtOffset(margin, y);
					cs.showText(toPdfSafeText("SECTION " + sectionNum));
					cs.endText();
					y -= 24f;

					// Section title
					String secTitle = section.getTitle() != null ? section.getTitle() : ("Section " + sectionNum);
					for (String line : wrapText(toPdfSafeText(secTitle), fontBold, 20f, contentW)) {
						cs.beginText();
						cs.setFont(fontBold, 20f);
						cs.setNonStrokingColor(ink);
						cs.newLineAtOffset(margin, y);
						cs.showText(line);
						cs.endText();
						y -= 26f;
					}

					// Moment subtitle
					if (section.getMoment() != null && !section.getMoment().isBlank()) {
						y -= 2f;
						for (String line : wrapText(toPdfSafeText(section.getMoment()), fontOblique, 11f, contentW)) {
							cs.beginText();
							cs.setFont(fontOblique, 11f);
							cs.setNonStrokingColor(muted);
							cs.newLineAtOffset(margin, y);
							cs.showText(line);
							cs.endText();
							y -= 15f;
						}
					}

					// Divider
					y -= 8f;
					cs.setNonStrokingColor(soft);
					cs.addRect(margin, y, contentW, 1.5f);
					cs.fill();
					y -= 18f;

					// Optional section spot graphic (fixed max height under heading)
					byte[] secImgBytes = section.getId() != null ? safeGraphics.get(section.getId()) : null;
					if (secImgBytes != null && secImgBytes.length > 0) {
						try {
							PDImageXObject secImg = PDImageXObject.createFromByteArray(
									doc, secImgBytes, "section-" + sectionNum);
							float imgW = secImg.getWidth();
							float imgH = secImg.getHeight();
							if (imgW > 0 && imgH > 0) {
								float maxH = 165f;
								float scale = Math.min(contentW / imgW, maxH / imgH);
								float drawW = imgW * scale;
								float drawH = imgH * scale;
								float drawX = margin + (contentW - drawW) / 2f;
								if (y - drawH > margin + 40f) {
									cs.drawImage(secImg, drawX, y - drawH, drawW, drawH);
									y -= (drawH + 18f);
								}
							}
						}
						catch (Exception ignored) {
						}
					}

					// Styled Markdown body blocks
					PdfCursor cursor = new PdfCursor(doc, pageSize, cs, y, margin, contentW, fonts, palette);
					try {
						List<MdBlock> blocks = manuscriptMarkdown.toPdfBlocks(section.getText());
						for (MdBlock block : blocks) {
							renderPdfBlock(cursor, block, margin, contentW, false);
						}
					}
					finally {
						cs = cursor.cs;
					}
				}
				finally {
					cs.close();
				}
			}

			doc.save(out);
			return out.toByteArray();
		}
	}

	private record PdfFonts(PDFont regular, PDFont bold, PDFont oblique, PDFont boldOblique, PDFont code) {
		PDFont forRun(TextRun run) {
			if (run.code()) {
				return code;
			}
			if (run.bold() && run.italic()) {
				return boldOblique;
			}
			if (run.bold()) {
				return bold;
			}
			if (run.italic()) {
				return oblique;
			}
			return regular;
		}
	}

	private record PdfPalette(Color bg, Color ink, Color accent, Color soft, Color muted) {
	}

	private record StyledWord(String text, PDFont font, float fontSize, Color color, boolean highlight, boolean code) {
	}

	private static final class PdfCursor {
		final PDDocument doc;
		final PDRectangle pageSize;
		PDPageContentStream cs;
		float y;
		final float margin;
		final float contentW;
		final PdfFonts fonts;
		final PdfPalette palette;

		PdfCursor(
				PDDocument doc,
				PDRectangle pageSize,
				PDPageContentStream cs,
				float y,
				float margin,
				float contentW,
				PdfFonts fonts,
				PdfPalette palette) {
			this.doc = doc;
			this.pageSize = pageSize;
			this.cs = cs;
			this.y = y;
			this.margin = margin;
			this.contentW = contentW;
			this.fonts = fonts;
			this.palette = palette;
		}

		void ensureSpace(float needed) throws IOException {
			if (y - needed < margin + 12f) {
				cs.close();
				PDPage next = new PDPage(pageSize);
				doc.addPage(next);
				cs = new PDPageContentStream(doc, next);
				y = pageSize.getHeight() - margin;
			}
		}
	}

	private void renderPdfBlock(PdfCursor cursor, MdBlock block, float x, float width, boolean inQuote)
			throws IOException {
		if (block instanceof HeadingBlock heading) {
			float size = heading.level() <= 1 ? 16f : (heading.level() == 2 ? 14.5f : 12.5f);
			float lineH = size * 1.38f;
			Color color = heading.level() == 2 ? cursor.palette.accent() : cursor.palette.ink();
			cursor.y -= 8f;
			cursor.ensureSpace(lineH * 2.2f);
			List<TextRun> boldRuns = heading.runs().stream()
					.map(r -> new TextRun(r.text(), true, r.italic(), r.code(), r.highlight()))
					.toList();
			List<List<StyledWord>> lines = wrapStyledRuns(boldRuns, cursor.fonts, size, color, width);
			drawStyledLines(cursor, lines, x, lineH);
			cursor.y -= 6f;
		}
		else if (block instanceof ParagraphBlock para) {
			float size = inQuote ? 10.5f : 11f;
			float lineH = inQuote ? 15.5f : 16.5f;
			Color color = inQuote ? cursor.palette.ink() : cursor.palette.ink();
			List<List<StyledWord>> lines = wrapStyledRuns(para.runs(), cursor.fonts, size, color, width);
			drawStyledLines(cursor, lines, x, lineH);
			cursor.y -= (inQuote ? 5f : 8f);
		}
		else if (block instanceof ListItemBlock item) {
			float size = 11f;
			float lineH = 16.5f;
			float indent = 16f + item.depth() * 14f;
			float textX = x + indent;
			float textW = Math.max(80f, width - indent);
			List<List<StyledWord>> lines = wrapStyledRuns(item.runs(), cursor.fonts, size, cursor.palette.ink(), textW);
			if (!lines.isEmpty()) {
				cursor.ensureSpace(lineH);
				// Draw bullet dot or ordered number on the first line
				if (item.ordered()) {
					cursor.cs.beginText();
					cursor.cs.setFont(cursor.fonts.bold(), size);
					cursor.cs.setNonStrokingColor(cursor.palette.accent());
					cursor.cs.newLineAtOffset(x + item.depth() * 14f, cursor.y);
					cursor.cs.showText(item.index() + ".");
					cursor.cs.endText();
				}
				else {
					float dotX = x + item.depth() * 14f + 5f;
					float dotY = cursor.y + 3.5f;
					cursor.cs.setNonStrokingColor(cursor.palette.accent());
					cursor.cs.addRect(dotX - 1.8f, dotY - 1.8f, 3.6f, 3.6f);
					cursor.cs.fill();
				}
				drawStyledLines(cursor, lines, textX, lineH);
				cursor.y -= 4f;
			}
		}
		else if (block instanceof QuoteBlock quote) {
			float padX = 12f;
			float padY = 8f;
			float innerX = x + padX + 4f;
			float innerW = Math.max(80f, width - padX * 2f - 4f);

			// Collect wrapped lines for height estimation so we can draw the soft callout background
			List<List<StyledWord>> allLines = new ArrayList<>();
			for (MdBlock innerBlock : quote.inner()) {
				if (innerBlock instanceof ParagraphBlock p) {
					allLines.addAll(wrapStyledRuns(p.runs(), cursor.fonts, 10.5f, cursor.palette.ink(), innerW));
				}
				else if (innerBlock instanceof HeadingBlock h) {
					List<TextRun> boldRuns = h.runs().stream()
							.map(r -> new TextRun(r.text(), true, r.italic(), r.code(), r.highlight()))
							.toList();
					allLines.addAll(wrapStyledRuns(boldRuns, cursor.fonts, 11.5f, cursor.palette.accent(), innerW));
				}
				else if (innerBlock instanceof ListItemBlock li) {
					allLines.addAll(wrapStyledRuns(li.runs(), cursor.fonts, 10.5f, cursor.palette.ink(), innerW));
				}
			}
			if (allLines.isEmpty()) {
				return;
			}

			float lineH = 15.5f;
			float totalH = allLines.size() * lineH + padY * 2f;
			cursor.y -= 4f;

			// If entire quote fits on one page, draw single cohesive callout card
			if (totalH <= (cursor.pageSize.getHeight() - cursor.margin * 2f - 20f)) {
				cursor.ensureSpace(totalH + 6f);
				float boxTop = cursor.y + 6f;
				float boxBottom = boxTop - totalH;
				cursor.cs.setNonStrokingColor(cursor.palette.bg());
				cursor.cs.addRect(x, boxBottom, width, totalH);
				cursor.cs.fill();
				cursor.cs.setNonStrokingColor(cursor.palette.accent());
				cursor.cs.addRect(x, boxBottom, 3f, totalH);
				cursor.cs.fill();

				cursor.y = boxTop - padY - 10f;
				drawStyledLines(cursor, allLines, innerX, lineH);
				cursor.y = boxBottom - 10f;
			}
			else {
				// Very long quote: render line-by-line with left accent bar on each line across page breaks
				for (List<StyledWord> line : allLines) {
					cursor.ensureSpace(lineH);
					cursor.cs.setNonStrokingColor(cursor.palette.bg());
					cursor.cs.addRect(x, cursor.y - 4f, width, lineH);
					cursor.cs.fill();
					cursor.cs.setNonStrokingColor(cursor.palette.accent());
					cursor.cs.addRect(x, cursor.y - 4f, 3f, lineH);
					cursor.cs.fill();
					drawSingleStyledLine(cursor, line, innerX);
					cursor.y -= lineH;
				}
				cursor.y -= 8f;
			}
		}
		else if (block instanceof RuleBlock) {
			cursor.y -= 6f;
			cursor.ensureSpace(12f);
			cursor.cs.setNonStrokingColor(cursor.palette.soft());
			cursor.cs.addRect(x, cursor.y, width, 1f);
			cursor.cs.fill();
			cursor.y -= 12f;
		}
		else if (block instanceof MdTableBlock table) {
			renderPdfTable(cursor, table, x, width);
		}
	}

	private void renderPdfTable(PdfCursor cursor, MdTableBlock table, float x, float width) throws IOException {
		int colCount = table.headers().size();
		for (List<List<TextRun>> row : table.rows()) {
			colCount = Math.max(colCount, row.size());
		}
		if (colCount <= 0) {
			return;
		}

		float fontSize = 9.5f;
		float lineH = 13.5f;
		float padX = 5f;
		float padY = 5f;

		// Measure natural column weights from text length (bounded so no column collapses or dominates)
		float[] weights = new float[colCount];
		for (int c = 0; c < colCount; c++) {
			weights[c] = 10f;
		}
		accumulateColWeights(weights, table.headers());
		for (List<List<TextRun>> row : table.rows()) {
			accumulateColWeights(weights, row);
		}
		float totalWeight = 0f;
		for (float w : weights) {
			totalWeight += w;
		}
		float[] colWidths = new float[colCount];
		float minColW = Math.min(54f, width / colCount);
		float remainingW = width - minColW * colCount;
		for (int c = 0; c < colCount; c++) {
			colWidths[c] = minColW + (totalWeight > 0 ? (remainingW * (weights[c] / totalWeight)) : (remainingW / colCount));
		}

		// Pre-wrap header & body rows
		List<List<List<StyledWord>>> wrappedHeader = wrapTableRow(
				table.headers(), colCount, colWidths, padX, cursor.fonts, fontSize, cursor.palette.ink(), true);
		float headerH = computeTableRowHeight(wrappedHeader, lineH, padY);

		List<List<List<List<StyledWord>>>> wrappedBody = new ArrayList<>(table.rows().size());
		List<Float> bodyHeights = new ArrayList<>(table.rows().size());
		float totalTableH = headerH;
		for (List<List<TextRun>> row : table.rows()) {
			List<List<List<StyledWord>>> wrappedRow = wrapTableRow(
					row, colCount, colWidths, padX, cursor.fonts, fontSize, cursor.palette.ink(), false);
			float rowH = computeTableRowHeight(wrappedRow, lineH, padY);
			wrappedBody.add(wrappedRow);
			bodyHeights.add(rowH);
			totalTableH += rowH;
		}

		cursor.y -= 6f;
		float usablePageH = cursor.pageSize.getHeight() - cursor.margin * 2f - 16f;
		// Page-break before table if the full table fits on one page but not in remaining space
		if (totalTableH <= usablePageH && (cursor.y - totalTableH < cursor.margin + 12f)) {
			cursor.ensureSpace(totalTableH + 4f);
		}
		else {
			float firstRowH = bodyHeights.isEmpty() ? 0f : bodyHeights.get(0);
			cursor.ensureSpace(headerH + firstRowH + 4f);
		}

		// Draw header row
		if (!table.headers().isEmpty()) {
			drawPdfTableRow(cursor, wrappedHeader, colWidths, x, width, headerH, lineH, padX, padY,
					cursor.palette.soft(), cursor.palette.accent());
		}

		// Draw body rows, repeating header if table spans onto a new page
		for (int r = 0; r < wrappedBody.size(); r++) {
			float rowH = bodyHeights.get(r);
			if (cursor.y - rowH < cursor.margin + 12f) {
				cursor.ensureSpace(rowH + headerH + 4f);
				if (!table.headers().isEmpty()) {
					drawPdfTableRow(cursor, wrappedHeader, colWidths, x, width, headerH, lineH, padX, padY,
							cursor.palette.soft(), cursor.palette.accent());
				}
			}
			Color rowBg = (r % 2 == 1) ? cursor.palette.bg() : null;
			drawPdfTableRow(cursor, wrappedBody.get(r), colWidths, x, width, rowH, lineH, padX, padY,
					rowBg, cursor.palette.soft());
		}
		cursor.y -= 8f;
	}

	private void accumulateColWeights(float[] weights, List<List<TextRun>> row) {
		if (row == null) {
			return;
		}
		for (int c = 0; c < Math.min(weights.length, row.size()); c++) {
			int len = 0;
			for (TextRun run : row.get(c)) {
				len += run.text().length();
			}
			weights[c] = Math.max(weights[c], Math.min(48f, len));
		}
	}

	private List<List<List<StyledWord>>> wrapTableRow(
			List<List<TextRun>> row,
			int colCount,
			float[] colWidths,
			float padX,
			PdfFonts fonts,
			float fontSize,
			Color color,
			boolean forceBold) throws IOException {
		List<List<List<StyledWord>>> wrappedCells = new ArrayList<>(colCount);
		for (int c = 0; c < colCount; c++) {
			List<TextRun> cellRuns = (row != null && c < row.size()) ? row.get(c) : List.of();
			if (forceBold) {
				cellRuns = cellRuns.stream()
						.map(r -> new TextRun(r.text(), true, r.italic(), r.code(), r.highlight()))
						.toList();
			}
			float innerW = Math.max(24f, colWidths[c] - padX * 2f);
			wrappedCells.add(wrapStyledRuns(cellRuns, fonts, fontSize, color, innerW));
		}
		return wrappedCells;
	}

	private float computeTableRowHeight(List<List<List<StyledWord>>> wrappedCells, float lineH, float padY) {
		int maxLines = 1;
		for (List<List<StyledWord>> cellLines : wrappedCells) {
			maxLines = Math.max(maxLines, cellLines.size());
		}
		return maxLines * lineH + padY * 2f;
	}

	private void drawPdfTableRow(
			PdfCursor cursor,
			List<List<List<StyledWord>>> wrappedCells,
			float[] colWidths,
			float x,
			float width,
			float rowH,
			float lineH,
			float padX,
			float padY,
			Color fillBg,
			Color borderBottomColor) throws IOException {
		float rowTop = cursor.y;
		float rowBottom = rowTop - rowH;

		if (fillBg != null) {
			cursor.cs.setNonStrokingColor(fillBg);
			cursor.cs.addRect(x, rowBottom, width, rowH);
			cursor.cs.fill();
		}

		// Draw cells (top-aligned)
		float colX = x;
		float savedY = cursor.y;
		for (int c = 0; c < wrappedCells.size(); c++) {
			List<List<StyledWord>> lines = wrappedCells.get(c);
			float lineY = rowTop - padY - 9.5f;
			for (List<StyledWord> line : lines) {
				cursor.y = lineY;
				drawSingleStyledLine(cursor, line, colX + padX);
				lineY -= lineH;
			}
			colX += colWidths[c];
		}
		cursor.y = savedY;

		// Horizontal rule at row bottom
		cursor.cs.setNonStrokingColor(borderBottomColor);
		cursor.cs.addRect(x, rowBottom, width, 0.8f);
		cursor.cs.fill();

		cursor.y = rowBottom;
	}

	private List<List<StyledWord>> wrapStyledRuns(
			List<TextRun> runs,
			PdfFonts fonts,
			float fontSize,
			Color defaultColor,
			float maxWidth) throws IOException {
		List<StyledWord> tokens = new ArrayList<>();
		for (TextRun run : runs) {
			String safe = toPdfSafeTextPreserveSpaces(run.text());
			if (safe.isEmpty()) {
				continue;
			}
			PDFont font = fonts.forRun(run);
			float size = run.code() ? Math.max(8.5f, fontSize - 0.8f) : fontSize;
			// Split into alternating word / space tokens so wrapping preserves spacing cleanly
			String[] parts = safe.split("(?<=\\s)|(?=\\s)");
			for (String part : parts) {
				if (part.isEmpty()) {
					continue;
				}
				tokens.add(new StyledWord(part, font, size, defaultColor, run.highlight(), run.code()));
			}
		}

		List<List<StyledWord>> lines = new ArrayList<>();
		List<StyledWord> currentLine = new ArrayList<>();
		float currentW = 0f;

		for (StyledWord token : tokens) {
			boolean isSpace = token.text().isBlank();
			if (isSpace && currentLine.isEmpty()) {
				continue;
			}
			float tokenW = token.font().getStringWidth(token.text()) / 1000f * token.fontSize();
			if (!isSpace && !currentLine.isEmpty() && currentW + tokenW > maxWidth) {
				trimTrailingSpaces(currentLine);
				lines.add(currentLine);
				currentLine = new ArrayList<>();
				currentW = 0f;
			}
			currentLine.add(token);
			currentW += tokenW;
		}
		trimTrailingSpaces(currentLine);
		if (!currentLine.isEmpty()) {
			lines.add(currentLine);
		}
		return lines;
	}

	private void trimTrailingSpaces(List<StyledWord> line) {
		while (!line.isEmpty() && line.get(line.size() - 1).text().isBlank()) {
			line.remove(line.size() - 1);
		}
	}

	private void drawStyledLines(PdfCursor cursor, List<List<StyledWord>> lines, float x, float lineH)
			throws IOException {
		for (List<StyledWord> line : lines) {
			cursor.ensureSpace(lineH);
			drawSingleStyledLine(cursor, line, x);
			cursor.y -= lineH;
		}
	}

	private void drawSingleStyledLine(PdfCursor cursor, List<StyledWord> line, float startX) throws IOException {
		float x = startX;
		for (StyledWord word : line) {
			float w = word.font().getStringWidth(word.text()) / 1000f * word.fontSize();
			if (word.highlight() && !word.text().isBlank()) {
				cursor.cs.setNonStrokingColor(cursor.palette.soft());
				cursor.cs.addRect(x - 1f, cursor.y - 2.5f, w + 2f, word.fontSize() + 3f);
				cursor.cs.fill();
			}
			else if (word.code() && !word.text().isBlank()) {
				cursor.cs.setNonStrokingColor(cursor.palette.bg());
				cursor.cs.addRect(x - 1.5f, cursor.y - 2f, w + 3f, word.fontSize() + 2.5f);
				cursor.cs.fill();
			}
			cursor.cs.beginText();
			cursor.cs.setFont(word.font(), word.fontSize());
			cursor.cs.setNonStrokingColor(word.color());
			cursor.cs.newLineAtOffset(x, cursor.y);
			cursor.cs.showText(word.text());
			cursor.cs.endText();
			x += w;
		}
	}

	private String toPdfSafeTextPreserveSpaces(String input) {
		if (input == null) {
			return "";
		}
		String normalized = input
				.replace('\u2018', '\'')
				.replace('\u2019', '\'')
				.replace('\u201C', '"')
				.replace('\u201D', '"')
				.replace('\u2014', '-')
				.replace('\u2013', '-')
				.replace("\u2026", "...")
				.replace('\u2022', '*')
				.replace('\u00A0', ' ');
		StringBuilder sb = new StringBuilder(normalized.length());
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			if ((c >= 32 && c <= 126) || (c >= 160 && c <= 255)) {
				sb.append(c);
			}
			else if (Character.isWhitespace(c)) {
				sb.append(' ');
			}
		}
		return sb.toString().replaceAll(" {2,}", " ");
	}

	public byte[] renderDocx(RelayBrand brand, List<RelaySection> sections) throws IOException {
		String title = safeTitle(brand);
		String[] cols = paletteColors(brand);
		String accentHex = cols[2].replace("#", "");
		String inkHex = cols[1].replace("#", "");
		String mutedHex = cols[4].replace("#", "");

		try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			// Cover title
			XWPFParagraph coverTitle = doc.createParagraph();
			coverTitle.setAlignment(ParagraphAlignment.LEFT);
			coverTitle.setSpacingBefore(1400);
			coverTitle.setSpacingAfter(240);
			XWPFRun titleRun = coverTitle.createRun();
			titleRun.setText(title);
			titleRun.setBold(true);
			titleRun.setFontSize(28);
			titleRun.setColor(inkHex);

			XWPFParagraph coverSub = doc.createParagraph();
			coverSub.setSpacingAfter(400);
			XWPFRun subRun = coverSub.createRun();
			subRun.setText(sections.size() + " sections");
			subRun.setFontSize(12);
			subRun.setColor(accentHex);

			int idx = 0;
			for (RelaySection section : sections) {
				idx++;
				XWPFParagraph breakPara = doc.createParagraph();
				breakPara.createRun().addBreak(BreakType.PAGE);

				XWPFParagraph eyebrow = doc.createParagraph();
				eyebrow.setSpacingAfter(60);
				XWPFRun eyebrowRun = eyebrow.createRun();
				eyebrowRun.setText("SECTION " + idx);
				eyebrowRun.setBold(true);
				eyebrowRun.setFontSize(10);
				eyebrowRun.setColor(accentHex);

				XWPFParagraph heading = doc.createParagraph();
				heading.setSpacingAfter(120);
				XWPFRun headingRun = heading.createRun();
				headingRun.setText(section.getTitle() != null ? section.getTitle() : ("Section " + idx));
				headingRun.setBold(true);
				headingRun.setFontSize(18);
				headingRun.setColor(inkHex);

				if (section.getMoment() != null && !section.getMoment().isBlank()) {
					XWPFParagraph moment = doc.createParagraph();
					moment.setSpacingAfter(180);
					XWPFRun momentRun = moment.createRun();
					momentRun.setText(section.getMoment());
					momentRun.setItalic(true);
					momentRun.setFontSize(11);
					momentRun.setColor(mutedHex);
				}

				for (MdBlock block : manuscriptMarkdown.toPdfBlocks(section.getText())) {
					appendDocxBlock(doc, block, inkHex, accentHex, mutedHex, false);
				}
			}

			doc.write(out);
			return out.toByteArray();
		}
	}

	private void appendDocxBlock(
			XWPFDocument doc,
			MdBlock block,
			String inkHex,
			String accentHex,
			String mutedHex,
			boolean inQuote) {
		if (block instanceof HeadingBlock heading) {
			XWPFParagraph p = doc.createParagraph();
			p.setSpacingBefore(220);
			p.setSpacingAfter(100);
			int size = heading.level() <= 2 ? 14 : 12;
			String color = heading.level() == 2 ? accentHex : inkHex;
			for (TextRun run : heading.runs()) {
				XWPFRun r = p.createRun();
				r.setText(run.text());
				r.setBold(true);
				r.setItalic(run.italic());
				r.setFontSize(size);
				r.setColor(color);
			}
		}
		else if (block instanceof ParagraphBlock para) {
			XWPFParagraph p = doc.createParagraph();
			p.setSpacingAfter(140);
			if (inQuote) {
				p.setIndentationLeft(420);
				p.setBorderLeft(Borders.SINGLE);
			}
			appendDocxRuns(p, para.runs(), inQuote ? mutedHex : inkHex, inQuote);
		}
		else if (block instanceof ListItemBlock item) {
			XWPFParagraph p = doc.createParagraph();
			p.setSpacingAfter(80);
			p.setIndentationLeft(360 + item.depth() * 240);
			XWPFRun marker = p.createRun();
			marker.setText(item.ordered() ? (item.index() + ". ") : "• ");
			marker.setBold(true);
			marker.setFontSize(11);
			marker.setColor(accentHex);
			appendDocxRuns(p, item.runs(), inkHex, false);
		}
		else if (block instanceof QuoteBlock quote) {
			for (MdBlock inner : quote.inner()) {
				appendDocxBlock(doc, inner, inkHex, accentHex, mutedHex, true);
			}
		}
		else if (block instanceof RuleBlock) {
			XWPFParagraph p = doc.createParagraph();
			p.setSpacingBefore(120);
			p.setSpacingAfter(120);
			p.setBorderBottom(Borders.SINGLE);
		}
		else if (block instanceof MdTableBlock tableBlock) {
			int colCount = tableBlock.headers().size();
			for (List<List<TextRun>> r : tableBlock.rows()) {
				colCount = Math.max(colCount, r.size());
			}
			if (colCount <= 0) {
				return;
			}
			int totalRows = (tableBlock.headers().isEmpty() ? 0 : 1) + tableBlock.rows().size();
			if (totalRows <= 0) {
				return;
			}
			XWPFTable xTable = doc.createTable(totalRows, colCount);
			int rowIdx = 0;
			if (!tableBlock.headers().isEmpty()) {
				XWPFTableRow headerRow = xTable.getRow(rowIdx++);
				for (int c = 0; c < colCount; c++) {
					XWPFTableCell cell = headerRow.getCell(c);
					cell.setColor("F4F1EA");
					XWPFParagraph cp = cell.getParagraphs().get(0);
					List<TextRun> cellRuns = c < tableBlock.headers().size() ? tableBlock.headers().get(c) : List.of();
					List<TextRun> boldRuns = cellRuns.stream()
							.map(r -> new TextRun(r.text(), true, r.italic(), r.code(), r.highlight()))
							.toList();
					appendDocxRuns(cp, boldRuns, inkHex, false);
				}
			}
			for (List<List<TextRun>> bodyRow : tableBlock.rows()) {
				XWPFTableRow xRow = xTable.getRow(rowIdx++);
				for (int c = 0; c < colCount; c++) {
					XWPFTableCell cell = xRow.getCell(c);
					XWPFParagraph cp = cell.getParagraphs().get(0);
					List<TextRun> cellRuns = c < bodyRow.size() ? bodyRow.get(c) : List.of();
					appendDocxRuns(cp, cellRuns, inkHex, false);
				}
			}
			// Spacer paragraph after table
			XWPFParagraph spacer = doc.createParagraph();
			spacer.setSpacingAfter(120);
		}
	}

	private void appendDocxRuns(XWPFParagraph p, List<TextRun> runs, String colorHex, boolean forceItalic) {
		for (TextRun run : runs) {
			XWPFRun r = p.createRun();
			r.setText(run.text());
			r.setBold(run.bold());
			r.setItalic(forceItalic || run.italic());
			r.setFontSize(run.code() ? 10 : 11);
			r.setColor(colorHex);
			if (run.code()) {
				r.setFontFamily("Courier New");
			}
			if (run.highlight()) {
				r.setTextHighlightColor("yellow");
			}
		}
	}

	public byte[] renderEpub(RelayBrand brand, List<RelaySection> sections) throws IOException {
		return renderEpub(brand, sections, null, Map.of());
	}

	public byte[] renderEpub(
			RelayBrand brand,
			List<RelaySection> sections,
			byte[] coverImageBytes,
			Map<Long, byte[]> sectionGraphics) throws IOException {
		String title = safeTitle(brand);
		String[] cols = paletteColors(brand);
		String bookId = "urn:uuid:" + UUID.randomUUID();
		Map<Long, byte[]> safeGraphics = sectionGraphics != null ? sectionGraphics : Map.of();
		boolean hasCoverImg = coverImageBytes != null && coverImageBytes.length > 0;

		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
				ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {

			// 1. Uncompressed mimetype entry MUST be first per EPUB spec
			byte[] mimeBytes = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
			ZipEntry mimeEntry = new ZipEntry("mimetype");
			mimeEntry.setMethod(ZipEntry.STORED);
			mimeEntry.setSize(mimeBytes.length);
			mimeEntry.setCompressedSize(mimeBytes.length);
			CRC32 crc = new CRC32();
			crc.update(mimeBytes);
			mimeEntry.setCrc(crc.getValue());
			zip.putNextEntry(mimeEntry);
			zip.write(mimeBytes);
			zip.closeEntry();

			// 2. META-INF/container.xml
			writeZipUtf8(zip, "META-INF/container.xml", """
					<?xml version="1.0" encoding="UTF-8"?>
					<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
					  <rootfiles>
					    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
					  </rootfiles>
					</container>
					""");

			// 3. OEBPS/style.css
			writeZipUtf8(zip, "OEBPS/style.css", """
					body { font-family: Georgia, serif; background: %s; color: %s; line-height: 1.65; margin: 1.5em; }
					h1, h2, h3 { color: %s; line-height: 1.25; }
					h2 { color: %s; margin-top: 1.4em; font-size: 1.25em; }
					h3 { margin-top: 1.1em; font-size: 1.08em; }
					.eyebrow { color: %s; font-family: sans-serif; font-size: 0.78em; letter-spacing: 0.08em; text-transform: uppercase; font-weight: bold; }
					.moment { color: %s; font-style: italic; margin-top: 0.25em; margin-bottom: 1.2em; }
					hr { border: 0; border-top: 1px solid %s; margin: 1.2em 0; }
					p { margin: 0 0 0.9em 0; }
					ul, ol { margin: 0 0 1em 0; padding-left: 1.4em; }
					li { margin-bottom: 0.35em; }
					blockquote { margin: 1em 0; padding: 0.75em 1em; border-left: 3px solid %s; background: %s; }
					blockquote p:last-child { margin-bottom: 0; }
					code { font-family: monospace; font-size: 0.9em; padding: 0.1em 0.3em; background: %s; border-radius: 3px; }
					mark, .highlight { background: %s; color: inherit; padding: 0.05em 0.2em; border-radius: 2px; }
					table { width: 100%%; border-collapse: collapse; margin: 0.9em 0; font-size: 0.92em; }
					th, td { border: 1px solid %s; padding: 0.45em 0.65em; text-align: left; vertical-align: top; }
					th { background: %s; font-weight: 700; }
					img.cover { max-width: 100%%; height: auto; display: block; margin: 0 auto; }
					img.section-art { max-width: 100%%; max-height: 260px; height: auto; display: block; margin: 0 auto 1.2em auto; border-radius: 6px; }
					""".formatted(cols[0], cols[1], cols[1], cols[2], cols[2], cols[4], cols[3], cols[2], cols[3], cols[3], cols[3], cols[3], cols[3]));

			if (hasCoverImg) {
				writeZipBytes(zip, "OEBPS/images/cover.png", coverImageBytes);
			}

			// 4. Cover XHTML
			String coverBody = hasCoverImg
					? "  <img class=\"cover\" src=\"images/cover.png\" alt=\"" + escapeXml(title) + "\"/>\n"
					: "  <div class=\"eyebrow\">Digital Edition</div>\n  <h1>" + escapeXml(title)
							+ "</h1>\n  <hr/>\n  <p>" + sections.size() + " sections</p>\n";
			writeZipUtf8(zip, "OEBPS/cover.xhtml", """
					<?xml version="1.0" encoding="UTF-8"?>
					<!DOCTYPE html>
					<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
					<head>
					  <title>%s</title>
					  <link rel="stylesheet" type="text/css" href="style.css"/>
					</head>
					<body>
					%s</body>
					</html>
					""".formatted(escapeXml(title), coverBody));

			// 5. Chapter XHTML files + manifest/spine/nav builders
			StringBuilder manifest = new StringBuilder();
			StringBuilder spine = new StringBuilder();
			StringBuilder navItems = new StringBuilder();

			manifest.append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
			manifest.append("    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n");
			manifest.append("    <item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n");
			if (hasCoverImg) {
				manifest.append("    <item id=\"cover-img\" href=\"images/cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>\n");
			}
			spine.append("    <itemref idref=\"cover\"/>\n");

			int idx = 0;
			for (RelaySection section : sections) {
				idx++;
				String itemId = "chap-" + idx;
				String href = "chapter-" + idx + ".xhtml";
				String secTitle = section.getTitle() != null ? section.getTitle() : ("Section " + idx);

				manifest.append("    <item id=\"").append(itemId)
						.append("\" href=\"").append(href)
						.append("\" media-type=\"application/xhtml+xml\"/>\n");
				spine.append("    <itemref idref=\"").append(itemId).append("\"/>\n");
				navItems.append("      <li><a href=\"").append(href).append("\">")
						.append(escapeXml(secTitle)).append("</a></li>\n");

				String imgBlock = "";
				byte[] secImg = section.getId() != null ? safeGraphics.get(section.getId()) : null;
				if (secImg != null && secImg.length > 0) {
					String imgHref = "images/section-" + idx + ".png";
					writeZipBytes(zip, "OEBPS/" + imgHref, secImg);
					manifest.append("    <item id=\"sec-img-").append(idx)
							.append("\" href=\"").append(imgHref)
							.append("\" media-type=\"image/png\"/>\n");
					imgBlock = "  <img class=\"section-art\" src=\"" + imgHref + "\" alt=\"" + escapeXml(secTitle) + "\"/>\n";
				}

				String bodyHtml = "<div class=\"md-body\">\n"
						+ manuscriptMarkdown.toHtmlFragment(section.getText())
						+ "</div>\n";
				String momentBlock = (section.getMoment() != null && !section.getMoment().isBlank())
						? "  <p class=\"moment\">" + escapeXml(section.getMoment()) + "</p>\n"
						: "";

				writeZipUtf8(zip, "OEBPS/" + href, """
						<?xml version="1.0" encoding="UTF-8"?>
						<!DOCTYPE html>
						<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
						<head>
						  <title>%s</title>
						  <link rel="stylesheet" type="text/css" href="style.css"/>
						</head>
						<body>
						  <div class="eyebrow">Section %d</div>
						  <h1>%s</h1>
						%s  <hr/>
						%s%s</body>
						</html>
						""".formatted(escapeXml(secTitle), idx, escapeXml(secTitle), momentBlock, imgBlock, bodyHtml));
			}

			// 6. OEBPS/nav.xhtml
			writeZipUtf8(zip, "OEBPS/nav.xhtml", """
					<?xml version="1.0" encoding="UTF-8"?>
					<!DOCTYPE html>
					<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en">
					<head>
					  <title>Contents</title>
					  <link rel="stylesheet" type="text/css" href="style.css"/>
					</head>
					<body>
					  <nav epub:type="toc" id="toc">
					    <h1>Contents</h1>
					    <ol>
					%s    </ol>
					  </nav>
					</body>
					</html>
					""".formatted(navItems));

			// 7. OEBPS/content.opf
			writeZipUtf8(zip, "OEBPS/content.opf", """
					<?xml version="1.0" encoding="UTF-8"?>
					<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
					  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
					    <dc:identifier id="bookid">%s</dc:identifier>
					    <dc:title>%s</dc:title>
					    <dc:language>en</dc:language>
					  </metadata>
					  <manifest>
					%s  </manifest>
					  <spine>
					%s  </spine>
					</package>
					""".formatted(bookId, escapeXml(title), manifest, spine));

			zip.finish();
			return out.toByteArray();
		}
	}

	public byte[] renderMockupsZip(RelayBrand brand, List<RelaySection> sections) throws IOException {
		return renderMockupsZip(brand, sections, List.of(), Map.of());
	}

	public byte[] renderMockupsZip(
			RelayBrand brand,
			List<RelaySection> sections,
			List<byte[]> coverImages,
			Map<Long, byte[]> sectionGraphics) throws IOException {
		String title = safeTitle(brand);
		String[] cols = paletteColors(brand);
		List<byte[]> safeCovers = coverImages != null ? coverImages : List.of();
		Map<Long, byte[]> safeGraphics = sectionGraphics != null ? sectionGraphics : Map.of();

		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
				ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			for (int variant = 0; variant < 3; variant++) {
				byte[] png = variant < safeCovers.size() ? safeCovers.get(variant) : null;
				if (png != null && png.length > 0) {
					writeZipBytes(zip, "cover-variant-" + variant + ".png", png);
				}
				else {
					writeZipUtf8(zip, "cover-variant-" + variant + ".svg", buildCoverSvg(title, cols, variant));
				}
			}
			for (int i = 0; i < 3; i++) {
				RelaySection sec = i < sections.size() ? sections.get(i) : null;
				writeZipUtf8(zip, "interior-page-" + (i + 1) + ".svg", buildInteriorSvg(cols, sec, i + 1));
				if (sec != null && sec.getId() != null) {
					byte[] secImg = safeGraphics.get(sec.getId());
					if (secImg != null && secImg.length > 0) {
						writeZipBytes(zip, "interior-graphic-" + (i + 1) + ".png", secImg);
					}
				}
			}
			zip.finish();
			return out.toByteArray();
		}
	}

	public byte[] renderHtmlPackZip(RelayBrand brand, List<RelaySection> sections) throws IOException {
		return renderHtmlPackZip(brand, sections, null, Map.of());
	}

	public byte[] renderHtmlPackZip(
			RelayBrand brand,
			List<RelaySection> sections,
			byte[] coverImageBytes,
			Map<Long, byte[]> sectionGraphics) throws IOException {
		String title = safeTitle(brand);
		String[] cols = paletteColors(brand);
		Map<Long, byte[]> safeGraphics = sectionGraphics != null ? sectionGraphics : Map.of();
		boolean hasCoverImg = coverImageBytes != null && coverImageBytes.length > 0;

		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
				ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {

			if (hasCoverImg) {
				writeZipBytes(zip, "assets/cover.png", coverImageBytes);
			}

			StringBuilder toc = new StringBuilder();
			StringBuilder allSections = new StringBuilder();
			int idx = 0;
			for (RelaySection section : sections) {
				idx++;
				String secTitle = section.getTitle() != null ? section.getTitle() : ("Section " + idx);
				String filename = "section-" + idx + ".html";
				toc.append("<li><a href=\"").append(filename).append("\">")
						.append(escapeXml(secTitle)).append("</a></li>\n");

				String imgHtml = "";
				byte[] secImg = section.getId() != null ? safeGraphics.get(section.getId()) : null;
				if (secImg != null && secImg.length > 0) {
					String assetPath = "assets/section-" + idx + ".png";
					writeZipBytes(zip, assetPath, secImg);
					imgHtml = "<img src=\"" + assetPath + "\" alt=\"" + escapeXml(secTitle)
							+ "\" style=\"max-width:100%;max-height:320px;border-radius:10px;display:block;margin:16px auto 20px;\"/>\n";
				}

				String bodyHtml = "<div class=\"md-body\">\n"
						+ manuscriptMarkdown.toHtmlFragment(section.getText())
						+ "</div>\n";
				String momentHtml = (section.getMoment() != null && !section.getMoment().isBlank())
						? "<p class=\"moment\">" + escapeXml(section.getMoment()) + "</p>\n"
						: "";

				allSections.append("<section class=\"card\"><div class=\"eyebrow\">Section ").append(idx)
						.append("</div><h2>").append(escapeXml(secTitle)).append("</h2>")
						.append(momentHtml).append(imgHtml).append(bodyHtml).append("</section>\n");

				writeZipUtf8(zip, filename, """
						<!DOCTYPE html>
						<html lang="en">
						<head>
						  <meta charset="UTF-8"/>
						  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
						  <title>%s — %s</title>
						  <style>
						    body { font-family: system-ui, -apple-system, sans-serif; background: %s; color: %s; margin: 0; padding: 48px 24px; }
						    main { max-width: 720px; margin: 0 auto; background: #ffffff; border: 1px solid %s; border-radius: 14px; padding: 40px; }
						    .eyebrow { color: %s; font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; }
						    h1 { margin: 8px 0 6px; font-size: 28px; }
						    .md-body h2 { color: %s; font-size: 20px; margin: 24px 0 10px; }
						    .md-body h3 { font-size: 16px; margin: 18px 0 8px; }
						    .md-body ul, .md-body ol { padding-left: 22px; margin: 0 0 14px; line-height: 1.7; }
						    .md-body blockquote { margin: 16px 0; padding: 12px 16px; border-left: 3px solid %s; background: %s; border-radius: 0 8px 8px 0; }
						    .md-body hr { border: 0; border-top: 1px solid %s; margin: 20px 0; }
						    .md-body code { font-family: ui-monospace, monospace; font-size: 0.9em; background: %s; padding: 2px 5px; border-radius: 4px; }
						    .md-body mark, .md-body .highlight { background: %s; padding: 1px 4px; border-radius: 3px; }
						    .md-body table { width: 100%%; border-collapse: collapse; margin: 16px 0; font-size: 14px; }
						    .md-body th, .md-body td { border: 1px solid %s; padding: 8px 12px; text-align: left; vertical-align: top; }
						    .md-body th { background: %s; font-weight: 700; }
						    .moment { color: %s; font-style: italic; margin: 0 0 24px; }
						    p { line-height: 1.7; margin: 0 0 14px; }
						    a { color: %s; }
						  </style>
						</head>
						<body>
						  <main>
						    <p><a href="index.html">&larr; Back to Contents</a></p>
						    <div class="eyebrow">Section %d</div>
						    <h1>%s</h1>
						    %s
						    %s
						    %s
						  </main>
						</body>
						</html>
						""".formatted(
						escapeXml(secTitle), escapeXml(title),
						cols[0], cols[1], cols[3], cols[2],
						cols[2], cols[2], cols[0], cols[3], cols[0], cols[3], cols[3], cols[0],
						cols[4], cols[2],
						idx, escapeXml(secTitle), momentHtml, imgHtml, bodyHtml));
			}

			String coverImgHtml = hasCoverImg
					? "<img src=\"assets/cover.png\" alt=\"" + escapeXml(title)
							+ "\" style=\"max-width:340px;width:100%;border-radius:12px;display:block;margin-bottom:20px;\"/>"
					: "";

			writeZipUtf8(zip, "index.html", """
					<!DOCTYPE html>
					<html lang="en">
					<head>
					  <meta charset="UTF-8"/>
					  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
					  <title>%s</title>
					  <style>
					    body { font-family: system-ui, -apple-system, sans-serif; background: %s; color: %s; margin: 0; padding: 48px 24px; }
					    main { max-width: 780px; margin: 0 auto; display: grid; gap: 24px; }
					    .cover { background: %s; border: 2px solid %s; border-radius: 16px; padding: 48px 40px; }
					    .eyebrow { color: %s; font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; }
					    h1 { margin: 12px 0; font-size: 36px; line-height: 1.15; }
					    .card { background: #ffffff; border: 1px solid %s; border-radius: 14px; padding: 32px; }
					    .md-body h2 { color: %s; font-size: 20px; margin: 22px 0 10px; }
					    .md-body h3 { font-size: 16px; margin: 16px 0 8px; }
					    .md-body ul, .md-body ol { padding-left: 22px; margin: 0 0 14px; line-height: 1.7; }
					    .md-body blockquote { margin: 16px 0; padding: 12px 16px; border-left: 3px solid %s; background: %s; border-radius: 0 8px 8px 0; }
					    .md-body hr { border: 0; border-top: 1px solid %s; margin: 20px 0; }
					    .md-body code { font-family: ui-monospace, monospace; font-size: 0.9em; background: %s; padding: 2px 5px; border-radius: 4px; }
					    .md-body mark, .md-body .highlight { background: %s; padding: 1px 4px; border-radius: 3px; }
					    .md-body table { width: 100%%; border-collapse: collapse; margin: 16px 0; font-size: 14px; }
					    .md-body th, .md-body td { border: 1px solid %s; padding: 8px 12px; text-align: left; vertical-align: top; }
					    .md-body th { background: %s; font-weight: 700; }
					    .moment { color: %s; font-style: italic; margin-top: 4px; }
					    ol { padding-left: 20px; line-height: 1.8; }
					    a { color: %s; text-decoration: none; font-weight: 600; }
					    p { line-height: 1.7; }
					  </style>
					</head>
					<body>
					  <main>
					    <header class="cover">
					      %s
					      <div class="eyebrow">Canva-Editable HTML Pack</div>
					      <h1>%s</h1>
					      <p>%d sections</p>
					    </header>
					    <nav class="card">
					      <h2>Contents</h2>
					      <ol>
					%s      </ol>
					    </nav>
					%s  </main>
					</body>
					</html>
					""".formatted(
					escapeXml(title),
					cols[0], cols[1], cols[0], cols[2], cols[2], cols[3],
					cols[2], cols[2], cols[0], cols[3], cols[0], cols[3], cols[3], cols[0],
					cols[4], cols[2],
					coverImgHtml, escapeXml(title), sections.size(), toc, allSections));

			zip.finish();
			return out.toByteArray();
		}
	}

	private String buildCoverSvg(String title, String[] cols, int variant) {
		List<String> lines = wrapByChars(title, 18);
		StringBuilder titleSvg = new StringBuilder();
		int y = variant == 1 ? 300 : 220;
		String textFill = variant == 2 ? cols[0] : cols[1];
		for (int i = 0; i < Math.min(lines.size(), 4); i++) {
			titleSvg.append("<text x=\"64\" y=\"").append(y + i * 52)
					.append("\" font-family=\"Georgia, serif\" font-size=\"40\" font-weight=\"700\" fill=\"")
					.append(textFill).append("\">")
					.append(escapeXml(lines.get(i))).append("</text>\n");
		}
		String bgFill = variant == 2 ? cols[1] : cols[0];
		String decor = switch (variant) {
			case 1 -> "<rect x=\"0\" y=\"0\" width=\"600\" height=\"220\" fill=\"" + cols[3] + "\"/>"
					+ "<circle cx=\"480\" cy=\"110\" r=\"72\" fill=\"" + cols[2] + "\" opacity=\"0.85\"/>";
			case 2 -> "<rect x=\"0\" y=\"0\" width=\"600\" height=\"28\" fill=\"" + cols[2] + "\"/>"
					+ "<rect x=\"64\" y=\"620\" width=\"120\" height=\"8\" rx=\"4\" fill=\"" + cols[2] + "\"/>";
			default -> "<rect x=\"0\" y=\"0\" width=\"24\" height=\"800\" fill=\"" + cols[2] + "\"/>"
					+ "<rect x=\"64\" y=\"640\" width=\"472\" height=\"84\" rx=\"10\" fill=\"" + cols[3] + "\"/>";
		};
		return """
				<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 800" width="600" height="800">
				  <rect width="600" height="800" fill="%s"/>
				  %s
				  %s
				</svg>
				""".formatted(bgFill, decor, titleSvg);
	}

	private String buildInteriorSvg(String[] cols, RelaySection section, int pageNum) {
		String heading = section != null && section.getTitle() != null
				? section.getTitle()
				: ("Section " + pageNum);
		String snippet = section != null && section.getText() != null
				? section.getText().replaceAll("\\s+", " ").trim()
				: "Structured interior page layout ready for export.";
		if (snippet.length() > 260) {
			snippet = snippet.substring(0, 260) + "...";
		}
		List<String> headLines = wrapByChars(heading, 26);
		List<String> bodyLines = wrapByChars(snippet, 46);

		StringBuilder sb = new StringBuilder();
		sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 600 800\" width=\"600\" height=\"800\">\n");
		sb.append("  <rect width=\"600\" height=\"800\" fill=\"").append(cols[0]).append("\"/>\n");
		sb.append("  <rect x=\"48\" y=\"48\" width=\"504\" height=\"704\" rx=\"8\" fill=\"#ffffff\" stroke=\"")
				.append(cols[3]).append("\" stroke-width=\"2\"/>\n");
		sb.append("  <text x=\"80\" y=\"104\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"700\" fill=\"")
				.append(cols[2]).append("\">SECTION ").append(pageNum).append("</text>\n");
		int y = 148;
		for (int i = 0; i < Math.min(headLines.size(), 2); i++) {
			sb.append("  <text x=\"80\" y=\"").append(y)
					.append("\" font-family=\"Georgia, serif\" font-size=\"26\" font-weight=\"700\" fill=\"")
					.append(cols[1]).append("\">").append(escapeXml(headLines.get(i))).append("</text>\n");
			y += 34;
		}
		sb.append("  <rect x=\"80\" y=\"").append(y + 4).append("\" width=\"440\" height=\"2\" fill=\"")
				.append(cols[3]).append("\"/>\n");
		y += 38;
		for (int i = 0; i < Math.min(bodyLines.size(), 8); i++) {
			sb.append("  <text x=\"80\" y=\"").append(y)
					.append("\" font-family=\"sans-serif\" font-size=\"15\" fill=\"")
					.append(cols[4]).append("\">").append(escapeXml(bodyLines.get(i))).append("</text>\n");
			y += 26;
		}
		sb.append("  <text x=\"80\" y=\"720\" font-family=\"sans-serif\" font-size=\"12\" fill=\"")
				.append(cols[4]).append("\">Page ").append(pageNum).append("</text>\n");
		sb.append("</svg>\n");
		return sb.toString();
	}

	private void writeZipUtf8(ZipOutputStream zip, String name, String content) throws IOException {
		writeZipBytes(zip, name, content.getBytes(StandardCharsets.UTF_8));
	}

	private void writeZipBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
	}

	private String safeTitle(RelayBrand brand) {
		if (brand != null && brand.getTitle() != null && !brand.getTitle().isBlank()) {
			return brand.getTitle().trim();
		}
		return "Untitled Product";
	}

	private String[] paletteColors(RelayBrand brand) {
		int idx = (brand != null && brand.getPaletteIndex() != null)
				? Math.max(0, Math.min(PALETTES.length - 1, brand.getPaletteIndex()))
				: 0;
		return PALETTES[idx];
	}

	private Color parseHex(String hex) {
		String clean = hex.startsWith("#") ? hex.substring(1) : hex;
		return new Color(Integer.parseInt(clean, 16));
	}

	private List<String> splitParagraphs(String text) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return out;
		}
		String[] raw = text.replace("\r\n", "\n").split("\\n\\s*\\n");
		for (String part : raw) {
			String cleaned = part.replace('\n', ' ').replaceAll("\\s+", " ").trim();
			if (!cleaned.isEmpty()) {
				out.add(cleaned);
			}
		}
		return out;
	}

	private List<String> wrapText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return lines;
		}
		String[] words = text.split("\\s+");
		StringBuilder current = new StringBuilder();
		for (String word : words) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			float width = font.getStringWidth(candidate) / 1000f * fontSize;
			if (width <= maxWidth || current.isEmpty()) {
				current.setLength(0);
				current.append(candidate);
			}
			else {
				lines.add(current.toString());
				current.setLength(0);
				current.append(word);
			}
		}
		if (!current.isEmpty()) {
			lines.add(current.toString());
		}
		return lines;
	}

	private List<String> wrapByChars(String text, int maxChars) {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return lines;
		}
		String[] words = text.split("\\s+");
		StringBuilder current = new StringBuilder();
		for (String word : words) {
			if (current.length() + word.length() + (current.isEmpty() ? 0 : 1) <= maxChars || current.isEmpty()) {
				if (!current.isEmpty()) {
					current.append(' ');
				}
				current.append(word);
			}
			else {
				lines.add(current.toString());
				current.setLength(0);
				current.append(word);
			}
		}
		if (!current.isEmpty()) {
			lines.add(current.toString());
		}
		return lines;
	}

	private String toPdfSafeText(String input) {
		if (input == null) {
			return "";
		}
		String normalized = input
				.replace('\u2018', '\'')
				.replace('\u2019', '\'')
				.replace('\u201C', '"')
				.replace('\u201D', '"')
				.replace('\u2014', '-')
				.replace('\u2013', '-')
				.replace("\u2026", "...")
				.replace('\u2022', '*')
				.replace('\u00A0', ' ');
		StringBuilder sb = new StringBuilder(normalized.length());
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			if (c >= 32 && c <= 126) {
				sb.append(c);
			}
			else if (c >= 160 && c <= 255) {
				sb.append(c);
			}
			else if (Character.isWhitespace(c)) {
				sb.append(' ');
			}
		}
		return sb.toString().replaceAll("\\s+", " ").trim();
	}

	private String escapeXml(String input) {
		if (input == null) {
			return "";
		}
		return input
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}
}

