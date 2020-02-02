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

/**
 * Reader for an Alto file, allowing to read the file data content starting
 * at the beginning of the file.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class FileReader {
	
	public static class EofException extends Exception {
		private static final long serialVersionUID = -3209137584130953232L;
	}
	
	private DiskPage currPage = null;
	private int totalFileBytes = 0;
	private int totalReadBytes = 0;
	private int currPageByte = 0;
	private int totalFileWords = 0;
	private int totalReadWords = 0;
	private int currPageWord = 0;

	public FileReader(DiskPage leaderPage) {
		RDA rda = leaderPage.getNextRDA();
		int fileBytes = 0;
		while(rda != RDA.NULL) {
			DiskPage page = DiskPage.forRDA(rda);
			fileBytes += page.getNBytes();
			rda = page.getNextRDA();
		}
		if (fileBytes > 0) {
			this.totalFileBytes = fileBytes;
			this.totalFileWords = fileBytes / 2;
			this.currPage = DiskPage.forRDA(leaderPage.getNextRDA());
		}
	}
	
	public boolean isAtEnd() {
		return (this.currPage == null);
	}
	
	public int nextWord() throws EofException {
		if (this.currPage == null) {
			throw new EofException();
		}
		int value = this.currPage.getWord(this.currPageWord);
		this.totalReadWords++;
		this.totalReadBytes = this.totalReadWords * 2;
		if (this.totalReadWords == this.totalFileWords) {
			this.currPage = null; // we're at end-of-file now
			return value;
		}
		this.currPageWord++;
		this.currPageByte = this.currPageWord * 2;
		while (this.currPageByte >= currPage.getNBytes()) {
			this.currPageWord = 0;
			this.currPageByte = 0;
			RDA rda = this.currPage.getNextRDA();
			if (rda == RDA.NULL) {
				// what??
				this.currPage = null;
				return value;
			}
			this.currPage = DiskPage.forRDA(rda);
		}
		return value;
	}
	
	public byte nextByte() throws EofException {
		if (this.currPage == null) {
			throw new EofException();
		}
		byte value = this.currPage.getByte(this.currPageByte);
		this.totalReadBytes++;
		this.totalReadWords = this.totalReadBytes / 2;
		if (this.totalReadBytes >= this.totalFileBytes) {
			this.currPage = null; // we're at end-of-file now
			return value;
		}
		this.currPageByte++;
		this.currPageWord = this.currPageByte / 2;
		while (this.currPageByte >= currPage.getNBytes()) {
			this.currPageWord = 0;
			this.currPageByte = 0;
			RDA rda = this.currPage.getNextRDA();
			if (rda == RDA.NULL) {
				// what??
				this.currPage = null;
				return value;
			}
			this.currPage = DiskPage.forRDA(rda);
		}
		return value;
	}
}
