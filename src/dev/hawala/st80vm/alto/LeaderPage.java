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

import java.util.Date;

/**
 * Representation of the leader page of an Alto file, holding the metadata
 * for the file (this page is the first page of the file but not part of the
 * data content) 
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class LeaderPage {

	private final DiskPage page;
	
	public LeaderPage(DiskPage p) {
		this.page = p;
	}
	
	public DiskPage getPage() {
		return this.page;
	}
	
	/*
	 * file dates
	 */
	
	// 01.01.1901 -> 01.01.1970: 69 years having 17 leap years
	private static final long SMALLTALK_TO_UNIX_TIME_DELTA_SECONDS = ((69L * 365L) + 17L) * 86400L;
	
	private Date getTime(int at) {
		long altoTime = (((long)this.page.getWord(at)) << 16) | (long)this.page.getWord(at + 1); // seconds since 01.01.1901
		long unixTime = altoTime - SMALLTALK_TO_UNIX_TIME_DELTA_SECONDS;
		return new Date(unixTime * 1000);
	}
	
	private void setTime(int at, Date dt) {
		long altoTime = (dt.getTime() / 1000) + SMALLTALK_TO_UNIX_TIME_DELTA_SECONDS;
		this.page.putWord(at, (int)((altoTime >> 16) & 0xFFFF));
		this.page.putWord(at + 1, (int)(altoTime & 0xFFFF));
	}
	
	public Date getCreated() {
		return this.getTime(0);
	}
	
	public LeaderPage setCreated(Date dt) {
		this.setTime(0, dt);
		return this;
	}
	
	public Date getWritten() {
		return this.getTime(2);
	}
	
	public LeaderPage setWritten(Date dt) {
		this.setTime(2, dt);
		return this;
	}
	
	public Date getRead() {
		return this.getTime(4);
	}
	
	public LeaderPage setRead(Date dt) {
		this.setTime(4, dt);
		return this;
	}
	
	/*
	 * filename
	 */
	
	public String getFilename() {
		int len = this.page.getByte(12) & 0x00FF;
		byte[] bytes = new byte[len - 1];
		for (int i = 1; i < len; i++) { // drop trailing dot ('.')
			bytes[i - 1] = this.page.getByte(12 + i);
		}
		String name = new String(bytes);
		return name;
	}
	
	public LeaderPage setFilename(String name) {
		// max. length is 38: 40 bytes for filename field - 1 byte for length - 1 byte for '.'
		int len = Math.min(38, name.length());
		int pos = 12;
		this.page.putByte(pos++, (byte)(len + 1)); // +1 for the trailing dot ('.')
		for (int i = 0; i < len; i++) {
			int c = (int)name.charAt(i);
			byte b = (c > 32) && (c < 128) ? (byte)c : (int)'_';
			this.page.putByte(pos++, b);
		}
		this.page.putByte(pos++, (byte)'.');
		while(pos < 52) {
			this.page.putByte(pos++, (byte)0);
		}
		return this;
	}
	
	/*
	 * dirFpHint
	 * (serialNumber[0] implicitly 0x8000)
	 * (version implicitly 1)
	 */
	
	public int getDirFpHint_fileId() {
		return this.page.getWord(0xF9);
	}
	
	public int getDirFpHint_leaderVDA() {
		return this.page.getWord(0xFC);
	}
	
	public LeaderPage setDirFpHint(int fileId, int leaderVDA) {
		this.page.putWord(0xF8, 0x8000);   // serialNumber[0] directory
		this.page.putWord(0xF9, fileId);   // serialNumber[1] fileId
		this.page.putWord(0xFA, 0x0001);   // version
		this.page.putWord(0xFB, 0x0000);   // blank
		this.page.putWord(0xFC, leaderVDA);// leaderVDA
		return this;
	}
	
	/*
	 * lastPageHint
	 */
	
	public int getLastPageHint_vda() {
		return this.page.getWord(0xFD);
	}
	
	public int getLastPageHint_pageNumber() {
		return this.page.getWord(0xFE);
	}
	
	public int getLastPageHint_charPos() {
		return this.page.getWord(0xFF);
	}
	
	public LeaderPage setLastPageHint_charPos(int charPos) {
		this.page.putWord(0xFF, charPos);
		return this;
	}
	
	public LeaderPage setLastPageHint(int vda, int pageNumber, int charPos) {
		this.page.putWord(0xFD, vda);
		this.page.putWord(0xFE, pageNumber);
		this.page.putWord(0xFF, charPos);
		return this;
	}
	
	/*
	 * initializing
	 */
	
	public LeaderPage initialize(String filename, int dirFileId, int dirLeaderVDA) {
		page.setFilepage(0)
			.setNBytes(DiskPage.BYTELEN_DATA);
		for (int i = 0; i < DiskPage.WORDLEN_DATA; i++) {
			page.putWord(i, 0);
		}
		page.putWord(0xF6, 0x1AD2); // proplength (byte: 42) , propbegin (byte: 210)
		
		Date now = new Date();
		this.setCreated(now);
		this.setWritten(now);
		this.setRead(new Date(0)); // ~ never
		
		this.setFilename(filename);
		this.setDirFpHint(dirFileId, dirLeaderVDA);
		this.setLastPageHint(0, 0, 0);
		
		return this;
	}
	
}
