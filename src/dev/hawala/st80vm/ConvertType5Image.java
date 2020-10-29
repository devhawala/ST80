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

package dev.hawala.st80vm;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversion program for reading a Xerox Smalltalk-80 type 5 image file (Stretch & LOOM)
 * to a type 1 image (without LOOM). This will only work if the original image fits into
 * the confined memory (48k objects, 16 segments with 64k heap memory).
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class ConvertType5Image {
	
	/*
	 * start and end of (image-file) memory areas for "real", "free" and "untouched" objects
	 */
	
	private static int heapFirstRealAddr = 0x7FFFFFFF;
	private static int heapFirstFreeAddr = 0x7FFFFFFF;
	private static int heapFirstUntouchedAddr = 0x7FFFFFFF;

	private static int heapLastRealAddr = -1;
	private static int heapLastFreeAddr = -1;
	private static int heapLastUntouchedAddr = -1;
	
	/*
	 * start positions for the true heap positions
	 */
	
	private static int heapRealBaseAddr = 0;
	private static int heapRealOtOffset = 0;
	private static int heapUntouchedBaseAddr = 0;
	private static int heapUntouchedOtOffset = 0;

	/**
	 * representation of a heap entry as read from the image file
	 */
	private static class OTEntry {
		public final int w1;
		public final int w2;
		
		public final int refCount;
		public final boolean inZeroCount;
		public final boolean inOverflow;
		public final int purposeBits;
		public final boolean untouched;
		public final int bank;
		public final int addressInBank;
		
		private final int rawHeapAddress;
		
		public final boolean isFreeOOP;
		public final boolean isRealOOP;
		public final int purposeError;
		
		private int heapAddrAdjust = 0; // correction to heap addresses for omitted heap pages in image file
		
		private int stretchHeapAddress = 0;
		
		public OTEntry(int otIdx, int w1, int w2) {
			this.w1 = w1;
			this.w2 = w2;
			
			this.refCount = (w2 >> 10) & 0x003F;
			this.inZeroCount = (w2 & 0x0200) != 0;
			this.inOverflow = (w2 & 0x0100) != 0;
			this.purposeBits = (w2 >> 5) & 0x0003;
			this.untouched = (w2 & 0x0010) != 0;
			this.bank = (w2 & 0x000F) | (((w2 & 0x0080) != 0) ? 0x0010 : 0);
			this.addressInBank = w1;
			
			this.rawHeapAddress = (this.bank << 16) | this.addressInBank;
			
			this.isFreeOOP = (this.purposeBits == 3);
			this.isRealOOP = (this.purposeBits == 0);
			this.purposeError = (this.isFreeOOP || this.isRealOOP) ? 0 : this.purposeBits;
			
			if (otIdx == 0 || this.rawHeapAddress == 0) {
				// ignored for heap access
			} else if (this.isFreeOOP) {
				heapFirstFreeAddr = Math.min(heapFirstFreeAddr, this.rawHeapAddress);
				heapLastFreeAddr = Math.max(heapLastFreeAddr, this.rawHeapAddress);
			} else if (this.untouched) {
				heapFirstUntouchedAddr = Math.min(heapFirstUntouchedAddr, this.rawHeapAddress);
				heapLastUntouchedAddr = Math.max(heapLastUntouchedAddr, this.rawHeapAddress);
			} else if (this.isRealOOP) {
				heapFirstRealAddr = Math.min(heapFirstRealAddr, this.rawHeapAddress);
				heapLastRealAddr = Math.max(heapLastRealAddr, this.rawHeapAddress);
			} else {
				System.out.printf("** OTEntry[%d] ( 0x%04X , 0x%04X ) : not (free|real|untouched) !!\n", otIdx, w1, w2);
			}
		}
		
		public void setAddressAdjust(int newAdjust) {
			this.heapAddrAdjust = newAdjust;
		}
		
		public int getHeapAddress() {
			if (this.untouched) {
				return this.rawHeapAddress - heapUntouchedOtOffset + heapUntouchedBaseAddr - heapAddrAdjust;
			}
			if (this.isRealOOP) {
				return this.rawHeapAddress - heapRealOtOffset + heapRealBaseAddr - heapAddrAdjust;
			}
			return 0;
		}
		
		public int getWord(int offset) {
			if (this.isRealOOP) {
				return rawHeap[this.getHeapAddress() + offset];
			}
			return 0;
		}
		
		public int getLength() { return this.getWord(1); }
		
		public int getStretchLength() {
			return this.getLength() - 1;
		}
		
		public void setStretchHeapAddr(int addr) {
			this.stretchHeapAddress = addr;
		}
		
		public int getWord0() {
			if (this.isFreeOOP) {
				return 0x0020;
			}
			int useCount = (this.refCount == 63) ? 255 : this.refCount; // adjust "permanent" status
			int deltaWord = this.getWord(0);
			boolean oddLength = (deltaWord & 0x0002) != 0;
			boolean pointerFields = (deltaWord & 0x0001) != 0;
			int w = ((useCount & 0xFF) << 8)
				  | (oddLength      ? 0x0080 : 0x0000)
				  | (pointerFields  ? 0x0040 : 0x0000)
				  | ((this.stretchHeapAddress >> 16) & 0x000F);
			return w;
		}
		
		public int getWord1() {
			return this.isFreeOOP ? 0 : this.stretchHeapAddress & 0xFFFF;
		}
		
	}
	
	// object table with linear addressing
	private static OTEntry[] ot = new OTEntry[48 * 1024];
	
	// the heap as loaded from the image file
	private static int[] rawHeap;
	private static int   realHeapLen = 0;
	private static int   heapPageCount = 0;

	public static void main(String[] args) throws IOException {
		String fnSrcImage = null;
		String fnTrgImage = null;
		
		for (String arg : args) {
			if ("-v".equals(arg)) {
				otVerbose = true;
			} else {
				if (fnSrcImage == null) {
					fnSrcImage = arg;
				} else 
					if (fnTrgImage == null) {
						fnTrgImage = arg;
					}
			}
		}
		
		if (fnSrcImage == null) {
			System.err.println("** missing source image filename");
			return;
		}
		if (fnTrgImage == null) {
			fnTrgImage = fnSrcImage + ".converted.im";
		}
		
		// read the input image file
		try ( FileInputStream fis = new FileInputStream(fnSrcImage) ) {

			// scan/skip header page
			int heapLenW1 = readWord(fis);
			int heapLenW2 = readWord(fis);
			int otLenW1 = readWord(fis);
			int otLenW2 = readWord(fis);
			int imType = readWord(fis);
			for (int i = 5; i < 256; i++) {
				readWord(fis);
			}
			
			// interpret header fields
			int heapLen = (((heapLenW1 << 16) | heapLenW2) & 0xFFFFFF00) + (((heapLenW2 & 0x00FF) != 0) ? 0x0100 : 0);
			int otCount = ((otLenW1 << 16) | otLenW2) / 2;
			logf("## image type: %d ; otCount: 0x%04X = %d ; maxHeap: 0x%06X = %d = %d pages\n\n",
				 imType, otCount, otCount, heapLen, heapLen, heapLen / 256);
			
			// stop if more ot entries than possible in pure stretch memory model
			if (otCount > ot.length) {
				logf("## image has more OT entries (%d) than supported by Smalltalk-80 image (%d), aborting...\n",
						otCount, ot.length);
				return;
			}
			
			// stop of not a type 5 image
			if (imType != 5) {
				logf("## not an type 5 Smalltalk-80 image, aborting...\n");
				return;
			}
			
			// load the object table
			for (int i = 0; i < otCount; i++) {
				int w1 = readWord(fis);
				int w2 = readWord(fis);
				ot[i] = new OTEntry(i, w1, w2);
			}
			logf("## heap bounds: firstReal = 0x%06X , firstFree = 0x%06X , firstUntouched = 0x%06X\n", heapFirstRealAddr, heapFirstFreeAddr, heapFirstUntouchedAddr);
			logf("## heap bounds:  lastReal = 0x%06X ,  lastFree = 0x%06X ,  lastUntouched = 0x%06X\n", heapLastRealAddr, heapLastFreeAddr, heapLastUntouchedAddr);
			for (int i = otCount; i < ot.length; i++) {
				ot[i] = new OTEntry(i, 0x0000, 0x0060); // free entry
			}
			
			// compute the real / untouched heap limits
			heapRealBaseAddr = 0;
			heapRealOtOffset = heapFirstRealAddr;
			heapUntouchedBaseAddr = (heapLastRealAddr - heapFirstRealAddr + 0x0000FF) & 0xFFFFFF00;
			heapUntouchedOtOffset = 0;
			
			logf("\n");
			logf("## heap layout:\n");
			logf("##   start real: 0x%06X -> start untouched: 0x%06X -> last ptr: 0x%06X\n", heapRealBaseAddr, heapUntouchedBaseAddr, heapUntouchedBaseAddr + heapLastUntouchedAddr);
			logf("##      ot base: 0x%06X            ot base: 0x%06X\n", heapRealOtOffset, heapUntouchedOtOffset);
			
			// load the heap
			int maxPages = 16 * 256; // 16 segments
			realHeapLen = fis.available() / 2;
			heapPageCount = (realHeapLen + 255) / 256;
			if (heapPageCount > maxPages) {
				logf("## image heap with %d = 0x%3X too large for pure Stretch memory model, aborting", heapPageCount, heapPageCount);
				return;
			}
			logf("## expecting heap with 0x%04X = %d pages for heapLen = 0x%06X = %d words\n", heapPageCount, heapPageCount, realHeapLen, realHeapLen);
			rawHeap = new int[0x1000 * 256]; // allocate the max heap space supported with non-LOOM Stretch memory model
			logf("## loading heap data ...");
			int loadPos = 0;
			try {
				while(true) {
					rawHeap[loadPos] = readWord(fis);
					loadPos++;
				}
			} catch (IOException ioe) {
				// ignored ... meaning eof
			}
			realHeapLen = loadPos;
			heapPageCount = (realHeapLen + 255) / 256;
			logf(" done, loaded: 0x%06X = %d words => %d pages\n", realHeapLen, realHeapLen, heapPageCount);
			int freeHeapSpace = rawHeap.length - realHeapLen;
			logf("## free heap: %d = 0x%06X heap words\n", freeHeapSpace, freeHeapSpace);
		}
		
		// ot entry type counts
		int realObjCount = 0;
		int freeObjCount = 0;
		int untouchedObjCount = 0;
		
		// real/untouched objects: heap-address => ot-index
		Map<Integer,Integer> realObjects = new HashMap<>();
		Map<Integer,Integer> untouchedObjects = new HashMap<>();
		
		// count and record object types in heap
		for (int i = 1; i < ot.length; i++) {
			OTEntry o = ot[i];
			if (o.isFreeOOP) {
				freeObjCount++;
			} else if (o.untouched) {
				untouchedObjCount++;
				untouchedObjects.put(o.rawHeapAddress, i);
			} else if (o.isRealOOP && o.getHeapAddress() >= 0) {
				realObjCount++;
				realObjects.put(o.rawHeapAddress, i);
			}
		}
		logf("\n## ot counts : real = %d , free = %d , untouched = %d - total = %d of %d\n",
			 realObjCount, freeObjCount, untouchedObjCount,
			 realObjCount + freeObjCount + untouchedObjCount, ot.length);
		
		// compute/set heap memory adjustments for real objects to skip page gaps at segment changes
		{
			List<Integer> realAddresses = new ArrayList<>(realObjects.keySet());
			realAddresses.sort( (l,r) -> l.compareTo(r) );
			int lastOtIndex = -1;
			int currHeapPos = heapRealBaseAddr;
			int currAdjust = 0;
			vlogf("\n## real objects:\n");
			for (int i = 0; i < realAddresses.size()-1; i++) {
				int otIndex = realObjects.get(realAddresses.get(i));
				ot[otIndex].setAddressAdjust(currAdjust);
				int sizeByDiff = realAddresses.get(i+1) - realAddresses.get(i);
				int sizeByOT = ot[otIndex].getLength();
				int sizeInHeap = rawHeap[currHeapPos + 1];
				vlogf("   heapAddr = 0x%06X - length = %5d -- ot-index: %5d - %s\n",
					realAddresses.get(i),
					sizeByDiff,
					otIndex,
					(sizeByDiff == sizeInHeap) ? "ok" : "!! size in heap: " + sizeInHeap + " ------------ !!"
					);
				if (sizeInHeap != sizeByOT) {
					vlogf("    -- sizeByOT = %d != sizeInHeap = %d\n", sizeByOT, sizeInHeap);
				}
				if (sizeByDiff > sizeInHeap) {
					int oversizeInAddress = sizeByDiff - sizeInHeap;
					int adjust = oversizeInAddress & 0xFFFF00;
					currAdjust += adjust;
					currHeapPos += sizeByDiff - adjust;
					if (adjust > 0) {
						vlogf("  ... new adjust offset: %d\n", currAdjust);
					}
				} else {
					currHeapPos += sizeByDiff;
				}
				lastOtIndex = otIndex;
			}
			int otIndex = realObjects.get(realAddresses.get(realAddresses.size()-1));
			ot[otIndex].setAddressAdjust(currAdjust);
			int sizeByOT = ot[otIndex].getLength();
			int sizeInHeap = rawHeap[currHeapPos + 1];
			if (sizeInHeap != sizeByOT) {
				vlogf(" ... final(ot-for-last-real-heap-address) -- sizeByOT = %d != sizeInHeap = %d\n", sizeByOT, sizeInHeap);
			}
		}
		
		// compute/set heap memory adjustments for untouched objects to skip page gaps at segment changes
		{
			List<Integer> untouchedAddresses = new ArrayList<>(untouchedObjects.keySet());
			untouchedAddresses.sort( (l,r) -> l.compareTo(r) );
			int lastOtIndex = -1;
			int currHeapPos = heapUntouchedBaseAddr;
			int currAdjust = 0;
			vlogf("\n## untouched objects:\n");
			for (int i = 0; i < untouchedAddresses.size()-1; i++) {
				int otIndex = untouchedObjects.get(untouchedAddresses.get(i));
				ot[otIndex].setAddressAdjust(currAdjust);
				int sizeByDiff = untouchedAddresses.get(i+1) - untouchedAddresses.get(i);
				int sizeByOT = ot[otIndex].getLength();
				int sizeInHeap = rawHeap[currHeapPos + 1];
				vlogf("   heapAddr = 0x%06X - length = %5d -- ot-index: %5d - %s\n",
					untouchedAddresses.get(i),
					sizeByDiff,
					otIndex,
					(sizeByDiff == sizeInHeap) ? "ok" : "!! size in heap: " + sizeInHeap + " ------------ !!"
					);
				if (sizeInHeap != sizeByOT) {
					vlogf("    -- sizeByOT = %d != sizeInHeap = %d\n", sizeByOT, sizeInHeap);
				}
				if (sizeByDiff > sizeInHeap) {
					int oversizeInAddress = sizeByDiff - sizeInHeap;
					int adjust = oversizeInAddress & 0xFFFF00;
					currAdjust += adjust;
					currHeapPos += sizeByDiff - adjust;
					if (adjust > 0) {
						vlogf("  ... new adjust offset: %d\n", currAdjust);
					}
				} else {
					currHeapPos += sizeByDiff;
				}
				lastOtIndex = otIndex;
			}
			int otIndex = untouchedObjects.get(untouchedAddresses.get(untouchedAddresses.size()-1));
			ot[otIndex].setAddressAdjust(currAdjust);
			int sizeByOT = ot[otIndex].getLength();
			int sizeInHeap = rawHeap[currHeapPos + 1];
			if (sizeInHeap != sizeByOT) {
				vlogf(" ... final(ot-for-last-untouched-heap-address) -- sizeByOT = %d != sizeInHeap = %d\n", sizeByOT, sizeInHeap);
			}
		}
		
		// count the required words for the new heap
		int stretchHeapLength = 0;
		for (int i = 1; i < ot.length; i++) {
			OTEntry o = ot[i];
			if (!o.isFreeOOP) {
				stretchHeapLength += o.getStretchLength();
			}
		}
		int stretchHeapFree = rawHeap.length - stretchHeapLength;
		
		logf("\n## producing new image\n", stretchHeapLength, stretchHeapLength);
		logf("##   new heap size: %d = 0x%06X words\n", stretchHeapLength, stretchHeapLength);
		logf("##   new heap free: %d = 0x%06X words\n", stretchHeapFree, stretchHeapFree);
		
		// produce the converted stretch memory model image
		try ( FileOutputStream fos = new FileOutputStream(fnTrgImage) ) {
			
			// metadata page
			writeWord(fos, (stretchHeapLength >> 16) & 0xFFFF); // heap used words, upper word
			writeWord(fos, stretchHeapLength & 0xFFFF);         // heap used words, lower word
			writeWord(fos, 0x0001);                             // object-table word length, upper word
			writeWord(fos, 0x8000);                             // object-table word length, lower word
			writeWord(fos, 0x0001);                             // image type, 1 = stretch memory model (without LOOM)
			for (int i = 0; i < 251; i++) { writeWord(fos, 0); }// fill up to page length
			
			// write the new heap: process all non-free objects
			int newHeapPos = 0;
			for (int i = 1; i < ot.length; i++) {
				OTEntry o = ot[i];
				if (o.isFreeOOP) { continue; }
				
				// remember where the object data starts in the new heap
				o.setStretchHeapAddr(newHeapPos);
				
				// write the length word
				int newLength = o.getStretchLength();
				writeWord(fos, newLength);
				newHeapPos++;
				
				// write the object body data
				int writeCount = newLength - 1; // the length word is already written
				int srcPos = o.getHeapAddress() + 2; // skip the status and length words of the object in old heap
				while(writeCount > 0) {
					writeWord(fos, rawHeap[srcPos++]);
					writeCount--;
					newHeapPos++;
				}
			}
			
			// fill up the last heap page written
			int lastPageUsedWords = newHeapPos & 0x0000FF;
			if (lastPageUsedWords > 0) {
				for (int i = 0; i < (256 - lastPageUsedWords); i++) {
					writeWord(fos, 0);
				}
			}
			
			// write the object table
			writeWord(fos, 0); // invalid object, upper word
			writeWord(fos, 0); // invalid object, lower word
			for (int i = 1; i < ot.length; i++) {
				OTEntry o = ot[i];
				writeWord(fos, o.getWord0());
				writeWord(fos, o.getWord1());
			}
		}
		
		logf("## new image written: %s\n", fnTrgImage);
	}
	
	/*
	 * utilities
	 */
	
	private static void logf(String pattern, Object... args) {
		System.out.printf(pattern, args);
	}
	
	private static boolean otVerbose = false;
	
	private static void vlogf(String pattern, Object... args) {
		if (otVerbose) {
			System.out.printf(pattern, args);
		}
	}
	
	private static int readWord(InputStream stream) throws IOException {
		int b1 = stream.read();
		if (b1 < 0) { throw new IOException("EOF"); }
		int b2 = stream.read();
		if (b2 < 0) { throw new IOException("EOF"); }
		return (b1 << 8) | b2;
		
	}
	
	private static void writeWord(OutputStream os, int w) throws IOException {
		os.write((w >> 8) & 0x00FF);
		os.write(w & 0x00FF);
	}

}