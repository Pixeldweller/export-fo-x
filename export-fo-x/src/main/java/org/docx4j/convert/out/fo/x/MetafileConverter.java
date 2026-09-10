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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts Windows metafiles into something Apache FOP can actually draw.
 *
 * <p>Word letterheads routinely carry their crest or logo as a WMF or EMF. FOP appears to
 * accept both -- it reserves the right amount of space and reports no error -- but the
 * Batik WMF transcoder it delegates to implements only a small part of the GDI op set, so
 * the picture comes out blank. That is why a docx4j PDF can end up with a correctly
 * sized, entirely empty logo box.
 *
 * <p>WMF is therefore converted to SVG with wmf2svg (already a docx4j dependency), which
 * FOP renders through Batik's SVG path. EMF has no pure-Java renderer at all, so the
 * largest raster embedded in the metafile is extracted instead -- which covers the common
 * case of a bitmap logo pasted into Word.
 */
public final class MetafileConverter {

	private static final Logger log = LoggerFactory.getLogger(MetafileConverter.class);

	/** Aldus placeable WMF header. */
	private static final int WMF_PLACEABLE_MAGIC = 0x9AC6CDD7;
	/** " EMF" signature, at offset 40 of an EMF header record. */
	private static final int EMF_SIGNATURE = 0x464D4520;
	private static final int EMF_MIN_HEADER_SIZE = 88;

	private static final int EMR_STRETCHDIBITS = 81;
	private static final int EMR_SETDIBITSTODEVICE = 80;

	private static final int BI_JPEG = 4;
	private static final int BI_PNG = 5;
	private static final int BITMAPFILEHEADER_SIZE = 14;

	/** What a conversion produced: the new bytes plus the extension they should be stored under. */
	public static final class Result {

		private final byte[] bytes;
		private final String extension;
		private final boolean converted;

		private Result(byte[] bytes, String extension, boolean converted) {
			this.bytes = bytes;
			this.extension = extension;
			this.converted = converted;
		}

		public byte[] getBytes() {
			return bytes;
		}

		/** File extension without the dot, eg "svg". */
		public String getExtension() {
			return extension;
		}

		/** False when the input was passed through untouched. */
		public boolean isConverted() {
			return converted;
		}
	}

	private MetafileConverter() {
	}

	/** True when these bytes are a WMF or EMF, ie a candidate for {@link #convert}. */
	public static boolean isMetafile(byte[] bytes) {
		return isWmf(bytes) || isEmf(bytes);
	}

	/**
	 * Converts WMF/EMF content. Anything else -- and anything that fails to convert -- is
	 * returned unchanged, so this never breaks a conversion that used to work.
	 *
	 * @param originalExtension extension of the incoming part, used when passing through
	 */
	public static Result convert(byte[] bytes, String originalExtension) {

		if (isWmf(bytes)) {
			byte[] svg = wmfToSvg(bytes);
			if (svg != null) {
				return new Result(svg, "svg", true);
			}
		} else if (isEmf(bytes)) {
			Result raster = emfToRaster(bytes);
			if (raster != null) {
				return raster;
			}
			log.warn("This EMF holds no extractable raster image and no pure-Java renderer "
					+ "for EMF exists, so Apache FOP will leave it blank. Re-saving the "
					+ "picture as PNG or SVG in the source document is the reliable fix.");
		}
		return new Result(bytes, originalExtension, false);
	}

	static boolean isWmf(byte[] b) {
		if (b == null || b.length < 18) {
			return false;
		}
		if (readIntLE(b, 0) == WMF_PLACEABLE_MAGIC) {
			return true;
		}
		// Standard (non-placeable) WMF: type 1 or 2, header size 9 words.
		int type = readShortLE(b, 0);
		int headerWords = readShortLE(b, 2);
		return (type == 1 || type == 2) && headerWords == 9;
	}

	static boolean isEmf(byte[] b) {
		return b != null
				&& b.length >= EMF_MIN_HEADER_SIZE
				&& readIntLE(b, 0) == 1                 // EMR_HEADER
				&& readIntLE(b, 40) == EMF_SIGNATURE;
	}

	private static byte[] wmfToSvg(byte[] wmf) {
		try {
			net.arnx.wmf2svg.gdi.svg.SvgGdi gdi = new net.arnx.wmf2svg.gdi.svg.SvgGdi(false);
			new net.arnx.wmf2svg.gdi.wmf.WmfParser().parse(new ByteArrayInputStream(wmf), gdi);

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			gdi.write(out);
			return stripDoctype(out.toByteArray());

		} catch (Exception | NoClassDefFoundError e) {
			log.warn("WMF could not be converted to SVG ({}); handing the metafile to FOP "
					+ "unchanged, which will most likely render it blank.", e.toString());
			return null;
		}
	}

	/**
	 * wmf2svg emits a DOCTYPE pointing at the SVG 1.0 DTD on w3.org. Left in place, the
	 * parser may try to fetch it at render time -- a network round trip per image, and a
	 * hang or failure on a machine without internet access. The declaration carries no
	 * information Batik needs.
	 */
	private static byte[] stripDoctype(byte[] svg) {
		String text = new String(svg, java.nio.charset.StandardCharsets.UTF_8);
		int start = text.indexOf("<!DOCTYPE");
		if (start < 0) {
			return svg;
		}
		int end = text.indexOf('>', start);
		if (end < 0) {
			return svg;
		}
		String stripped = text.substring(0, start) + text.substring(end + 1);
		return stripped.getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * Walks the EMF record list and returns the biggest bitmap it can rebuild.
	 * Returns null when the metafile draws purely with vector records (or is EMF+ only).
	 */
	private static Result emfToRaster(byte[] emf) {

		Result best = null;
		int bestPixels = 0;
		int offset = 0;

		while (offset + 8 <= emf.length) {
			int type = readIntLE(emf, offset);
			int size = readIntLE(emf, offset + 4);
			if (size < 8 || offset + size > emf.length) {
				break; // malformed or truncated: stop rather than guess
			}
			if (type == EMR_STRETCHDIBITS || type == EMR_SETDIBITSTODEVICE) {
				Candidate c = readDibRecord(emf, offset, size, type);
				if (c != null && c.pixels > bestPixels) {
					Result converted = dibToImage(c);
					if (converted != null) {
						best = converted;
						bestPixels = c.pixels;
					}
				}
			}
			offset += size;
		}
		return best;
	}

	private static final class Candidate {
		byte[] header;
		byte[] bits;
		int compression;
		int pixels;
	}

	private static Candidate readDibRecord(byte[] emf, int recordOffset, int recordSize, int type) {
		// Both records place the four DIB offsets at the same distance from the record
		// start: bounds(16) + 6 LONGs(24) for STRETCHDIBITS, bounds(16) + 6 LONGs(24) for
		// SETDIBITSTODEVICE followed by its own offsets.
		int fieldOffset = recordOffset + 8 + 16 + 24;
		if (fieldOffset + 16 > recordOffset + recordSize) {
			return null;
		}
		int offBmi = readIntLE(emf, fieldOffset);
		int cbBmi = readIntLE(emf, fieldOffset + 4);
		int offBits = readIntLE(emf, fieldOffset + 8);
		int cbBits = readIntLE(emf, fieldOffset + 12);

		if (cbBmi < 40 || cbBits <= 0) {
			return null;
		}
		int bmiStart = recordOffset + offBmi;
		int bitsStart = recordOffset + offBits;
		if (bmiStart < 0 || bitsStart < 0
				|| bmiStart + cbBmi > emf.length
				|| bitsStart + cbBits > emf.length) {
			return null;
		}

		Candidate c = new Candidate();
		c.header = java.util.Arrays.copyOfRange(emf, bmiStart, bmiStart + cbBmi);
		c.bits = java.util.Arrays.copyOfRange(emf, bitsStart, bitsStart + cbBits);
		c.compression = readIntLE(c.header, 16);
		int width = Math.abs(readIntLE(c.header, 4));
		int height = Math.abs(readIntLE(c.header, 8));
		c.pixels = width * height;
		if (log.isDebugEnabled()) {
			log.debug("EMF record type {}: {}x{} DIB, compression {}", type, width, height, c.compression);
		}
		return c;
	}

	private static Result dibToImage(Candidate c) {
		// BI_PNG / BI_JPEG mean the "bits" already are a complete image file.
		if (c.compression == BI_PNG) {
			return new Result(c.bits, "png", true);
		}
		if (c.compression == BI_JPEG) {
			return new Result(c.bits, "jpg", true);
		}
		byte[] bmp = wrapAsBmp(c.header, c.bits);
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(bmp));
			if (image == null) {
				return null;
			}
			ByteArrayOutputStream png = new ByteArrayOutputStream();
			ImageIO.write(image, "png", png);
			return new Result(png.toByteArray(), "png", true);
		} catch (IOException e) {
			log.debug("Could not decode the DIB embedded in the EMF: {}", e.toString());
			return null;
		}
	}

	/** Prepends a BITMAPFILEHEADER so a bare DIB becomes a readable .bmp. */
	private static byte[] wrapAsBmp(byte[] header, byte[] bits) {
		int dataOffset = BITMAPFILEHEADER_SIZE + header.length;
		int fileSize = dataOffset + bits.length;

		ByteBuffer buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
		buf.put((byte) 'B').put((byte) 'M');
		buf.putInt(fileSize);
		buf.putInt(0);
		buf.putInt(dataOffset);
		buf.put(header);
		buf.put(bits);
		return buf.array();
	}

	/** True if this filename's extension names a metafile. */
	public static boolean hasMetafileExtension(String filename) {
		if (filename == null) {
			return false;
		}
		String lower = filename.toLowerCase(Locale.ROOT);
		return lower.endsWith(".wmf") || lower.endsWith(".emf");
	}

	private static int readIntLE(byte[] b, int offset) {
		return (b[offset] & 0xFF)
				| ((b[offset + 1] & 0xFF) << 8)
				| ((b[offset + 2] & 0xFF) << 16)
				| ((b[offset + 3] & 0xFF) << 24);
	}

	private static int readShortLE(byte[] b, int offset) {
		return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
	}
}
