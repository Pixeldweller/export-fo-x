/*
 * export-fo-x sample application.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pixeldweller.export.fox.sample;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under {@code export-fo-x:} in application.yml. */
@ConfigurationProperties(prefix = "export-fo-x")
public class ConversionProperties {

	/**
	 * Directories of TTF/OTF files to register before converting.
	 *
	 * <p>Set this in any deployment that is not the Windows machine the templates were
	 * written on. A font docx4j cannot find is substituted, and because line breaking
	 * follows the substitute's metrics the document reflows -- often onto a different
	 * number of pages.
	 */
	private List<String> fontDirectories = new ArrayList<>();

	/** Keep the paragraph that carries a section break in its own section. */
	private boolean normalizeSectionBreaks = true;

	/** Rescale Word's "multiple" line spacing to the font's real line height. */
	private boolean normalizeLineSpacing = true;

	/** Resolve a leading w:tab in a hanging-indent paragraph into a first-line indent. */
	private boolean normalizeLeadingTabs = true;

	/** Where to write the intermediate XSL-FO, for debugging. Empty = don't. */
	private String foDumpDirectory;

	public List<String> getFontDirectories() {
		return fontDirectories;
	}

	public void setFontDirectories(List<String> fontDirectories) {
		this.fontDirectories = fontDirectories;
	}

	public boolean isNormalizeSectionBreaks() {
		return normalizeSectionBreaks;
	}

	public void setNormalizeSectionBreaks(boolean normalizeSectionBreaks) {
		this.normalizeSectionBreaks = normalizeSectionBreaks;
	}

	public boolean isNormalizeLineSpacing() {
		return normalizeLineSpacing;
	}

	public void setNormalizeLineSpacing(boolean normalizeLineSpacing) {
		this.normalizeLineSpacing = normalizeLineSpacing;
	}

	public boolean isNormalizeLeadingTabs() {
		return normalizeLeadingTabs;
	}

	public void setNormalizeLeadingTabs(boolean normalizeLeadingTabs) {
		this.normalizeLeadingTabs = normalizeLeadingTabs;
	}

	public String getFoDumpDirectory() {
		return foDumpDirectory;
	}

	public void setFoDumpDirectory(String foDumpDirectory) {
		this.foDumpDirectory = foDumpDirectory;
	}
}
