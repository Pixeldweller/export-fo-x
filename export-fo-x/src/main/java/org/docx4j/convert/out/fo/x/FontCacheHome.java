/*
 * export-fo-x -- Weiterentwicklung von docx4j-export-fo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.docx4j.convert.out.fo.x;

import java.io.File;
import java.util.Locale;

import org.docx4j.Docx4jProperties;
import org.docx4j.openpackaging.parts.AbstractFontPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes sure docx4j has somewhere to put its font cache before it goes looking.
 *
 * <p>docx4j derives the cache location from {@code user.home} alone
 * ({@code org.docx4j.fonts.fop.fonts.FontCache#getDefaultCacheFile}):
 *
 * <pre>
 * File dir = toDirectory(System.getProperty("user.home"));   // null unless it EXISTS
 * if (dir != null) {
 *     File d = new File(dir, ".docx4j");
 *     if (!d.exists()) writable = d.mkdir();
 *     if (!writable) dir = toDirectory(System.getProperty("java.io.tmpdir"));
 *     return new File(new File(dir, ".docx4j"), "fop-fonts.cache");
 * }
 * return new File(".docx4j");                                // relative
 * </pre>
 *
 * <p>The temp-directory fallback only applies when {@code user.home} <em>exists</em> but
 * is not writable. When it does not exist at all -- a container running as an arbitrary
 * UID with no passwd entry, where the JDK sets {@code user.home} to {@code "?"} -- the
 * first branch is skipped entirely and the method returns the <b>relative</b> path
 * {@code .docx4j}, resolved against whatever the process working directory happens to be.
 *
 * <p>That is not merely untidy. {@code FontCache.saveTo} does not create parent
 * directories and reports failure strictly, and
 * {@code IdentityPlusMapper}'s static initialiser calls {@code fontCache.save()}
 * unconditionally and rethrows anything it catches. On a read-only working directory the
 * result is an {@link ExceptionInInitializerError}, after which that class stays
 * unusable for the life of the classloader.
 *
 * <p>There is no separate property to point at: {@code user.home} is the only input, and
 * it feeds four different writers -- docx4j's FOP font cache, Apache FOP's own font cache
 * in {@code .fop}, docx4j's directory for fonts embedded in a document, and by extension
 * anything else deriving from it. So when it does not resolve, this points it at the first
 * writable directory it can find.
 *
 * <p>Candidates, in order: a directory the caller nominates (in a servlet container, pass
 * {@code jakarta.servlet.context.tempdir} -- the spec guarantees it is writable), then
 * {@code java.io.tmpdir}, then {@code /tmp}. The last one matters because under Tomcat
 * {@code java.io.tmpdir} is {@code $CATALINA_BASE/temp}, which on a locked-down host may
 * be read-only while {@code /tmp} is not.
 *
 * <p><b>Ordering matters.</b> The cache is written from a static initialiser, so this has
 * to run before {@code IdentityPlusMapper} is first loaded.
 * {@link com.pixeldweller.export.fox.DocxToPdfConverter} calls it for you. Code that drives
 * {@code Docx4J.toPDF} directly should call {@link #ensureUsable()} during startup.
 *
 * <p>Calling it more than once is fine: once {@code user.home} resolves, every further
 * call is a no-op, so an early attempt with no nominated directory can be followed by a
 * later one that has a better candidate to offer.
 *
 * <h2>Embedded fonts</h2>
 *
 * <p>The directory docx4j unpacks fonts embedded in a document into has no fallback at all
 * ({@code org.docx4j.openpackaging.parts.AbstractFontPart}, static initialiser):
 *
 * <pre>
 * tmpFontDir = property ObfuscatedFontPart.tmpFontDir, else
 *              new File(user.home, ".docx4all/temporary embedded fonts");
 * tmpFontDir.mkdir();                         // result ignored; mkdir, not mkdirs
 * </pre>
 *
 * <p>and after <em>every</em> conversion of a document that has a font table,
 * {@code AbstractExporter.export} calls {@code ObfuscatedFontPart.deleteEmbeddedFontTempFiles},
 * which does {@code getTmpFontDir().listFiles().length} without a null check. If the
 * directory does not exist, the conversion fails with a {@link NullPointerException} --
 * after the PDF has already been written. That happens when
 * <ul>
 * <li>{@code user.home} exists but is not writable -- the usual case for a service account
 *     on RHEL, e.g. {@code tomcat} with home {@code /usr/share/tomcat}, owned by root;</li>
 * <li>the property names a directory whose parent does not exist, since docx4j only
 *     calls {@code mkdir};</li>
 * <li>the directory is removed while the application runs, e.g. by
 *     {@code systemd-tmpfiles} cleaning up {@code /tmp}.</li>
 * </ul>
 * So {@link #ensureUsable(File)} also checks that directory, and points the docx4j
 * property somewhere writable if it is not; and {@link #ensureEmbeddedFontDirectory()}
 * recreates it if it has gone missing, which {@code DocxToPdfConverter} does before every
 * conversion.
 */
public final class FontCacheHome {

	private static final Logger log = LoggerFactory.getLogger(FontCacheHome.class);

	/** Set this to {@code true} to leave {@code user.home} alone whatever its value. */
	public static final String DISABLE_PROPERTY = "exportfox.userHomeFallback.disabled";

	/** docx4j's property for the directory that embedded fonts are unpacked into. */
	public static final String EMBEDDED_FONT_DIR_PROPERTY =
			"docx4j.openpackaging.parts.WordprocessingML.ObfuscatedFontPart.tmpFontDir";

	/** Where docx4j puts embedded fonts, relative to {@code user.home}, if not told otherwise. */
	private static final String EMBEDDED_FONT_SUBDIRECTORY = ".docx4all/temporary embedded fonts";

	private static final String USER_HOME = "user.home";
	private static final String TMPDIR = "java.io.tmpdir";
	private static final String FALLBACK_PREFIX = "docx4j-home";

	/** Last resort: the POSIX temp directory, which is writable where little else is. */
	private static final String POSIX_TMP = "/tmp";

	private FontCacheHome() {
	}

	/**
	 * Points {@code user.home} at a usable directory if, and only if, it does not resolve
	 * to one. A {@code user.home} that exists but cannot be written to is left as it is --
	 * docx4j's own temp-directory fallback covers that case for the font cache, and the
	 * directory for embedded fonts is redirected on its own (see the class comment).
	 *
	 * <p>Idempotent, and safe to call from several threads.
	 *
	 * @return true if {@code user.home} was changed
	 */
	public static synchronized boolean ensureUsable() {
		return ensureUsable(null);
	}

	/**
	 * Points {@code user.home} at a usable directory if, and only if, it does not resolve
	 * to one. A {@code user.home} that exists but cannot be written to is left as it is --
	 * docx4j's own temp-directory fallback covers that case for the font cache, and the
	 * directory for embedded fonts is redirected on its own (see the class comment).
	 *
	 * @param preferred a directory to try before the temp directories, or null. In a
	 *                  servlet container this should be
	 *                  {@code (File) servletContext.getAttribute(ServletContext.TEMPDIR)},
	 *                  which the servlet specification requires to be writable.
	 * @return true if {@code user.home} was changed
	 */
	public static synchronized boolean ensureUsable(File preferred) {
		boolean changed = ensureUserHome(preferred);
		// After user.home, since docx4j's default for this directory derives from it.
		ensureEmbeddedFontDirectoryConfigured(preferred);
		return changed;
	}

	/**
	 * Recreates docx4j's directory for embedded fonts if it has gone missing. Cheap when
	 * it exists, so it can be called before every conversion.
	 *
	 * <p>This loads {@code AbstractFontPart}, which fixes the directory for the life of the
	 * classloader, so call {@link #ensureUsable(File)} first.
	 */
	public static void ensureEmbeddedFontDirectory() {
		recreate(AbstractFontPart.getTmpFontDir());
	}

	static void recreate(File directory) {
		if (directory == null || directory.isDirectory()) {
			// null: docx4j found no home at all, and checks for that itself.
			return;
		}
		if (directory.mkdirs() || directory.isDirectory()) {
			log.info("Recreated docx4j's directory for embedded fonts, {}", directory);
			return;
		}
		log.warn("docx4j's directory for embedded fonts, {}, does not exist and cannot be "
				+ "created. docx4j will fail every conversion with a NullPointerException in "
				+ "ObfuscatedFontPart.deleteEmbeddedFontTempFiles. Set {} in docx4j.properties "
				+ "to a writable directory.", directory, EMBEDDED_FONT_DIR_PROPERTY);
	}

	/**
	 * Makes sure the directory docx4j will unpack embedded fonts into can be created and
	 * written to, and points {@link #EMBEDDED_FONT_DIR_PROPERTY} elsewhere if not.
	 *
	 * <p>Only has an effect before {@code AbstractFontPart} is loaded, which reads the
	 * property once, in its static initialiser.
	 */
	static void ensureEmbeddedFontDirectoryConfigured(File preferred) {

		String configured = Docx4jProperties.getProperty(EMBEDDED_FONT_DIR_PROPERTY);
		File intended;
		if (configured != null) {
			intended = new File(configured);
		} else if (resolves(System.getProperty(USER_HOME))) {
			intended = new File(System.getProperty(USER_HOME), EMBEDDED_FONT_SUBDIRECTORY);
		} else {
			// Nowhere to put it at all; ensureUserHome has already said so.
			return;
		}
		if (writableDirectory(intended)) {
			return;
		}

		File fallback = firstWritable(preferred);
		File redirected = fallback == null ? null : new File(fallback, EMBEDDED_FONT_SUBDIRECTORY);
		if (redirected == null || !writableDirectory(redirected)) {
			log.warn("docx4j would unpack embedded fonts into {}, which cannot be created or "
					+ "written to, and no writable alternative was found. Every conversion will "
					+ "fail with a NullPointerException in "
					+ "ObfuscatedFontPart.deleteEmbeddedFontTempFiles. Set {} in "
					+ "docx4j.properties to a writable directory.",
					intended, EMBEDDED_FONT_DIR_PROPERTY);
			return;
		}

		Docx4jProperties.setProperty(EMBEDDED_FONT_DIR_PROPERTY, redirected.getAbsolutePath());
		log.info("docx4j would unpack embedded fonts into {}, which cannot be created or "
				+ "written to. Pointed {} at {} instead.",
				intended, EMBEDDED_FONT_DIR_PROPERTY, redirected.getAbsolutePath());
	}

	/** Creates the directory, parents included, and says whether it can be written to. */
	static boolean writableDirectory(File directory) {
		return (directory.isDirectory() || directory.mkdirs() || directory.isDirectory())
				&& directory.canWrite();
	}

	private static boolean ensureUserHome(File preferred) {

		if (Boolean.getBoolean(DISABLE_PROPERTY)) {
			log.debug("{} is set; leaving user.home alone", DISABLE_PROPERTY);
			return false;
		}

		String current = System.getProperty(USER_HOME);
		if (resolves(current)) {
			return false;
		}

		File fallback = firstWritable(preferred);
		if (fallback == null) {
			log.warn("user.home is '{}', which does not exist, and no writable directory was "
					+ "found among the candidates (nominated: {}, {}: {}, {}). docx4j will "
					+ "fall back to the relative path '.docx4j' in the working directory ({}); "
					+ "if that is not writable, loading org.docx4j.fonts.IdentityPlusMapper "
					+ "will fail outright.",
					current, preferred, TMPDIR, System.getProperty(TMPDIR), POSIX_TMP,
					new File(".").getAbsolutePath());
			return false;
		}

		System.setProperty(USER_HOME, fallback.getAbsolutePath());
		log.info("user.home was '{}', which does not exist, so docx4j would have written its "
				+ "font cache to a relative path. Pointed user.home at {} instead.",
				current, fallback.getAbsolutePath());
		return true;
	}

	/** The first candidate we can actually create a directory in. */
	private static File firstWritable(File preferred) {
		if (preferred != null) {
			File inPreferred = subdirectoryOf(preferred);
			if (inPreferred != null) {
				return inPreferred;
			}
		}
		for (String base : new String[] {System.getProperty(TMPDIR), POSIX_TMP}) {
			if (!resolves(base)) {
				continue;
			}
			File candidate = subdirectoryOf(new File(base));
			if (candidate != null) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Mirrors docx4j's own test: a path it can turn into a directory. Note that docx4j
	 * checks {@code exists()} rather than {@code isDirectory()}, so this does too --
	 * the point is to intervene exactly when docx4j would not.
	 */
	static boolean resolves(String path) {
		return path != null && !path.isBlank() && new File(path).exists();
	}

	/**
	 * Creates the directory we keep the cache in, inside {@code base}, and hands it back
	 * only if it is genuinely writable.
	 *
	 * <p>The name carries the user so that two accounts sharing a machine do not fight
	 * over one directory, and is stable across restarts so the cache is actually reused.
	 */
	static File subdirectoryOf(File base) {

		File directory = new File(base, FALLBACK_PREFIX + suffixForUser());
		if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
			log.debug("Could not create {}", directory);
			return null;
		}
		if (!directory.canWrite()) {
			log.debug("Not writable: {}", directory);
			return null;
		}
		return directory;
	}

	private static String suffixForUser() {
		String user = System.getProperty("user.name");
		if (user == null || user.isBlank()) {
			// The same broken container that loses user.home often loses user.name too.
			return "";
		}
		String safe = user.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
		return safe.isEmpty() ? "" : "-" + safe;
	}
}
