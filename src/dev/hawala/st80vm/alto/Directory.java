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

import java.util.ArrayList;
import java.util.List;

import dev.hawala.st80vm.alto.Disk.DiskFullException;
import dev.hawala.st80vm.alto.Disk.InvalidDiskException;
import dev.hawala.st80vm.alto.FileReader.EofException;

/**
 * Representation of an Alto filesystem directory file on the Alto disk image.
 * <p>
 * Remark: an Alto disk could have several directories for a hierarchical file
 * system, but Smalltalk uses only the root directory without subdirectories.
 * (this is consistent with some information that none of the Alto systems (BCPL,
 * Mesa, Lisp, Smalltalk) used the subdirectory feature)
 * </p>
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Directory {
	
	private final byte USED_ENTRY_BYTE = (byte)0x04;
	
	private final DiskPage leaderDiskPage;
	private final LeaderPage leaderPage;
	
	public Directory(LeaderPage leaderPage) {
		this.leaderPage = leaderPage;
		this.leaderDiskPage = leaderPage.getPage();
	}
	
	public LeaderPage getLeaderPage() {
		return this.leaderPage;
	}
	
	public int getLeaderFileId() {
		return this.leaderDiskPage.getFileId();
	}
	
	public int getLeaderVda() {
		return this.leaderDiskPage.getVDA();
	}
	
	public List<FileEntry> list() {
		List<FileEntry> entries = new ArrayList<>();
		FileReader rdr = new FileReader(this.leaderDiskPage);
		try {
			while(!rdr.isAtEnd()) {
				if ((rdr.nextByte() & USED_ENTRY_BYTE) == 0) {
					// deleted entry or empty chain for filling up to the file end
					// => skip number of words indicated by next byte
					int wordLen = rdr.nextByte() & 0x00FF;
					for (int i = 0; i < wordLen; i++) {
						rdr.nextWord();
					}
					continue;
				}
				FileEntry e = new FileEntry(rdr);
				entries.add(e);
			}
		} catch(EofException e) {
			// last entry is incomplete? => ignore it
		}
		return entries;
	}
	
	// find all files on disk claiming to be in this directory based on the leader pages
	public List<FileEntry> scan() {
		List<FileEntry> entries = new ArrayList<>();
		int myFileId = this.leaderDiskPage.getFileId();
		for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
			DiskPage page = DiskPage.forVDA(vda);
			if (page.isUsed() && page.getFilepage() == 0) {
				LeaderPage lp = new LeaderPage(page);
				int lp_fileId = lp.getDirFpHint_fileId();
				if (lp_fileId == myFileId) {
					entries.add(new FileEntry(page.getFileId(), page.getHeaderRDA().getVDA(), lp.getFilename()));
				}
			}
		}
		return entries;
	}
	
	public FileEntry getFile(String filename) {
		List<FileEntry> entries = this.scan();
		for (FileEntry e : entries) {
			if (e.getFilename().equalsIgnoreCase(filename)) {
				return e;
			}
		}
		return null;
	}
	
	public void rebuildDirectory() {
		// clear current data in directory pages
		RDA rda = this.leaderDiskPage.getNextRDA();
		while(rda != RDA.NULL) {
			DiskPage page = DiskPage.forRDA(rda);
			for (int i = 0; i < (page.getNBytes() + 1) / 2; i++) {
				page.putWord(i, 0);
			}
			rda = page.getNextRDA();
		}
		
		// scan disk for allocated leader pages having this as directory and sort by ascending filename
		List<FileEntry> entries = this.scan();
		entries.sort( (l,r) -> l.getFilename().compareToIgnoreCase(r.getFilename()) );
		
		// write new directory content, then fill up with an empty-chain up to the file end
		FileWriter fw = new FileWriter(this.leaderPage, 0);
		try {
			// directory content
			for (FileEntry e : entries) {
				byte[] filename = e.getFilename().getBytes();
				int filenameLen = e.getFilename().length();
				int wordLen = (6 + ((filenameLen + 3) / 2)) & 0x00FF; // length-byte + filename + '.', rounded up to word boundary
				
				fw.putWord((USED_ENTRY_BYTE << 8) | wordLen); // type+length, with type=04 => valid entry
				fw.putWord(0);                         // FP.serialNumber[0]
				fw.putWord(e.getFileId());             // FP.serialNumber[1]
				fw.putWord(1);                         // FP.version
				fw.putWord(0);                         // FP.blank
				fw.putWord(e.getLeaderVDA());          // FP.leaderVDA
				fw.putByte((byte)(filenameLen + 1));   // filename[0]: length of filename including trailing '.'
				for (int i = 0; i < filenameLen; i++) {
					fw.putByte(filename[i]);
				}
				fw.putByte((byte)'.');
			}
			
			// compute remaining word count to fill
			int fileLen = 0;
			RDA currRda = this.leaderDiskPage.getNextRDA();
			while(currRda != RDA.NULL) {
				DiskPage page = DiskPage.forRDA(currRda);
				fileLen += page.getNBytes();
				currRda = page.getNextRDA();
			}
			
			// create an empty chain
			int writtenLen = fw.getNextBytePosition();
			if ((writtenLen & 1) == 1) { // not on a word boundary
				fw.putByte((byte)0);
				writtenLen++;
			}
			int remainingBytes = fileLen - writtenLen;
			while(remainingBytes > 0) {
				int chunkWords = Math.min(100, (remainingBytes + 1) / 2);
				fw.putByte((byte)0);          // deleted entry
				fw.putByte((byte)chunkWords); // lengh in words including this word
				for (int i = 1; i < chunkWords; i++) { // starting at 1 since 1 word was already written
					fw.putWord(0);
				}
				remainingBytes -= chunkWords * 2;
			}
			
		} catch (InvalidDiskException | DiskFullException e) {
			// ignored, as these can't happen: the disk is initialized here and the directory does not grow
		}
	}
	
	// collect all files not assigned to a directory and add them to this directory
	public void addOrphanedFiles() {
		boolean foundOrphans = false;
		for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
			DiskPage page = DiskPage.forVDA(vda);
			if (page.isUsed() && page.getFilepage() == 0) {
				LeaderPage lp = new LeaderPage(page);
				int lp_fileId = lp.getDirFpHint_fileId();
				if (lp_fileId == 0) {
					lp.setDirFpHint(this.leaderDiskPage.getFileId(), this.leaderDiskPage.getVDA());
					foundOrphans = true;
				}
			}
		}
		
		if (foundOrphans) {
			this.rebuildDirectory();
		}
	}

}
