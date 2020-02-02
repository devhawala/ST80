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

import java.util.HashMap;
import java.util.Map;

/**
 * Raw Disk Address (or disk/cyl/head/sector address) used in the addressing
 * of Alto disk pages.
 * <p>
 * This class manages the RDA singletons used for all page identifications,
 * allowing to compare disk addresses directly with {@code ==}. An RDA can be
 * retrieved from this class either by its linear disk address (the VDA, i.e.
 * virtual disk address) or by its external representation in the Alto disk image.
 * </p>
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class RDA {
	
	/*
	 * Alto disk constants
	 */
	
	public static final int DISK_PAGES = 4872; // one disk, unclear how to handle one file system composed of 2 disks
	
	private static final int SECTORS_PER_CYL = 12;
	private static final int HEADS_PER_CYL = 2;
	private static final int CYLINDERS = 203;
	
	/*
	 * RDA singleton management
	 */
	
	public static final RDA NULL;
	
	private static final RDA[] vda2rda;
	private static final Map<Integer,RDA> ext2rda;
	
	static {
		vda2rda = new RDA[DISK_PAGES];
		ext2rda = new HashMap<>();
		
		int pageCyl = 0;
		int pageHead = 0;
		int pageSect = 0;
		for (int vda = 0; vda < DISK_PAGES; vda++) {
			int ext = (pageCyl & 0x01FF) << 3
					| (pageHead & 0x0001) << 2
					| (pageSect & 0x000F) << 12; // implicitly: disk 0 (at bit 0x0002)
			
			RDA rda = new RDA(pageCyl, pageHead, pageSect, ext, vda);
			vda2rda[vda] = rda;
			ext2rda.put(ext, rda);
			
			pageSect++;
			if (pageSect == SECTORS_PER_CYL) {
				pageSect = 0;
				pageHead++;
				if (pageHead == HEADS_PER_CYL) {
					pageHead = 0;
					pageCyl++;
				}
			}
		}
		if (pageSect != 0 || pageHead != 0 || pageCyl != CYLINDERS) {
			System.err.printf("** warning: at end of initialize not at last sector, but: cyl=%d head=%d sect=%d\n", pageCyl, pageHead, pageSect);
		}
		
		NULL = vda2rda[0];
	}
	
	public static RDA forExternal(int extRepr) {
		RDA rda = ext2rda.get(extRepr & 0xFFFF);
		if (rda == null) {
			throw new RuntimeException(String.format("invalid RDA 0x%04X", extRepr));
		}
		return rda;
	}
	
	public static RDA forVDA(int vda) {
		if (vda < 0 || vda >= vda2rda.length) {
			throw new RuntimeException(String.format("invalid VDA %d ~ 0x%04X", vda, vda));
		}
		return vda2rda[vda];
	}
	
	/*
	 * instance items (diskNo implicitly 0, no second disk supported for now)
	 */
	
	private final int cyl;
	private final int head;
	private final int sector;
	private final int extRepr;
	private final int vda;
	
	private RDA(int cyl, int head, int sector, int extRepr, int vda) {
		this.cyl = cyl;
		this.head = head;
		this.sector = sector;
		this.extRepr = extRepr;
		this.vda = vda;
	}

	public int getCyl() { return this.cyl; }

	public int getHead() { return this.head; }

	public int getSector() { return this.sector; }

	public int getExternal() { return this.extRepr; }

	public int getVDA() { return this.vda; }
	
	@Override
	public String toString() {
		return String.format("RDA[c:%03d,h:%d,s:%02d]", this.cyl, this.head, this.sector);
	}

}
