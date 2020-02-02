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

import dev.hawala.st80vm.alto.FileReader.EofException;

/**
 * Representation of an entry in an directory of the Alto file system.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class FileEntry {
	
	private final int fileId;
	private final int leaderVDA;
	private final String filename;
	
	FileEntry(int id, int vda, String name) {
		this.fileId = id;
		this.leaderVDA = vda;
		this.filename = name;
	}
	
	FileEntry(FileReader r) throws EofException {
		// type of typelength already read
		int wordLen = r.nextByte() & 0x00FF;
		
		// serialNumber
		r.nextWord();
		this.fileId = r.nextWord() & 0xFFFF;
		
		// version
		r.nextWord();
		
		// blank
		r.nextWord();
		
		// leader RDA
		this.leaderVDA = r.nextWord() & 0xFFFF;
		
		// filename
		int nameLen = r.nextByte() & 0x00FF;
		byte[] bytes = new byte[nameLen - 1];
		for (int i = 0; i < nameLen - 1; i++) { // drop trailing dot ('.')
			bytes[i] = r.nextByte();
		}
		r.nextByte(); // the trailing dot
		if ((nameLen & 1) == 0) {
			// even length (including trailing dot) + length byte => odd number of bytes read so far
			r.nextByte(); // make it an even number of bytes to be on word boundary
		}
		this.filename = new String(bytes);
	}

	public int getFileId() { return this.fileId; }

	public int getLeaderVDA() { return this.leaderVDA; }

	public String getFilename() { return this.filename; }
	
	public LeaderPage getLeaderPage() {
		return (this.leaderVDA != 0) ? new LeaderPage(DiskPage.forVDA(this.leaderVDA)) : null;
	}
	
	public int getSizeInBytes() {
		if (this.leaderVDA == 0) {
			return -1;
		}
		DiskPage page = DiskPage.forVDA(this.leaderVDA);
		RDA rda;
		int byteCount = 0;
		while ((rda = page.getNextRDA()) != RDA.NULL) {
			page = DiskPage.forRDA(rda);
			byteCount += page.getNBytes();
		}
		return byteCount;
	}
	
}