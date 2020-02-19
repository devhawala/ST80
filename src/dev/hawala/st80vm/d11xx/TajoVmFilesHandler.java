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

package dev.hawala.st80vm.d11xx;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.primitives.iVmFilesHandler;

/**
 * File set handler for a Smalltalk-80 image with an emulated Tajo file system.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class TajoVmFilesHandler implements iVmFilesHandler {
	
	private static void logf(String pattern, Object... args) {
		if (Config.TRACE_TAJO_OPS) { System.out.printf(pattern, args); }
	}
	
	private static final String SEARCHPATH_FILE = ";searchpath.txt";
	
	public static TajoVmFilesHandler forImageFile(String imageFile, StringBuilder sb) throws IOException {
		// open the image file
		String fn = (imageFile.toLowerCase().endsWith(".im")) ? imageFile : imageFile + ".im";
		File imFile = new File(fn);
		if (!imFile.exists() || imFile.isDirectory()) {
			File tmp = new File(imageFile);
			if (!tmp.exists() || tmp.isDirectory()) {
				sb.append("** image file '").append(fn).append("' not found (for DV6 / Tajo)\n");
				return null;
			}
			imFile = tmp;
		}
		
		// verify if there is a .sources or a .changes besides the image
		String[] siblings = imFile.getParentFile().list();
		boolean foundSmalltalk = false;
		for (String sibling : siblings) {
			String lcFn = sibling.toLowerCase();
			if (lcFn.endsWith(".sources") || lcFn.endsWith(".changes")) {
				foundSmalltalk = true;
			}
		}
		if (!foundSmalltalk) {
			sb.append("Smalltalk sources or changes not found besides image => not acceptable for Tajo emulated file system\n");
			return null;
		}
		
		// find the searchpath file in up to 4 levels up
		String pathToImage = imFile.getName();
		int depth = 0;
		File dir = imFile.getParentFile();
		File searchpathFile = null;
		while(searchpathFile == null && dir != null && depth < 4) {
			// check if there is a searchpath file
			File[] files = dir.listFiles();
			for (File f : files) {
				String name = f.getName();
				if (name.equalsIgnoreCase(SEARCHPATH_FILE)) {
					searchpathFile = f;
					break;
				}
			}
			
			// try one level deeper
			if (searchpathFile == null) {
				pathToImage = dir.getName() + ">" + pathToImage;
				depth++;
				dir = dir.getParentFile();
			}
		}
		
		// get the volume as
		// - either: the directory where the searchpath file is
		// - or: the directory where the image is
		final File volumeDir;
		final List<String> searchpath;
		if (searchpathFile == null) {
			volumeDir = imFile.getParentFile();
			pathToImage = "<>" + imFile.getName();
			searchpath = new ArrayList<>();
			searchpath.add("<>");
		} else {
			volumeDir = searchpathFile.getParentFile();
			pathToImage = "<>" + pathToImage;
			searchpath = Files.readAllLines(Paths.get(searchpathFile.getAbsolutePath()));
		}
		
		logf("## volume dir: %s\n", volumeDir.getAbsolutePath());
		logf("## image file: %s\n", pathToImage);
		logf("## searchpath:\n");
		for(String d : searchpath) {
			logf("##  %s\n", d);
		}
		TajoFilesystem.initialize(volumeDir, pathToImage, searchpath);
		
		return new TajoVmFilesHandler(imFile.getAbsolutePath());
	}
	
	private String imageFilename;
	
	private TajoVmFilesHandler(String imageFilename) throws IOException {
		this.imageFilename = imageFilename;
		
		// load the image
		Memory.loadVirtualImage(this.imageFilename);
	}

	@Override
	public void setSnapshotFilename(String filename) {
		this.imageFilename = filename;
	}

	@Override
	public boolean saveSnapshot(PrintStream ps) {
		Memory.saveVirtualImage(this.imageFilename);
		return true;
	}

	@Override
	public boolean saveDiskChanges(PrintStream ps) {
		// nothing to do, as the Tajo filesystem operates directly on the disk
		return false;
	}

}
