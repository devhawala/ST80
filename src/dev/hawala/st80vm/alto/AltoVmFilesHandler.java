/*
Copyright (c) 2020, Dr. Hans-Walter Latz
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * The name of the author may not be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER "AS IS" AND ANY EXPRESS
OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package dev.hawala.st80vm.alto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.hawala.st80vm.alto.Disk.InvalidDiskException;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.primitives.iVmFilesHandler;

/**
 * File set handler for a Smalltalk image with an attached Alto disk image and an optional
 * delta file.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class AltoVmFilesHandler implements iVmFilesHandler {
	
	private String imageFilename;
	private String altoDiskFilename;
	
	private AltoVmFilesHandler(String imageName, String diskName) throws IOException, InvalidDiskException {
		this.imageFilename = imageName;
		this.altoDiskFilename = diskName;
		
		// load the file set
		Memory.loadVirtualImage(this.imageFilename);
		Disk.loadDiskImage(new File(this.altoDiskFilename)); // this also loads the delta if present
	}
	
	public static AltoVmFilesHandler forImageFile(String imageName, StringBuilder sb) throws IOException {
		String imageFilename;
		String altoDiskFilename;
		if (imageName.endsWith(".im")) {
			imageFilename = imageName;
			altoDiskFilename = imageName.substring(0, imageName.length() - 3) + ".dsk";
		} else {
			imageFilename = imageName + ".im";
			altoDiskFilename = imageName + ".dsk";
		}
		
		if (!(new File(imageFilename)).canRead()) {
			sb.append("** image file '").append(imageFilename).append("' not found (for Version 2 / Alto)\n");
			return null;
		} else if (!(new File(altoDiskFilename)).canRead()) {
			sb.append("** Alto disk file '").append(altoDiskFilename).append("' not found\n");
			return null;
		} else {
			try {
				return new AltoVmFilesHandler(imageFilename, altoDiskFilename);
			} catch (InvalidDiskException e) {
				sb.append("** Alto disk invalid, not using disk '").append(altoDiskFilename).append("%s'\n");
				return null;
			}
		}
	}

	@Override
	public void setSnapshotFilename(String filename) {
		String fn = filename.toLowerCase().endsWith(".im") ? filename.substring(0, filename.length() - 3) : filename;
		String parentDir = (new File(this.imageFilename)).getParent();
		if (parentDir == null || parentDir.isEmpty()) {
			parentDir = ".";
		}
		this.imageFilename = parentDir + File.separator + fn + ".im";
		this.altoDiskFilename = parentDir + File.separator + fn + ".dsk";
	}
	
	private void addToZip(File file, ZipOutputStream zos, PrintStream ps) throws FileNotFoundException, IOException {
		if (ps != null) { ps.printf("... adding: %s\n", file.getName()); }
		
		ZipEntry zipEntry = new ZipEntry(file.getName());
		zos.putNextEntry(zipEntry);

		try (FileInputStream fis = new FileInputStream(file)) {
			byte[] buffer= new byte[1024];
			int length = fis.read(buffer);
			while (length >= 0) {
				zos.write(buffer, 0, length);
				length = fis.read(buffer);
			}
		}

		zos.closeEntry();
	}

	@Override
	public boolean saveSnapshot(PrintStream ps) {
		// archive the current fileset { image , disk [ , disk-delta ] }
		File imFile = new File(this.imageFilename);
		File dskFile = new File(this.altoDiskFilename);
		String imAbsFilename = imFile.getPath();
		String baseFn = imAbsFilename.substring(0, imAbsFilename.length() - 3);
		if (imFile.exists()) {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd_HH.mm.ss.SSS");
			String zipName = baseFn + "-" + sdf.format(new Date()) + ".zip";
			if (ps != null) { ps.printf("Creating archive: %s\n", zipName); }
			try (FileOutputStream fos = new FileOutputStream(zipName); ZipOutputStream zos = new ZipOutputStream(fos)) {
				// add the image itself
				addToZip(imFile, zos, ps);
				
				// add the alto disk
				addToZip(dskFile, zos, ps);
				
				// add the alto disk delta if present
				File dskDelta = new File(this.altoDiskFilename + ".delta");
				if (dskDelta.exists()) {
					addToZip(dskDelta, zos, ps);
				}
			} catch (IOException e) {
				if (ps != null) {
					ps.printf("** error archiving old image file set: %s\n", e.getMessage());
				}
			}
		}
		
		// save new image
		try {
			Memory.writeSnapshotFile(this.imageFilename);
		} catch (Exception e) {
			if (ps != null) {
				ps.printf("** error saving virtual image: %s\n", e.getMessage());
			}
		}
		
		// save new Alto disk image file
		try {
			Disk.saveDiskImage(dskFile);
		} catch (Exception e) {
			if (ps != null) {
				ps.printf("** error saving new Alto disk image: %s\n", e.getMessage());
			}
		}
		
		// done
		return true;
	}

	@Override
	public boolean saveDiskChanges(PrintStream ps) {
		if (Disk.isChanged()) {
			try {
				Disk.saveDiskDeltas();
				return true;
			} catch (InvalidDiskException | IOException e) {
				if (ps != null) {
					ps.printf("save Alto disk changes failed: %s\n", e.getMessage());
				}
				return false;
			}
		} else {
			return true; // no disk => nothing to save => success
		}
	}

}
