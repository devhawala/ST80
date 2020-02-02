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
 * Representation of the (required and single) disk descriptor file on an Alto disk.
 * The single instance of this class manages the set of free pages on the disk, allowing
 * to get a free page or return a no longer used page to the free pages pool.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class DiskDescriptor {
	
	private final DiskPage page0;
	private final DiskPage page1;
	
	private final int NON_BITS_WORDS = 16; // number of leading words that are not use-bitmap
	private final int BITWORDS_FIRST_PAGE_LIMIT = DiskPage.WORDLEN_DATA - NON_BITS_WORDS;
	
	public DiskDescriptor(DiskPage leaderDiskPage) throws InvalidDiskException {
		// get the data pages for the DiskDescriptor
		RDA rda = leaderDiskPage.getNextRDA();
		if (rda == RDA.NULL) { throw new Disk.InvalidDiskException(); }
		this.page0 = DiskPage.forRDA(rda);
		rda = this.page0.getNextRDA();
		if (rda == RDA.NULL) { throw new Disk.InvalidDiskException(); }
		this.page1 = DiskPage.forRDA(rda);
		
		// check for supported disk geometry
		if (this.page0.getWord(0) != 1         // nDisks
			|| this.page0.getWord(1) != 203    // nTracks
			|| this.page0.getWord(2) != 2      // nHeads
			|| this.page0.getWord(3) != 12     // nSectors
			|| this.page0.getWord(7) < 0x0131  // diskBTsize (required number of bit-table words)
				) { 
			throw new Disk.InvalidDiskException();
		}
		
		// verify disk page usage and adjust bitmap / free count in case of deviations (based on page used-flag)
		this.correctFreePagesInformation(true);
	}
	
	public void correctFreePagesInformation(boolean verbose) {
		int countedFree = 0;
		for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
			DiskPage page = DiskPage.forVDA(vda);
			if (page.isUsed() != this.isUsed(vda)) {
				if (verbose) {
					System.out.printf("** warning: usage deviation for page vda=%d: page.isUsed=%s != bitTable.isUsed=%s - reverting to page setting\n", vda, page.isUsed(), this.isUsed(vda));
				}
				this.setUsed(vda, page.isUsed());
			}
			if (!page.isUsed()) {
				countedFree++;
			}
		}
		if (countedFree != this.getFree()) {
			if (verbose) {
				System.out.printf("** warning: counted free pages (%d) differs from DiskDescriptor free page count %d, using counted value\n", countedFree, this.getFree());
			}
			this.setFree(countedFree);
		}
	}
	
	public int getFree() {
		return this.page0.getWord(9);
	}
	
	private void setFree(int cnt) {
		this.page0.putWord(9,  cnt);
	}
	
	public int allocateFileId() {
		int lastFileId = page0.getWord(5);
		int fileId = lastFileId + 1;
		page0.putWord(5,  (short)fileId);
		return fileId;
	}
	
	public DiskPage allocateNakedPage() throws DiskFullException {
		for (int vda = 0; vda < RDA.DISK_PAGES; vda++) {
			DiskPage page = DiskPage.forVDA(vda);
			if (!page.isUsed()) {
				// do our accounting
				this.setUsed(vda, true);
				this.setFree(this.getFree() - 1);
				
				// reset the page
				page.setPrevRDA(RDA.NULL)
					.setNextRDA(RDA.NULL)
					.setFileId(false, 0)
					.setFilepage(0)
					.setNBytes(0);
				
				// done
				return page;
			}
		}
		throw new Disk.DiskFullException();
	}
	
	public DiskPage allocatePage(LeaderPage forFile) throws DiskFullException {
		// get a free page on disk
		DiskPage page = this.allocateNakedPage();
		
		// append the page to the end of the file and adjust the leader page of the file
		int lastPageNo = 0;
		DiskPage lastFilePage = forFile.getPage();
		RDA nextRDA = lastFilePage.getNextRDA();
		while(nextRDA != RDA.NULL) {
			lastFilePage = DiskPage.forRDA(nextRDA);
			lastPageNo++;
			nextRDA = lastFilePage.getNextRDA();
		}
		lastFilePage.setNextRDA(page.getHeaderRDA());
		page.setPrevRDA(lastFilePage.getHeaderRDA());
		page.setNextRDA(RDA.NULL);
		page.setFileId(forFile.getPage().isDirectory(), forFile.getPage().getFileId());
		page.setFilepage(lastPageNo + 1);
		page.setNBytes(0);
		forFile.setLastPageHint(page.getVDA(), page.getFilepage(), page.getNBytes());
		
		// done
		return page;
	}
	
	public void freePage(DiskPage page) {
		if (page.isUsed()) {
			// reset page
			page.setUnused();
			page.setPrevRDA(RDA.NULL);
			page.setNextRDA(RDA.NULL);
			page.setFilepage(0);
			page.setNBytes(0);
			
			// do our accounting
			this.setUsed(page.getVDA(), false);
			this.setFree(this.getFree() + 1);
		}
	}
	
	private boolean isUsed(int vda) {
		if (vda < 0 || vda >= RDA.DISK_PAGES) {
			throw new IllegalArgumentException("vda out of range");
		}
		int offset = vda / 16;
		int bit = vda % 16;
		DiskPage bitsPage = this.page0;
		if (offset >= BITWORDS_FIRST_PAGE_LIMIT) {
			bitsPage = this.page1;
			offset -= BITWORDS_FIRST_PAGE_LIMIT;
		} else {
			offset += NON_BITS_WORDS;
		}
		int bitMask = 0x8000 >> bit;
		int bitsWord = bitsPage.getWord(offset);
		return ((bitsWord & bitMask) != 0);
	}
	
	private void setUsed(int vda, boolean inUse) {
		if (vda < 0 || vda >= RDA.DISK_PAGES) {
			throw new IllegalArgumentException("vda out of range");
		}
		int offset = vda / 16;
		int bit = vda % 16;
		DiskPage bitsPage = this.page0;
		if (offset >= BITWORDS_FIRST_PAGE_LIMIT) {
			bitsPage = this.page1;
			offset -= BITWORDS_FIRST_PAGE_LIMIT;
		} else {
			offset += NON_BITS_WORDS;
		}
		int bitMask = 0x8000 >> bit;
		int bitsWord = bitsPage.getWord(offset);
		if (inUse) {
			bitsWord |= bitMask;
		} else {
			bitsWord &= (bitMask ^ 0xFFFF);
		}
		bitsPage.putWord(offset, bitsWord);
	}
	
}
