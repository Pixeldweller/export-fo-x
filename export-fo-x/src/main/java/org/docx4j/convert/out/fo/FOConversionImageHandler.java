/*
   Licensed to Plutext Pty Ltd under one or more contributor license agreements.

 *  This file is part of docx4j.

    docx4j is licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.

    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */
/*
 * MODIFIED by the export-fo-x project, 2026.
 *
 * Taken from docx4j-export-fo 11.5.9 (Copyright Plutext Pty Ltd, Apache
 * License 2.0) and changed: WMF and EMF images are converted on the way out,
 * via MetafileConverter.
 *
 * See NOTICE and README.md at the root of this repository.
 */
package org.docx4j.convert.out.fo;

import java.io.File;
import java.net.MalformedURLException;

import org.docx4j.convert.out.fo.x.MetafileConverter;
import org.docx4j.model.images.FileConversionImageHandler;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.parts.WordprocessingML.BinaryPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This is a File-based ImageHandler, for generating images used in FO/PDF-documents
 *
 * <p>export-fo-x addition: Windows metafiles (WMF/EMF) are converted on the way out.
 * FOP accepts a metafile without complaint but renders it blank, which is how a
 * letterhead logo silently vanishes from the PDF; see {@link MetafileConverter}.
 */
public class FOConversionImageHandler extends FileConversionImageHandler {

	private final static Logger log = LoggerFactory.getLogger(FOConversionImageHandler.class);

	/** Create a PDFConversionImageHandler,
	 * the images are generated in the java.io.tmpdir directory, runs are differentiated with an uuid
	 */
	public FOConversionImageHandler() {
		super(null, true);
	}

	/** Create a PDFConversionImageHandler
	 *
	 * @param imageDirPath Path where the images should be stored
	 * @param includeUUID, if a uuid should be used in the image name to differentiate the runs
	 */
	public FOConversionImageHandler(String imageDirPath, boolean includeUUID) {
		super(imageDirPath, includeUUID);
	}

	@Override
	protected String storeImage(BinaryPart binaryPart, byte[] bytes, File folder, String filename)
			throws Docx4JException {

		if (MetafileConverter.isMetafile(bytes)) {
			MetafileConverter.Result result = MetafileConverter.convert(bytes, extensionOf(filename));
			if (result.isConverted()) {
				String converted = replaceExtension(filename, result.getExtension());
				log.debug("Converted metafile {} to {}", filename, converted);
				return super.storeImage(binaryPart, result.getBytes(), folder, converted);
			}
		}
		return super.storeImage(binaryPart, bytes, folder, filename);
	}

	private static String extensionOf(String filename) {
		int dot = filename == null ? -1 : filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1);
	}

	private static String replaceExtension(String filename, String extension) {
		int dot = filename.lastIndexOf('.');
		String base = dot < 0 ? filename : filename.substring(0, dot);
		return base + "." + extension;
	}

	@Override
	protected String setupImageUri(File imageFile) {
		// file:///"
		try {
			return imageFile.toURI().toURL().toString();
		} catch (MalformedURLException e) {
			log.error(e.getMessage(), e);
			return imageFile.getName();
		}
	}
}
