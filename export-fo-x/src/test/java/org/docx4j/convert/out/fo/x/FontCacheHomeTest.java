package org.docx4j.convert.out.fo.x;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FontCacheHomeTest {

	@Test
	void anExistingDirectoryResolvesAndIsLeftAlone(@TempDir Path home) {
		assertTrue(FontCacheHome.resolves(home.toString()));
	}

	@Test
	void theValuesAServerActuallyProducesDoNotResolve() {
		// A container with no passwd entry for the running UID: the JDK sets user.home
		// to "?". docx4j then skips its temp-directory fallback altogether and returns
		// the relative path ".docx4j".
		assertFalse(FontCacheHome.resolves("?"));
		assertFalse(FontCacheHome.resolves(null));
		assertFalse(FontCacheHome.resolves(""));
		assertFalse(FontCacheHome.resolves("   "));
		assertFalse(FontCacheHome.resolves("/nonexistent/definitely/not/here"));
	}

	@Test
	void aPlainFileCountsAsResolvable(@TempDir Path dir) throws IOException {
		// Deliberately mirrors docx4j, which tests exists() rather than isDirectory().
		// docx4j handles this case itself: the .docx4j mkdir fails and it falls back to
		// the temp directory, so there is nothing here for us to fix.
		File file = Files.createFile(dir.resolve("home")).toFile();
		assertTrue(FontCacheHome.resolves(file.getAbsolutePath()));
	}

	@Test
	void theFallbackIsAWritableDirectoryUnderTheTempDirectory() {
		File fallback = FontCacheHome.fallbackDirectory();

		assertNotNull(fallback, "no usable directory under java.io.tmpdir");
		assertTrue(fallback.isDirectory());
		assertTrue(fallback.canWrite());
		assertTrue(fallback.getAbsolutePath()
						.startsWith(new File(System.getProperty("java.io.tmpdir")).getAbsolutePath()),
				"expected the fallback under java.io.tmpdir, got " + fallback);
	}

	@Test
	void theFallbackIsStableSoTheCacheSurvivesARestart() {
		assertTrue(FontCacheHome.fallbackDirectory()
				.equals(FontCacheHome.fallbackDirectory()));
	}

	@Test
	void ensureUsableRunsOnceAndLeavesAWorkingUserHomeUntouched() {
		String before = System.getProperty("user.home");

		// The build runs with a real home directory, so this must be a no-op; a second
		// call must be one too, whatever the first decided.
		assertFalse(FontCacheHome.ensureUsable(),
				"user.home resolves during the build, so nothing should have changed");
		assertFalse(FontCacheHome.ensureUsable(), "ensureUsable must be idempotent");

		org.junit.jupiter.api.Assertions.assertEquals(before, System.getProperty("user.home"));
	}
}
