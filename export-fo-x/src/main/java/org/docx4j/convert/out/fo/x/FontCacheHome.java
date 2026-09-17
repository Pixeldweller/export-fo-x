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
 */
public final class FontCacheHome {

	private static final Logger log = LoggerFactory.getLogger(FontCacheHome.class);

	/** Set this to {@code true} to leave {@code user.home} alone whatever its value. */
	public static final String DISABLE_PROPERTY = "exportfox.userHomeFallback.disabled";

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
	 * docx4j's own temp-directory fallback covers that case.
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
	 * docx4j's own temp-directory fallback covers that case.
	 *
	 * @param preferred a directory to try before the temp directories, or null. In a
	 *                  servlet container this should be
	 *                  {@code (File) servletContext.getAttribute(ServletContext.TEMPDIR)},
	 *                  which the servlet specification requires to be writable.
	 * @return true if {@code user.home} was changed
	 */
	public static synchronized boolean ensureUsable(File preferred) {

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
