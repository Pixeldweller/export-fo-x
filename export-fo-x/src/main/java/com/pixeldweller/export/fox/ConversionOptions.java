/*
 * export-fo-x -- Weiterentwicklung von docx4j-export-fo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pixeldweller.export.fox;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Settings for {@link DocxToPdfConverter}. All of them have a sensible default;
 * the one worth setting in a server deployment is {@link #addFontDirectory(File)}.
 */
public class ConversionOptions {

	private final List<File> fontDirectories = new ArrayList<>();
	private boolean normalizeSectionBreaks = true;
	private boolean normalizeLineSpacing = true;
	private boolean normalizeLeadingTabs = true;
	private File foDumpFile;
	private String imageDirPath;

	/**
	 * A directory of TTF/OTF files to register with docx4j before converting.
	 *
	 * <p>Worth doing on any machine that is not the Windows box the document was authored
	 * on: an unavailable font is silently substituted, and since line breaking follows the
	 * substitute's metrics, the whole document reflows. Ship the fonts the templates
	 * actually name (here: Open Sans, Arial, Times New Roman) next to the application.
	 */
	public ConversionOptions addFontDirectory(File directory) {
		if (directory != null) {
			fontDirectories.add(directory);
		}
		return this;
	}

	public List<File> getFontDirectories() {
		return Collections.unmodifiableList(fontDirectories);
	}

	/**
	 * Whether to move a {@code w:sectPr} onto a paragraph of its own first, so the
	 * paragraph that carried it stays in its own section. Defaults to true; see
	 * {@link org.docx4j.convert.out.fo.x.SectPrParagraphNormalizer} for why.
	 */
	public ConversionOptions setNormalizeSectionBreaks(boolean normalizeSectionBreaks) {
		this.normalizeSectionBreaks = normalizeSectionBreaks;
		return this;
	}

	public boolean isNormalizeSectionBreaks() {
		return normalizeSectionBreaks;
	}

	/**
	 * Whether to rescale Word's "multiple" line spacing to the font's real line height
	 * before converting. Defaults to true; see
	 * {@link org.docx4j.convert.out.fo.x.LineSpacingNormalizer}.
	 *
	 * <p>Only paragraphs whose font is actually installed are affected, so on a machine
	 * without the document's fonts this quietly does nothing -- one more reason to
	 * {@link #addFontDirectory(File) supply them}.
	 */
	public ConversionOptions setNormalizeLineSpacing(boolean normalizeLineSpacing) {
		this.normalizeLineSpacing = normalizeLineSpacing;
		return this;
	}

	public boolean isNormalizeLineSpacing() {
		return normalizeLineSpacing;
	}

	/**
	 * Whether to resolve a leading {@code w:tab} in a paragraph with a hanging indent
	 * into the first-line indent it actually produces. Defaults to true; see
	 * {@link org.docx4j.convert.out.fo.x.LeadingTabNormalizer}.
	 *
	 * <p>Without it the first line of such a paragraph hangs out to the left, because
	 * docx4j applies the hanging indent but renders the tab that compensates it as three
	 * fixed spaces.
	 */
	public ConversionOptions setNormalizeLeadingTabs(boolean normalizeLeadingTabs) {
		this.normalizeLeadingTabs = normalizeLeadingTabs;
		return this;
	}

	public boolean isNormalizeLeadingTabs() {
		return normalizeLeadingTabs;
	}

	/** Write the intermediate XSL-FO here as well. Useful when a layout looks wrong. */
	public ConversionOptions setFoDumpFile(File foDumpFile) {
		this.foDumpFile = foDumpFile;
		return this;
	}

	public File getFoDumpFile() {
		return foDumpFile;
	}

	/**
	 * Where extracted images are written. Defaults to {@code java.io.tmpdir}.
	 * Every file is prefixed with a per-conversion UUID, so concurrent conversions
	 * do not collide.
	 */
	public ConversionOptions setImageDirPath(String imageDirPath) {
		this.imageDirPath = imageDirPath;
		return this;
	}

	public String getImageDirPath() {
		return imageDirPath;
	}
}
