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

import dev.hawala.st80vm.alto.Disk.DiskFullException;
import dev.hawala.st80vm.alto.Disk.InvalidDiskException;

/**
 * Writer for an Alto file, allowing to overwrite the file data content from
 * a given position in the file and appending to the file if the file end is
 * reached. 
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class FileWriter {
	
	public static final int START_AT_END = 0x7FFFFFFF;
	
	private final LeaderPage leaderPage;
	private DiskPage currPage = null;
	private int currPageByte = 0; // next byte position to write on this page
	private boolean onLastPage = false;
	private int absWritePos = 0;
	
	public FileWriter(DiskPage leaderDiskPage, int startBytePos) {
		this(new LeaderPage(leaderDiskPage), startBytePos);
	}
	
	public FileWriter(LeaderPage leaderPage, int startBytePos) {
		this.leaderPage = leaderPage;
		
		int bytesToSkip = startBytePos;
		this.currPage = DiskPage.forRDA(this.leaderPage.getPage().getNextRDA());
		while(true) {
			if ((bytesToSkip <= this.currPage.getNBytes())) {
				// we found the page where startBytePos is
				this.currPageByte = bytesToSkip;
				this.absWritePos += bytesToSkip;
				break;
			}
			
			// skip all bytes on the current page
			bytesToSkip -= this.currPage.getNBytes();
			this.absWritePos += this.currPage.getNBytes();
			// must get to the next page
			if (this.currPage.getNextRDA() == RDA.NULL) {
				// it's the last page => continue writing at end of this page
				this.currPageByte = this.currPage.getNBytes();
				break;
			}
			this.currPage = DiskPage.forRDA(this.currPage.getNextRDA());
		}
		this.onLastPage = (this.currPage.getNextRDA() == RDA.NULL);
	}
	
	private void moveToNextPage() throws InvalidDiskException, DiskFullException {
		if (this.currPage.getNextRDA() == RDA.NULL) {
			this.currPage = Disk.allocatePage(this.leaderPage);
			this.onLastPage = true;
		} else {
			this.currPage = DiskPage.forRDA(this.currPage.getNextRDA());
		}
		this.currPageByte = 0;
	}
	
	public FileWriter putByte(byte b) throws InvalidDiskException, DiskFullException {
		if (this.currPageByte >= DiskPage.BYTELEN_DATA) {
			moveToNextPage();
		}
		this.currPage.putByte(this.currPageByte++, b);
		if (this.onLastPage && this.currPageByte > this.leaderPage.getLastPageHint_charPos()) {
			this.leaderPage.setLastPageHint_charPos(this.currPageByte);
		}
		this.absWritePos++;
		return this;
	}
	
	public FileWriter putWord(int w) throws InvalidDiskException, DiskFullException {
		if ((this.currPageByte & 1) == 1) {
			this.putByte((byte)0);
		}
		if (this.currPageByte >= DiskPage.BYTELEN_DATA) {
			moveToNextPage();
		}
		this.currPage.putWord(this.currPageByte / 2, w);
		this.currPageByte += 2;
		if (this.onLastPage && this.currPageByte > this.leaderPage.getLastPageHint_charPos()) {
			this.leaderPage.setLastPageHint_charPos(this.currPageByte);
		}
		this.absWritePos += 2;
		return this;
	}
	
	public FileWriter putWord(short w) throws InvalidDiskException, DiskFullException {
		return this.putWord(w & 0xFFFF);
	}
	
	public int getNextBytePosition() {
		return this.absWritePos;
	}
	
}
