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
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Simplified emulation of the Tajo file system (search path and file access) on top
 * of a standard file system on the OS where ST80 runs (Windows or Unix-oids)
 * through the standard Java file API.
 * 
 * <p>
 * The Tajo file system has a virtual volume {@code <User>} which is a base directory
 * in the OS file system. The directory structure below this base directory and
 * the files found there are mapped into the virtual Tajo file system with most
 * of the properties usually found in Tajo/XDE, most notably a "search path" 
 * representing the directories where files are found by default (and having
 * the first directory being where new files are created) if the filename only
 * is given and case insensitive names. Any file outside the search path can be
 * identified by giving the absolute path from the volume (e.g. {@code <>samples>test.st}).
 * Exactly one volume implicitly named "User" is supported, which can be specified
 * as {@code <User>} or {@code <>}, as both are equivalent.
 * </p>
 * 
 * <p>
 * Remark: OS files having an semicolon in the filename are ignored, these are
 * usually the searchpath-file (a replacement for a User.cm setting the searchpath)
 * and the image backup files, but this may be used to "hide" existing files
 * from the Tajo file system.
 * </p>
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class TajoFilesystem {
	
	// the root directory in the base OS representing the Tajo volume <User>
	private static File volumeRoot = null;
	
	// all files in the Tajo volume
	private static final List<TajoFile> filesInVolumeRoot = new ArrayList<>(); // direct descendants of the volume
	private static final Map<String,TajoFile> allFiles = new HashMap<>(); // Map lowercase-fullpath -> file
	
	// the current searchpath as list of nice names and lowercas names (always full path form <User>)
	private static final List<String> searchpath = new ArrayList<>();
	private static final List<String> cmpSearchpath = new ArrayList<>();
	
	// the full path for the current Smalltalk-80 image in the Tajo filesystem
	private static String tajoAbsImagePath = "<>snapshot.im";
	
	public static void initialize(File volumeDir, String tajoImagePath, List<String> initialSearchpath) {
		volumeRoot = volumeDir;
		tajoAbsImagePath = tajoImagePath;
		
		File[] files = volumeRoot.listFiles();
		for (File f: files) {
			if (f.getName().contains(";")) { continue; }
			filesInVolumeRoot.add(new TajoFile(null, f));
		}

		setSearchPath(initialSearchpath);
		
		// dumpFS();
	}
	
	public static void setSearchPath(List<String> newSearchPath) {
		searchpath.clear();
		cmpSearchpath.clear();
		
		if (newSearchPath == null || newSearchPath.isEmpty()) {
			searchpath.add("<>");
			cmpSearchpath.add("<>");
			return;
		}
		
		for (String p : newSearchPath) {
			// sanity checks: remove volume name, ensure leading <>, ensure trailing >, check existence
			String path = p;
			if (p.charAt(0) == '<') {
				int closeIdx = p.indexOf('>');
				if (closeIdx != 1) {
					String volName = p.substring(1, closeIdx);
					if (!volName.equalsIgnoreCase("user")) { continue; } // not <User>...
					path = "<>" + p.substring(closeIdx + 1);
				}
			} else {
				path = "<>" + p;
			}
			if (!path.endsWith(">")) {
				path += ">";
			}
			String lcPath = path.toLowerCase();
			if (!"<>".equals(lcPath)) {
				TajoFile pathFile = allFiles.get(lcPath);
				if (pathFile == null || !pathFile.isDirectory()) { continue; } // does not exists or not a directory
			}
			
			// seems somehow valid: add to our searchpath lists
			searchpath.add(path);
			cmpSearchpath.add(lcPath);
		}
		if (searchpath.isEmpty()) {
			searchpath.add("<>");
			cmpSearchpath.add("<>");
		}
	}
	
	public static void getSearchPath(List<String> target) {
		target.clear();
		target.addAll(searchpath);
	}
	
	public static String getTajoAbsImagePath() {
		return tajoAbsImagePath;
	}

	public static void setTajoAbsImagePath(String tajoAbsoluteImagePath) {
		tajoAbsImagePath = tajoAbsoluteImagePath;
	}
	
	public static int getFreePages() {
		long freeBytes = volumeRoot.getFreeSpace();
		int freePages = Math.min(0x7FFFFFFF, (int)((freeBytes / 512) & 0xFFFFFFFFL));
		return freePages;
	}
	
	public static List<TajoFile> enumerateFiles(Predicate<TajoFile> predicate) {
		List<TajoFile> results = new ArrayList<>();
		List<String> cmpKeys = new ArrayList<>(allFiles.keySet());
		cmpKeys.sort( (l,r) -> l.compareTo(r) );
		for (String k : cmpKeys) {
			TajoFile f = allFiles.get(k);
			if (predicate.test(f)) {
				results.add(f);
			}
		}
		return results;
	}
	
	private static TajoFile lookupFile(String lcFn) {
		TajoFile f = allFiles.get(lcFn);
		if (f != null) { return f; }
		if (lcFn.endsWith(">")) {
			return allFiles.get(lcFn.substring(0, lcFn.length() - 1));
		} else {
			return allFiles.get(lcFn + ">");
		}
	}

	public static TajoFile locate(String filename) {
		String lcFn = filename.toLowerCase();
		
		if (lcFn.startsWith("<>")) {
			return lookupFile(lcFn);
		}
		
		for (String path : cmpSearchpath) {
			String cmpPath = path + lcFn;
			TajoFile file = lookupFile(cmpPath);
			if (file != null) { return file; }
		}
		return null;
	}
	
	public static TajoFile create(String filename, boolean asDirectory) {
		if (filename.contains(";")) {
			return null; // we disallow semicolons in any names (semicolon marks OS files to be ignored!)
		}
		
		String tajoPath = cmpSearchpath.get(0);
		String lcFn = filename.toLowerCase();
		if (lcFn.endsWith(">")) {
			lcFn = lcFn.substring(0, lcFn.length() - 1);
			filename = filename.substring(0, filename.length() - 1);
		}
		
		if (lcFn.startsWith("<>")) {
			if (lookupFile(lcFn) != null) {
				return null; // a file/directory with this name already exists
			}
			int lastSep = lcFn.lastIndexOf('>');
			tajoPath = lcFn.substring(0, lastSep + 1);
			lcFn = lcFn.substring(lastSep + 1);
			filename = filename.substring(lastSep + 1);
		}
		
		String cmpPath = tajoPath + lcFn;
		if (lookupFile(cmpPath) != null) {
			return null; // a file/directory with this name already exists
		}
		
		boolean isRootElement = "<>".equals(tajoPath);
		final File osParent;
		final TajoFile tajoParent;
		if (isRootElement) {
			tajoParent = null;
			osParent = volumeRoot;
		} else {
			tajoParent = locate(cmpSearchpath.get(0));
			if (tajoParent == null) { // WHAT?
				return null;
			}
			osParent = tajoParent.getOsFile();
		}
		 
		try {
			File osFile = new File(osParent, filename);
			boolean created = (asDirectory) ? osFile.mkdirs(): osFile.createNewFile();
			if (!created) {
				return null;
			}
			TajoFile newFile = new TajoFile(tajoParent, osFile);
			if (tajoParent != null) {
				tajoParent.addChild(newFile);
			}
			return newFile;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Representation of a Tajo file, either a plain file as a leaf or a directory
	 * having a list of all sub items.
	 */
	public static class TajoFile {
		
		private final TajoFile parent; // parent directory or null for a file in the volume root (<>)
		private final File osFile; // the real file in the underlying file system
		private final String cmpFilename; // the (simple) file name in lowercase
		private final String cmpAbsoluteFilename; // the full path incl. filename in lowercase
		private final String niceFullpath; // the full path incl. filename in mixed case as in the underlying OS
		private final List<TajoFile> children; // null for plain files or non-null containing the direct items in the directory
		
		private TajoFile(TajoFile parent, File osFile) {
			boolean isDirectory = osFile.isDirectory();
			
			// the own data
			this.parent = parent;
			this.osFile = osFile;
			this.cmpFilename = osFile.getName().toLowerCase() + (isDirectory ? ">" : "");
			String parentPath = (parent == null) ? "<>" : parent.getCmpAbsoluteFilename();
			this.cmpAbsoluteFilename = parentPath + this.cmpFilename;
			String niceParentpath = (parent == null) ? "<>" : parent.getNiceFullpath();
			this.niceFullpath = niceParentpath + this.osFile.getName() + (isDirectory ? ">" : "");
			
			// plain file or directory? for directories: create the sub items in this directory
			this.children = (isDirectory) ? new ArrayList<>() : null;
			if (this.children != null) {
				File[] files = osFile.listFiles();
				for (File f : files) {
					if (f.getName().contains(";")) { continue; }
					this.children.add(new TajoFile(this, f));
				}
			}
			
			// add to the global file mmanagement
			if (parent == null) { filesInVolumeRoot.add(this); }
			allFiles.put(this.cmpAbsoluteFilename, this);
		}
		
		private void addChild(TajoFile child) {
			this.children.add(child);
		}

		public TajoFile getParent() { return this.parent; }

		public File getOsFile() { return this.osFile; }
		
		public String getNiceName() { return this.osFile.getName(); }
		
		public String getNiceFullpath() { return this.niceFullpath; }

		public String getCmpFilename() { return this.cmpFilename; }

		public String getCmpAbsoluteFilename() { return this.cmpAbsoluteFilename; }
		
		public boolean isDirectory() { return (this.children != null); }
		
		public int getChildrenCount() {
			if (this.children != null) { return this.children.size(); }
			return 0;
		}
		
		public TajoFile getChild(int index) {
			if (this.children != null && index >= 0 && index < this.children.size()) {
				return this.children.get(index);
			}
			return null;
		}

		public RandomAccessFile open() throws IOException {
			if (this.children != null) {
				throw new IOException("cannot open() a directory");
			}
			return new RandomAccessFile(this.osFile, "rw");
		};
		
		public boolean rename(String newName) {
			// check that the new name does not exists
			if (newName.contains(";")) { return false; }
			if (newName.contains(">")) { return false; }
			if (newName.contains("<")) { return false; }
			String absNewName = (this.parent != null)
					? this.parent.getCmpAbsoluteFilename() + newName.toLowerCase()
					: "<>" + newName.toLowerCase();
			if (lookupFile(absNewName) != null) {
				return false;
			}
			
			// rename the file
			File parentFile = (this.parent != null) ? this.parent.getOsFile() : volumeRoot;
			File newFile = new File(parentFile, newName);
			boolean renamed = this.osFile.renameTo(newFile); // will probably not work for non-empty directories!
			
			// update our filesystem metadata
			if (renamed) {
				this.removeFromLists();
				new TajoFile(this.parent, newFile);
			}
			
			// done
			return renamed;
		}
		
		public void delete() {
			if (this.children != null) {
				for (TajoFile tf : this.children) {
					tf.delete();
				}
			}
			
			if (this.parent == null) {
				filesInVolumeRoot.remove(this);
			}
			allFiles.remove(this.cmpAbsoluteFilename);
			try {
				this.osFile.delete();
			} catch(Exception e) {
				// ignored...
			}
		}
		
		private void removeFromLists() {
			if (this.children != null) {
				for (TajoFile tf : this.children) {
					tf.removeFromLists();
				}
			}
			if (this.parent == null) {
				filesInVolumeRoot.remove(this);
			}
			allFiles.remove(this.cmpAbsoluteFilename);
		}
		
		private void check(String lcTajoPath) {
			if (!this.cmpAbsoluteFilename.equals(lcTajoPath)) {
				System.out.printf("   ## cmpAbsNames differ: own '%s' != key '%s'\n", this.cmpAbsoluteFilename, lcTajoPath);
			}
			if (!this.osFile.exists()) {
				System.out.printf("   ## osFile (%s) not found\n", this.osFile.getAbsolutePath());
			} else if (this.osFile.isDirectory() != (this.children != null)) {
				System.out.printf("   ## isDirectory differ: own '%s' != os '%s'\n", Boolean.toString(this.children != null), Boolean.toString(this.osFile.isDirectory()));
			}
			if ((this.parent == null) != filesInVolumeRoot.contains(this)) {
				System.out.printf("   ## inVolumeRoot info differ: own '%s' != inVolRoot '%s'\n", Boolean.toString(this.parent == null), Boolean.toString(filesInVolumeRoot.contains(this)));
			}
		}
	}
	
	public static void dumpFS() {
		System.out.printf("\n-- searchpath:\n");
		for (int i = 0; i < searchpath.size(); i++) {
			System.out.printf("--    %s => %s\n", searchpath.get(i), cmpSearchpath.get(i));
		}
		System.out.printf("--\n");
		System.out.printf("-- tajo image path: %s\n", tajoAbsImagePath);
		System.out.printf("--\n");
		System.out.printf("-- file keys:\n");
		List<String> keys = new ArrayList<>(allFiles.keySet());
		keys.sort(  (l,r) -> l.compareTo(r) );
		for (String k : keys) {
			TajoFile f = allFiles.get(k);
			System.out.printf("--  %s\n", k);
			f.check(k);
		}
	}

}
