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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Copies font files shipped inside the deployable out to a real directory.
 *
 * <p>Two reasons this is needed rather than reading them where they lie:
 *
 * <ul>
 * <li>{@code ConversionOptions.addFontDirectory} lists a directory. Inside a packaged war
 *     -- or in {@code BOOT-INF/lib} of a runnable jar -- there is no directory to list.</li>
 * <li>Even docx4j's own {@code PhysicalFonts.discoverJarFonts()}, which does read fonts
 *     from a jar, registers them under a {@code jar:} URI. export-fo-x measures a font's
 *     real line height to reproduce Word's "multiple" line spacing, and that measurement
 *     needs a file it can open -- a {@code jar:} URI makes it give up and fall back to
 *     docx4j's incorrect spacing, silently. Extracting keeps every feature working.</li>
 * </ul>
 *
 * <p>The scan uses Spring's {@code classpath*:} resolver, which unlike
 * {@code ClassLoader.getResource} finds <em>every</em> match on the classpath and also
 * copes with Tomcat's {@code war:} protocol for an unexpanded war. docx4j's own
 * discovery takes only the first hit, so a {@code fonts/} directory in the application
 * hides the one in {@code docx4j-export-fo-fonts-crosextra} (verified) -- which is why
 * the default resource path here is not {@code fonts}.
 */
public final class FontResourceExtractor {

	private static final Logger log = LoggerFactory.getLogger(FontResourceExtractor.class);

	private static final String[] FONT_SUFFIXES = {".ttf", ".otf", ".ttc"};

	private FontResourceExtractor() {
	}

	/**
	 * Extracts every font under {@code resourcePath} on the classpath into
	 * {@code targetDirectory}.
	 *
	 * @return the target directory, or null when there was nothing to extract
	 */
	public static File extract(String resourcePath, File targetDirectory) {

		String path = trim(resourcePath);
		if (path.isEmpty()) {
			// An empty path would turn the pattern into classpath*:/**, ie every file on
			// the classpath -- including the fonts inside docx4j's own jars.
			log.debug("No font resource path configured");
			return null;
		}
		String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + path + "/**";

		Resource[] resources;
		try {
			resources = new PathMatchingResourcePatternResolver().getResources(pattern);
		} catch (IOException e) {
			log.warn("Could not scan {} for fonts: {}", pattern, e.toString());
			return null;
		}

		int extracted = 0;
		int reused = 0;
		for (Resource resource : resources) {
			String name = resource.getFilename();
			if (name == null || !isFont(name)) {
				continue;
			}
			try {
				if (copy(resource, targetDirectory.toPath().resolve(name))) {
					extracted++;
				} else {
					reused++;
				}
			} catch (IOException e) {
				log.warn("Could not extract font {}: {}", name, e.toString());
			}
		}

		if (extracted + reused == 0) {
			log.debug("No fonts found under classpath:{}", path);
			return null;
		}
		log.info("Fonts from classpath:{} available in {} ({} extracted, {} already there)",
				path, targetDirectory, extracted, reused);
		return targetDirectory;
	}

	/**
	 * @return true when the file was written, false when an identical one was already
	 *         there -- restarts are common and a font never changes without its size
	 *         changing too
	 */
	private static boolean copy(Resource resource, Path target) throws IOException {

		long size = resource.contentLength();
		if (Files.isRegularFile(target) && size > 0 && Files.size(target) == size) {
			return false;
		}
		Files.createDirectories(target.getParent());
		try (InputStream in = resource.getInputStream()) {
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return true;
	}

	private static boolean isFont(String filename) {
		String lower = filename.toLowerCase(Locale.ROOT);
		for (String suffix : FONT_SUFFIXES) {
			if (lower.endsWith(suffix)) {
				return true;
			}
		}
		return false;
	}

	private static String trim(String path) {
		String p = path == null ? "" : path.trim();
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		while (p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		return p;
	}
}
