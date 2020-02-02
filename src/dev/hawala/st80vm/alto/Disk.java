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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * Implementation of an Alto filesystem on a single disk. This class
 * allows to load and save an Alto disk image (as found at Bitsavers and
 * used by other emulators like ContrAlto) with an additional delta file
 * which holds the changed disk pages. This class also provides the "usual"
 * file system operations like create, delete, rename, list files, format disk.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Disk {
	
	/*
	 * exceptions
	 */
	
	public static class InvalidDiskException extends Exception {
		private static final long serialVersionUID = 8155092041263830167L;
	}
	
	public static class DiskFullException extends Exception {
		private static final long serialVersionUID = -5275847034146115607L;
	}
	
	public static class FileError extends Exception {
		private static final long serialVersionUID = -2070204711748592893L;
		private FileError(String msg) { super(msg); }
	}
	
	
	/*
	 * disk structure variables
	 */
	
	private static DiskDescriptor diskDescriptor = null;
	private static Directory rootDirectory = null;
	
	/*
	 * low-level item management
	 */
	
	public static int allocateFileId() throws InvalidDiskException {
		if (diskDescriptor == null) {
			throw new InvalidDiskException();
		}
		return diskDescriptor.allocateFileId();
	}
	
	public static DiskPage allocatePage(LeaderPage forFile) throws InvalidDiskException, DiskFullException {
		if (diskDescriptor == null) {
			throw new InvalidDiskException();
		}
		return diskDescriptor.allocatePage(forFile);
	}
	
	public static void freePage(DiskPage page) throws InvalidDiskException {
		if (diskDescriptor == null) {
			throw new InvalidDiskException();
		}
		diskDescriptor.freePage(page);
	}
	
	/*
	 * high-level file management
	 */
	
	public static LeaderPage createFile(String filename, Date createDate, Date writeDate, Date readDate) throws InvalidDiskException, DiskFullException, FileError {
		if (!isPresent()) {
			throw new InvalidDiskException();
		}
		if (rootDirectory.getFile(filename) != null) {
			throw new FileError("a file named '" + filename + "' already exists");
		}
		
		// create leader page
		int fileId = allocateFileId();
		DiskPage leaderDiskPage = diskDescriptor.allocateNakedPage()
			.setFileId(false, fileId);
		LeaderPage leaderPage = new LeaderPage(leaderDiskPage)
			.initialize(filename, rootDirectory.getLeaderFileId(), rootDirectory.getLeaderVda());
		if (createDate != null) { leaderPage.setCreated(createDate); }
		if (writeDate != null) { leaderPage.setWritten(writeDate); }
		if (readDate != null) { leaderPage.setRead(readDate); }
		
		// create first content page
		allocatePage(leaderPage);
		
		// add it to the directory
		rootDirectory.rebuildDirectory(); // not the fastest way, but this also implicitly sorts the directory
		
		// done
		return leaderPage;
	}
	
	public static LeaderPage getFile(String filename) throws InvalidDiskException {
		if (!isPresent()) {
			throw new InvalidDiskException();
		}
		
		FileEntry fe = rootDirectory.getFile(filename);
		if (fe == null) { return null; }
		return fe.getLeaderPage();
	}
	
	public static void deleteFile(String filename) throws InvalidDiskException, FileError {
		if (!isPresent()) {
			throw new InvalidDiskException();
		}
		
		// check file existence
		FileEntry fileEntry = rootDirectory.getFile(filename);
		if (fileEntry == null) {
			throw new FileError("file named '" + filename + "' not found"); 
		}
		LeaderPage leaderPage = fileEntry.getLeaderPage();
		if (leaderPage == null) {
			throw new FileError("file named '" + filename + "' not found"); 
		}
		
		// check for forbidden file
		checkForReservedFile(filename);
		
		// free all pages
		DiskPage leaderDiskPage = leaderPage.getPage();
		RDA nextPageRDA = leaderDiskPage.getNextRDA();
		freePage(leaderDiskPage);
		while(nextPageRDA != RDA.NULL) {
			DiskPage page = DiskPage.forRDA(nextPageRDA);
			nextPageRDA = page.getNextRDA();
			freePage(page);
		}
		
		// remove it from the directory
		rootDirectory.rebuildDirectory();
	}
	
	public static void renameFile(String filename, String newName) throws InvalidDiskException, FileError {
		if (!isPresent()) {
			throw new InvalidDiskException();
		}
		
		// check existence of file resp. non.-existence of the new filename
		FileEntry fileEntry = rootDirectory.getFile(filename);
		if (fileEntry == null) {
			throw new FileError("file named '" + filename + "' not found");
		}
		LeaderPage leaderPage = fileEntry.getLeaderPage();
		if (leaderPage == null) {
			throw new FileError("file named '" + filename + "' not found");
		}
		if (rootDirectory.getFile(newName) != null) {
			throw new FileError("a file named '" + newName + "' already exists");
		}
		
		// check for forbidden files
		checkForReservedFile(filename);
		checkForReservedFile(newName);
		
		// rename the file
		leaderPage.setFilename(newName);
		
		// update the directory
		rootDirectory.rebuildDirectory();
	}
	
	public static List<FileEntry> listFiles() throws InvalidDiskException {
		if (!isPresent()) {
			throw new InvalidDiskException();
		}
		return rootDirectory.list();
	}
	
	public static List<FileEntry> scanRootFiles() throws InvalidDiskException {
		if (!isPresent()) {
			throw new InvalidDiskException();
		}
		return rootDirectory.scan();
	}
	
	private static void checkForReservedFile(String filename) throws FileError {
		if ("SysDir".equalsIgnoreCase(filename) || "DiskDescriptor".equalsIgnoreCase(filename)) {
			throw new FileError("file '" + filename + "' may not be renamed or removed");
		}
	}
	
	
	/*
	 * basic disk image operations: load, unload, format
	 */
	
	private static File loadedDiskImageFile = null;
	
	public static boolean isPresent() {
		return diskDescriptor != null && rootDirectory != null;
	}
	
	public static void unloadDiskImage() {
		diskDescriptor = null;
		rootDirectory = null;
		loadedDiskImageFile = null;
	}
	
	public static void loadDiskImage(File imageFile) throws IOException, InvalidDiskException {
		// reset
		unloadDiskImage();
		
		// read base disk image content
		try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(imageFile))) {
			for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
				DiskPage.forVDA(vda).loadPage(bis);
			}
		}
		
		// read delta file if present
		File deltaFile = new File(imageFile.getAbsolutePath() + ".delta");
		if (deltaFile.exists()) {
			try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(deltaFile))) {
				int pageNo = DiskPage.readWord(bis);
				while(pageNo >= 0) {
					DiskPage.forVDA(pageNo).loadDeltaPage(bis);
					pageNo = DiskPage.readWord(bis);
				}
			}
		}
		
		// find the files 'SysDir' and 'DiskDescriptor'
		Directory rootDir = null;
		DiskDescriptor diskDescr = null;
		for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
			DiskPage page = DiskPage.forVDA(vda);
			if (page.isUsed() && page.getFilepage() == 0) {
				LeaderPage lp = new LeaderPage(page);
				if ("SysDir".equalsIgnoreCase(lp.getFilename()) && page.isDirectory()) {
					rootDir = new Directory(lp);
				} else if ("DiskDescriptor".equalsIgnoreCase(lp.getFilename()) && !page.isDirectory()) {
					diskDescr = new DiskDescriptor(page);
				}
			}
		}
		if (rootDir == null) {
			throw new RuntimeException("invalid Alto disk image, missing root directory file");
		}
		if (diskDescr == null) {
			throw new RuntimeException("invalid Alto disk image, missing disk descriptor file");
		}
		
		// ok, disk seems to be valid
		rootDirectory = rootDir;
		diskDescriptor = diskDescr;
		loadedDiskImageFile = imageFile;
	}
	
	public static void saveDiskImage(File toFile) throws IOException, InvalidDiskException {
		if (!isPresent()) {
			throw new InvalidDiskException();
		}
		if (toFile == null && loadedDiskImageFile == null) {
			throw new IllegalArgumentException("missing target file");
		}
		
		fixSmalltalkOmissions(); // fix Smalltalk bugs for Alto file system
		
		File target = (toFile != null) ? toFile : loadedDiskImageFile;
		
		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(target))) {
			for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
				DiskPage page = DiskPage.forVDA(vda);
				page.savePage(bos, false);
			}
		}
		
		File delta = new File(target.getAbsolutePath() + ".delta");
		if (delta.exists()) {
			delta.delete(); // we did a full save, so the delta is obsolete (and may no longer be used!)
		}
	}
	
	public static void saveDiskDeltas() throws InvalidDiskException, IOException {
		if (!isPresent()) {
			throw new InvalidDiskException();
		}
		if (loadedDiskImageFile == null) {
			throw new IllegalArgumentException("cannot save delta for non-base disk");
		}
		
		fixSmalltalkOmissions(); // fix Smalltalk bugs for Alto file system
		
		File target = new File(loadedDiskImageFile.getAbsolutePath() + ".delta");
		
		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(target))) {
			for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
				DiskPage page = DiskPage.forVDA(vda);
				if (page.isDirty()) {
					page.savePage(bos, true);
				}
			}
		}
	}
	
	public static boolean isChanged() {
		if (!isPresent()) {
			return false;
		}
		for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
			DiskPage page = DiskPage.forVDA(vda);
			if (page.isDirty()) {
				return true;
			}
		}
		return false;
	}
	
	public static void format() throws InvalidDiskException {
		// reset all sectors
		for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
			DiskPage page = DiskPage.forVDA(vda);
			page.setUnused();
		}
		
		// vda[0]: normally: copy of 1st data page of 'Sys.Boot', here dummy boot sector (no 'Sys.boot')
		DiskPage page_00 = DiskPage.forVDA(0);
		page_00
			.setNextRDA(RDA.NULL)
			.setPrevRDA(RDA.NULL)
			.setFilepage(1)
			.setFileId(false, 0x0066);
		for (int i = 0; i < DiskPage.WORDLEN_DATA; i++) {
			page_00.putWord(i, 0xDEAD);
		}
		
		// vda[1]: leader page for 'SysDir' (fileId: 0x0064)
		DiskPage page_01 = DiskPage.forVDA(1);
		page_01
			.setNextRDA(RDA.forVDA(2))
			.setPrevRDA(RDA.NULL)
			.setFileId(true, 0x0064);
		LeaderPage leader_01 = new LeaderPage(page_01)
				.initialize("SysDir", 0x0064, 0x0001)
				.setLastPageHint(41, 40, 512);
		// add DSHAPE attribute for the disk to the file properties (taken from a single disk, in the hope it is correct)
		page_01
			.putWord(0x001A, 0x0105) // attr-type DSHAPE, length 5 words
			.putWord(0x001B, 0x0001) // 1 disk (?)
			.putWord(0x001C, 0x00CB) // 203 cyls
			.putWord(0x001D, 0x0002) // 2 heads
			.putWord(0x001E, 0x000C);// 12 sectors
		
		// vda[2]..vda[41]: data pages for 'SysDir'
		int sysDirPageNo = 1;
		for (int vda = 2; vda <= 41; vda++) {
			DiskPage.forVDA(vda)
				.setNextRDA(RDA.forVDA(vda + 1))
				.setPrevRDA(RDA.forVDA(vda - 1))
				.setFileId(true, 0x0064)
				.setFilepage(sysDirPageNo++)
				.setNBytes(512);
		}
		DiskPage.forVDA(41).setNextRDA(RDA.NULL);
		
		// vda[42]: leader page for 'DiskDescriptor' (fileId: 0x0065)
		DiskPage page_42 = DiskPage.forVDA(42);
		page_42
			.setNextRDA(RDA.forVDA(43))
			.setPrevRDA(RDA.forVDA(0))
			.setFileId(false, 0x0065);
		LeaderPage leader_42 = new LeaderPage(page_42)
				.initialize("DiskDescriptor", 0x0064, 0x0001)
				.setLastPageHint(44, 2, 130);
		
		// vda[43]..vda[44]: data pages for 'DiskDescriptor'
		DiskPage page_43 = DiskPage.forVDA(43);
		page_43
			.setNextRDA(RDA.forVDA(44))
			.setPrevRDA(RDA.forVDA(42))
			.setFileId(false, 0x0065)
			.setFilepage(1)
			.setNBytes(512);
		DiskPage page_44 = DiskPage.forVDA(44);
		page_44
			.setNextRDA(RDA.NULL)
			.setPrevRDA(RDA.forVDA(43))
			.setFileId(false, 0x0065)
			.setFilepage(2)
			.setNBytes(130);
		
		// mark pages above as used in the 'DiskDescriptor' (bitmap & remaining free pages), set last SN, ...
		// ... initialize the disk descriptor (all words are already zeroed out, see formatting begin)
		page_43
			.putWord(0, 1)      // nDisks
			.putWord(1, 203)    // nTracks
			.putWord(2,  2)     // nHeads
			.putWord(3, 12)     // sectors
			.putWord(4,  0)     // lastSN[0]
			.putWord(5, 0x0067) // lastSN.fileId
			.putWord(6,  9)     // blank
			.putWord(7, 305)    // diskBTsize (number valid words in the bit table: 305 words for 4880 pages = 4872 pages in disk rounded up to a 16 bit word)
			.putWord(8,  0)     // defVersionsKept (0 -> no versioning)
			.putWord(9, RDA.DISK_PAGES - 45); // freePages: disk size - pages used so far
		// ... set bits for pages already used in the disk descriptor
		page_43
			.putWord(0x10, 0xFFFF)  // pages 0..15 are used
			.putWord(0x11, 0xFFFF)  // pages 16..31 are used
			.putWord(0x12, 0xFFF8); // pages 31..44 are used
		
		// allocate diskDescriptor
		diskDescriptor = new DiskDescriptor(page_42);
		
		// build the root directory (putting 'SysDir', 'DiskDescriptor' into the directory)
		rootDirectory = new Directory(leader_01);
		rootDirectory.rebuildDirectory();
	}
	
	// fix Smalltalk bugs when changing the Alto file system
	private static void fixSmalltalkOmissions() {
		rootDirectory.addOrphanedFiles(); // new files belong to no directory!
		diskDescriptor.correctFreePagesInformation(false); //  free pages bitmap is not maintained corectly, free page count is not maintained
	}

}
