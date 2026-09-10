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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Accepts a DOCX upload and answers with the rendered PDF. */
@RestController
public class ConversionController {

	private static final Logger log = LoggerFactory.getLogger(ConversionController.class);

	private static final String DOCX_EXTENSION = ".docx";
	private static final String DOCX_CONTENT_TYPE =
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	private final ConversionService conversionService;

	public ConversionController(ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	@PostMapping(path = "/api/convert",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_PDF_VALUE)
	public ResponseEntity<byte[]> convert(@RequestParam("file") MultipartFile file) {

		if (file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file is empty.");
		}
		String filename = originalFilename(file);
		if (!looksLikeDocx(filename, file.getContentType())) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
					"Expected a .docx (WordprocessingML) file, got: " + filename);
		}

		byte[] pdf;
		try (InputStream in = file.getInputStream()) {
			pdf = conversionService.toPdf(in, filename);
		} catch (IOException e) {
			log.warn("Could not read the upload {}", filename, e);
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"The upload could not be read: " + e.getMessage());
		} catch (Docx4JException e) {
			log.warn("Could not convert {}", filename, e);
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"The document could not be converted: " + e.getMessage());
		}

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_PDF);
		headers.setContentDisposition(ContentDisposition.attachment()
				.filename(pdfNameFor(filename), StandardCharsets.UTF_8)
				.build());
		headers.setContentLength(pdf.length);
		return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
	}

	private static String originalFilename(MultipartFile file) {
		String name = file.getOriginalFilename();
		if (name == null || name.isBlank()) {
			return "document.docx";
		}
		// A browser may send a path; keep only the last segment.
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		return slash < 0 ? name : name.substring(slash + 1);
	}

	private static boolean looksLikeDocx(String filename, String contentType) {
		return filename.toLowerCase(Locale.ROOT).endsWith(DOCX_EXTENSION)
				|| DOCX_CONTENT_TYPE.equals(contentType);
	}

	private static String pdfNameFor(String filename) {
		int dot = filename.lastIndexOf('.');
		return (dot < 0 ? filename : filename.substring(0, dot)) + ".pdf";
	}
}
