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

	/**
	 * Classpath directory holding fonts shipped inside the deployable, eg
	 * {@code fonts-app}. They are copied out to {@link #getFontExtractDirectory()} at
	 * startup and that directory is registered.
	 *
	 * <p>Deliberately not {@code fonts}: docx4j scans that name itself but takes only the
	 * first match on the classpath, so an application directory called {@code fonts}
	 * would hide the one in docx4j-export-fo-fonts-crosextra.
	 *
	 * <p>Empty disables the extraction.
	 */
	private String fontResourcePath = "fonts-app";

	/**
	 * Where extracted fonts are written. Empty means a directory under
	 * {@code java.io.tmpdir} -- which under Tomcat is {@code $CATALINA_BASE/temp}, not
	 * {@code /tmp}.
	 */
	private String fontExtractDirectory;

	/**
	 * Where images pulled out of a document are written during conversion. Empty means
	 * {@code java.io.tmpdir}. Set it when that is not writable.
	 */
	private String imageDirPath;

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

	public String getFontResourcePath() {
		return fontResourcePath;
	}

	public void setFontResourcePath(String fontResourcePath) {
		this.fontResourcePath = fontResourcePath;
	}

	public String getFontExtractDirectory() {
		return fontExtractDirectory;
	}

	public void setFontExtractDirectory(String fontExtractDirectory) {
		this.fontExtractDirectory = fontExtractDirectory;
	}

	public String getImageDirPath() {
		return imageDirPath;
	}

	public void setImageDirPath(String imageDirPath) {
		this.imageDirPath = imageDirPath;
	}

	public String getFoDumpDirectory() {
		return foDumpDirectory;
	}

	public void setFoDumpDirectory(String foDumpDirectory) {
		this.foDumpDirectory = foDumpDirectory;
	}
}
