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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A page on an Alto disk, allowing to access the different parts of the page
 * (header, label fields, data words/bytes).
 * <br/>This class also manages the static array of all pages of the disk, the
 * array is addressed either with the VDA (virtual disk address or linear sector
 * number) or the RDA (raw disk address or disk/cyl/head/sector address).
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class DiskPage {
	
	public static int WORDLEN_HEADER = 2; // 1st word is 0, 2nd is RDA for abs. sector no
	public static int WORDLEN_LABEL = 8; // nextRDA, prevRDA, 0, nBytes, filepage, fid[ used, kind, fileId ] (used: 1 if used, 0xFFFF if not; kind: 0x8000 for directory, 0 for regular file, 0xFFFF for unused)
	public static int WORDLEN_DATA = 256;
	public static int BYTELEN_DATA = WORDLEN_DATA * 2;
	private static int WORDLEN_PAGE_EXT = 1 + WORDLEN_HEADER + WORDLEN_LABEL + WORDLEN_DATA; // +1: leading 1 word for page no (== vda or (mostly) 0) in the external file
	
	/*
	 * DiskPage singleton management
	 */
	
	private static final DiskPage[] pages;
	
	static {
		pages = new DiskPage[RDA.DISK_PAGES];
		for (int vda = 0; vda < pages.length; vda++) {
			pages[vda] = new DiskPage(vda);
		}
	}
	
	public static DiskPage forVDA(int vda) {
		if (vda < 0 || vda >= pages.length) {
			throw new RuntimeException(String.format("invalid VDA %d ~ 0x%04X", vda, vda));
		}
		return pages[vda];
	}
	
	public static DiskPage forRDA(int extRepr) {
		RDA rda = RDA.forExternal(extRepr);
		return forVDA(rda.getVDA());
	}
	
	public static DiskPage forRDA(RDA rda) {
		return forVDA(rda.getVDA());
	}
	
	/*
	 * instance items
	 */
	
	private final int vda;
	private final RDA headerRda;
	
	// these are in fact 16-bit quantities, but Java-short is signet and we need them as unsigned
	private final int[] label = new int[WORDLEN_LABEL];
	private final int[] data = new int[WORDLEN_DATA];
	
	private RDA nextRDA = RDA.NULL;
	private RDA prevRDA = RDA.NULL;
	private int nBytes = 0;
	private int filepage = 0;
	private int fid_used = 0xFFFF; // meaning: not used
	private int fid_kind = 0xFFFF; // meaning: not used
	private int fid_fileId = 0xFFFF; // this seems to be the "no fileId" value
	
	private boolean dirty = false;

	DiskPage(int vda) {
		this.vda = vda;
		this.headerRda = RDA.forVDA(vda);
	}
	
	/*
	 * access
	 */

	public int getVDA() { return vda; }

	public RDA getHeaderRDA() { return headerRda; }
	
	public RDA getNextRDA() {
		return this.nextRDA;
	}
	
	public DiskPage setNextRDA(RDA rda) {
		this.nextRDA = rda;
		return this;
	}
	
	public DiskPage setNextRDA(int extRepr) {
		return this.setNextRDA(RDA.forExternal(extRepr));
	}
	
	public RDA getPrevRDA() {
		return this.prevRDA;
	}
	
	public DiskPage setPrevRDA(RDA rda) {
		this.prevRDA = rda;
		return this;
	}
	
	public DiskPage setPrevRDA(int extRepr) {
		return this.setPrevRDA(RDA.forExternal(extRepr));
	}
	
	public int getNBytes() {
		return this.nBytes;
	}
	
	public DiskPage setNBytes(int bytes) {
		if (bytes < 0 || bytes > 512) {
			throw new RuntimeException("invalid nBytes for disk page");
		}
		this.nBytes = bytes;
		return this;
	}
	
	public int getFilepage() {
		return this.filepage;
	}
	
	public DiskPage setFilepage(int page) {
		if (page < 0 || page >= RDA.DISK_PAGES) {
			throw new RuntimeException("invalid filepage for disk page");
		}
		this.filepage = page;
		return this;
	}
	
	public boolean isUsed() {
		return (this.fid_used != 0xFFFF);
	}
	
	public boolean isDirectory() {
		return (this.fid_kind == 0x8000);
	}
	
	public int getFileId() {
		return this.fid_fileId;
	}
	
	public boolean isDirty() {
		return this.dirty;
	}
	
	public DiskPage setUnused() {
		this.fid_used = 0xFFFF;
		this.fid_kind = 0xFFFF;
		this.fid_fileId = 0xFFFF;
		for (int i = 0; i < this.data.length; i++) {
			this.data[i] = 0;
		}
		this.filepage = 0;
		this.nBytes = 0;
		this.dirty = true;
		return this;
	}
	
	public DiskPage setFileId(boolean isDirectory, int id) {
		this.fid_used = 1;
		this.fid_kind = isDirectory ? 0x8000 : 0;
		this.fid_fileId = id & 0xFFFF;
		this.dirty = true;
		return this;
	}
	
	public int getWord(int at) {
		return this.data[at];
	}
	
	public DiskPage putWord(int at, int value) {
		if (!this.isUsed()) {
			throw new RuntimeException("attempt to modify data of unused page");
		}
		this.data[at] = value;
		int nextBytePos = (at + 1) * 2;
		if (this.nBytes < nextBytePos) {
			this.nBytes = nextBytePos;
		}
		this.dirty = true;
		return this;
	}
	
	public DiskPage putWordRaw(int at, int value) {
		if (!this.isUsed()) {
			throw new RuntimeException("attempt to modify data of unused page");
		}
		this.data[at] = value;
		this.dirty = true;
		return this;
	}
	
	public DiskPage putWord(int at, short value) {
		return this.putWord(at, value & 0xFFFF);
	}
	
	public byte getByte(int at) {
		int word = this.getWord(at / 2);
		return ((at & 1) == 0) ? (byte)((word >> 8) & 0x00FF) : (byte)(word & 0x00FF);
	}
	
	public DiskPage putByte(int at, byte value) {
		if (!this.isUsed()) {
			throw new RuntimeException("attempt to modify data of unused page");
		}
		int atWord = at / 2;
		int word = this.getWord(atWord);
		if ((at & 1) == 0) {
			word &= 0x00FF;
			word |= (value << 8) & 0xFF00;
		} else {
			word &= 0xFF00;
			word |= value & 0xFF;
		}
		this.data[atWord] = (short)word;
		int nextBytePos = at + 1;
		if (this.nBytes < nextBytePos) {
			this.nBytes = nextBytePos;
		}
		this.dirty = true;
		return this;
	}
	
	/*
	 * persistence-I/O
	 */
	
	public static int readWord(InputStream src) throws IOException {
		int b1 = src.read();
		if (b1 < 0) { return -1; }
		int b2 = src.read();
		if (b2 < 0) { return -1; }
		return (b2 << 8) | b1; // exported alto disks are written as little endian
	}
	
	private static void writeWord(OutputStream dst, int word) throws IOException {
		int high = (word >> 8) & 0x00FF;
		int low = word & 0x00FF;
		dst.write((byte)low);  // write word as little endian: low byte first
		dst.write((byte)high);
	}
	
	public int getAbsBytePosition() {
		return this.vda * WORDLEN_PAGE_EXT;
	}
	
	private void loadRawPage(InputStream src) throws IOException {
		// header
		int header0 = readWord(src); // should be 0 (diskNo??)
		int header1 = readWord(src); // RDA of the sector
		
		// label
		for (int i = 0; i < this.label.length; i++) {
			this.label[i] = readWord(src);
		}
		
		// data
		for (int i = 0; i < this.data.length; i++) {
			this.data[i] = readWord(src);
		}
		
		// interpret header
		if (header0 != 0) {
			System.err.printf("page[%d]: header[0] = 0x%04X (not 0)\n", this.vda, header0);
		}
		if (header1 != this.headerRda.getExternal()) {
			throw new RuntimeException(String.format("page[%d]: header[1] = 0x%04X but expected: 0x%04X for %s\n", this.vda, header1, this.headerRda.getExternal(), this.headerRda.toString()));
		}
		
		// interpret label
		this.setLabelDataFromWords(this.label);
	}
	
	public void loadPage(InputStream src) throws IOException {
		// leader word (ignored)
		readWord(src);
		
		// load page content
		this.loadRawPage(src);
		
		// dirty management
		this.dirty = false; // freshly loaded, so consistent with persistent state
	}
	
	public void loadDeltaPage(InputStream src) throws IOException {
		// leader word has already been read and used to select this page to be loaded
		
		// load page content
		this.loadRawPage(src);
		
		// dirty management
		this.dirty = true; // ensure that this delta page will be saved
	}
	
	public void savePage(OutputStream dst, boolean isDeltaSave) throws IOException {
		// leader word
		writeWord(dst, (short)this.vda);
		
		// header
		writeWord(dst, (short)0); // should be 0 (diskNo??)
		writeWord(dst, this.headerRda.getExternal());
		
		// label
		this.getLabelDataToWords(this.label);
		for (int i = 0; i < this.label.length; i++) {
			writeWord(dst, this.label[i]);
		}
		
		// data
		for (int i = 0; i < this.data.length; i++) {
			writeWord(dst, this.data[i]);
		}
		
		// dirty management
		this.dirty &= isDeltaSave; // hold re-save information consistent with persistence type
	}
	
	public void getLabelDataToWords(int[] lbl) {
		if (lbl.length < 8) {
			throw new IllegalArgumentException("label too short");
		}
		lbl[0] = this.nextRDA.getExternal();
		lbl[1] = this.prevRDA.getExternal();
		lbl[2] = 0;
		lbl[3] = this.nBytes;
		lbl[4] = this.filepage;
		lbl[5] = this.fid_used;
		lbl[6] = this.fid_kind;
		lbl[7] = this.fid_fileId;
	}
	
	public void setLabelDataFromWords(int[] lbl) {
		if (lbl.length < 8) {
			throw new IllegalArgumentException("label too short");
		}
		this.setNextRDA(lbl[0])
			.setPrevRDA(lbl[1])
			.setNBytes(lbl[3])
			.setFilepage(lbl[4]);
		if ((lbl[5] == 0xFFFF && lbl[6] != 0xFFFF) || (lbl[5] != 0xFFFF && lbl[6] == 0xFFFF)) {
			throw new RuntimeException("contradicting 'in-use' flags for disk page");
		}
		if (lbl[5] == 0xFFFF) {
			this.setUnused();
		} else {
			this.setFileId(lbl[6] == 0x8000, lbl[7]);
		}
	}
	
}
