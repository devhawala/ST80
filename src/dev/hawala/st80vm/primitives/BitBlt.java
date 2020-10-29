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

package dev.hawala.st80vm.primitives;

import static dev.hawala.st80vm.interpreter.InterpreterBase.popStack;
import static dev.hawala.st80vm.interpreter.InterpreterBase.push;
import static dev.hawala.st80vm.interpreter.InterpreterBase.stackTop;
import static dev.hawala.st80vm.interpreter.InterpreterBase.success;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.interpreter.Interpreter;
import dev.hawala.st80vm.interpreter.Interpreter.Primitive;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.OTEntry;
import dev.hawala.st80vm.memory.Well;
import dev.hawala.st80vm.ui.iDisplayPane;

/**
 * Implementation of primitives for the class BitBlt and its subclass CharacterScanner.
 * <p>These are:
 * </p>
 * <ul>
 * <li>{@code BitBlt.copyBits} (required, closely based on Bluebook pp. 355 "Simulation of BitBlt", with bugfixes)</li>
 * <li>{@code BitBlt.drawLoopX} (optional, based on the default implementation in file Smalltalk-80.sources)</li>
 * <li>{@code BitBlt.drawCircle} (optional, DV6 only, based on the default implementation in file Smalltalk-80.sources)</li>
 * <li>{@code CharacterScanner.scanCharacters} (optional, based on the default implementation in file ST80-DV6.sources)</li>
 * </ul>
 * <p>(the optional primitives are implemented to speed up the general and UI performance of the system)</p>
 * <p>
 * This class also implements the connection to the "real" display provided by a Java-Swing pane. 
 * </p>
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class BitBlt {
	
	private BitBlt() { }
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> BitBlt." + name + "\n"); } }
	private static void logf(String format, Object... args) { if (Config.TRACE_BITBLT) { System.out.printf(format, args); } }
	
	/*
	 * generic utilities for number-pointer <-> value conversion
	 * (at least more general than integerValueOf/integerObjectOf, allowing to handle 32 bit integers and floats)
	 */
	
	private static int pointer2int(int integerPointer) {
		if (Memory.isIntegerObject(integerPointer)) {
			return Memory.integerValueOf(integerPointer);
		}
		int clsPointer = Memory.fetchClassOf(integerPointer);
		
		if (clsPointer == Well.known().ClassFloatPointer) {
			int floatRepr = (Memory.fetchWord(0, integerPointer) << 16) | Memory.fetchWord(1, integerPointer);
			float value = Float.intBitsToFloat(floatRepr);
			return (int)value;
		}
		
		int len = Memory.fetchByteLengthOf(integerPointer);
		if ( (clsPointer != Well.known().ClassLargePositivelntegerPointer && clsPointer != Well.known().ClassLargeNegativeIntegerPointer) || len > 4) {
			Interpreter.primitiveFail();
			return 0x7FFFFFFF; // we must return something, but what? (last Integer => out-of-bounds when accessing anything in virtual memory!)
		}
		int value = 0;
		if (len > 0) { value = Memory.fetchByte(0, integerPointer); }
		if (len > 1) { value |= Memory.fetchByte(1, integerPointer) << 8; }
		if (len > 2) { value |= Memory.fetchByte(2, integerPointer) << 16; }
		if (len > 3) { value |= Memory.fetchByte(3, integerPointer) << 24; }
		if (clsPointer == Well.known().ClassLargePositivelntegerPointer) {
			return value;
		} else if (value < 0) {
			return value;
		} else {
			return -value;
		}
	}
	
	private static int int2pointer(int integerValue) {
		if (integerValue < 0xFFFFL && Memory.isIntegerValue((int)integerValue)) {
			return Memory.integerObjectOf((int)integerValue);
		}
		
		final int clsPtr;
		if (integerValue >= 0) {
			clsPtr = Well.known().ClassLargePositivelntegerPointer;
		} else {
			clsPtr = Well.known().ClassLargeNegativeIntegerPointer;
			logf("\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ int2pointer(): creating LargeNegativeIntegerPointer for: %d\n", integerValue);
		}
		integerValue = Math.abs(integerValue); // this assumes that negatives are stored as positives
		
		int bytes = (integerValue > 0x00FFFFFFL) ? 4 :(integerValue > 0x0000FFFFL) ? 3 : 2;
		
		int newLargeInteger = Memory.instantiateClassWithBytes(clsPtr, bytes);
		Memory.storeByte(0, newLargeInteger, (int)(integerValue & 0xFF));
		Memory.storeByte(1, newLargeInteger, (int)((integerValue >> 8) & 0xFF));
		if (bytes > 2) { Memory.storeByte(2, newLargeInteger, (int)((integerValue >> 16) & 0xFF)); }
		if (bytes > 3) { Memory.storeByte(3, newLargeInteger, (int)((integerValue >> 24) & 0xFF)); }
		return newLargeInteger;
	}
	
	/*
	 * ************************************************************ display interface
	 */
	
	private static int displayScreenPointer = -1;
	private static int displayWidth = -1;   // in pixels
	private static int displayHeight = -1;  // in pixels
	
	private static OTEntry display = null;
	private static int displayRaster = -1;  // number of words per line
	private static int redisplayTop = 0;    // hint: first line to redisplay (0-based), incl.
	private static int redisplayBottom = 0; // hint: last line to redisplay (0-based), incl.
	
	private static iDisplayPane displayPane = null;
	
	public static void registerDisplayPane(iDisplayPane pane) {
		displayPane = pane;
	}
	
	public static synchronized void registerDisplayScreen(int ds) {
		displayScreenPointer = ds;
		display = Memory.ot(displayScreenPointer);
		
		int bitsPointer = display.fetchPointerAt(Well.known().FormBitsIndex);
		OTEntry bits = Memory.ot(bitsPointer);
		
		displayWidth = pointer2int(display.fetchPointerAt(Well.known().FormWidthIndex));
		displayHeight = pointer2int(display.fetchPointerAt(Well.known().FormHeightIndex));
		displayRaster = ((displayWidth - 1) / 16) + 1;

		logf("--\n--registerDisplayScreen():\n");
		logf("-- display.........: %s %s\n", display, (display != null) ? Memory.getClassName(display.getClassOOP()) : "");
		logf("-- displayBits.....: %s %s\n", bits, (bits != null) ? Memory.getClassName(bits.getClassOOP()) : "");
		logf("-- displayWidth....: %d\n", displayWidth);
		logf("-- displayRaster...: %d\n", displayRaster);
		logf("-- displayHeight...: %d\n", displayHeight);
		logf("--\n");
		
		// set refresh range to "everything"
		redisplayTop = 0;
		redisplayBottom = displayHeight - 1;
	}
	
	private static synchronized void checkDisplayChange(int formPointer, int dy, int h) {
		if (formPointer != displayScreenPointer) { return; }
		if (dy < redisplayTop) {
			redisplayTop = Math.max(dy, 0);
		}
		int lastLine = dy + h; // - 1;
		if (lastLine > redisplayBottom) {
			redisplayBottom = Math.min(lastLine,  displayHeight - 1);
		}
	}
	
	public static synchronized void refreshDisplay() {
		// check if connected to a display screen
		if (displayPane == null || display == null) {
			return;
		}
		
		// copy changed scan lines to the display screen
		displayPane.copyDisplayContent(
				Memory.getHeapMem(),
				Memory.ot(display.fetchPointerAt(Well.known().FormBitsIndex)).address() + 2, // cannot be cached, as address may change due to memory compaction!
				displayWidth,
				displayRaster,
				displayHeight,
				Config.OPTIMIZE_SCREEN_REFRESH ? redisplayTop : 0,
				Config.OPTIMIZE_SCREEN_REFRESH ? redisplayBottom : displayHeight - 1
				);
		

		// reset to "nothing changed so far"
		redisplayTop = displayHeight - 1;
		redisplayBottom = 0;
	}
	
	/*
	 * ************************************************************ class BitBlt
	 */
	
	
	/*
	 * field indices
	 */

	private static final int BitBlt_DestForm_Index = 0;
	private static final int BitBlt_SourceForm_Index = 1;
	private static final int BitBlt_HalftoneForm_Index = 2;
	private static final int BitBlt_CombinationRule_Index = 3;
	private static final int BitBlt_DestX_Index = 4;
	private static final int BitBlt_DestY_Index = 5;
	private static final int BitBlt_Width_Index = 6;
	private static final int BitBlt_Height_Index = 7;
	private static final int BitBlt_SourceX_Index = 8;
	private static final int BitBlt_SourceY_Index = 9;
	private static final int BitBlt_ClipX_Index = 10;
	private static final int BitBlt_ClipY_Index = 11;
	private static final int BitBlt_ClipWidth_Index = 12;
	private static final int BitBlt_ClipHeight_Index = 13;
	
	/*
	 * processing variables for a single BitBlt.copyBits
	 * (assuming that primitives are NEVER executed concurrently)
	 */
	
	// BitBlt instance variables (the int-values already are true integers, not SmallInteger-oops)
	private static OTEntry destForm;
	private static OTEntry sourceForm;
	private static OTEntry halftoneForm;
	private static int combinationRule;
	private static int destX;
	private static int destY;
	private static int width;
	private static int height;
	private static int sourceX;
	private static int sourceY;
	private static int clipX;
	private static int clipY;
	private static int clipWidth;
	private static int clipHeight;
	
	// work variables
	private static OTEntry sourceBits;
	private static int sourceRaster;
	private static OTEntry destBits;
	private static int destRaster;
	private static OTEntry halftoneBits;
	private static int skew;
	private static int skewMask;
	private static int startBits;
	private static int mask1;
	private static int endBits;
	private static int mask2;
	private static boolean preload;
	private static int nWords;
	private static int hDir;
	private static int vDir;
	private static int sourceIndex;
	private static int sourceDelta;
	private static int destIndex;
	private static int destDelta;
	private static int sx;
	private static int sy;
	private static int dx;
	private static int dy;
	private static int w;
	private static int h;
	
	private static int sourceForm_width;
	private static int sourceForm_height;
	
	// bit masks
	private static final int[] RightMasks = {
		0x0000 ,
		0x0001 , 0x0003 , 0x0007 , 0x000F ,
		0x001F , 0x003F , 0x007F , 0x00FF ,
		0x01FF , 0x03FF , 0x07FF , 0x0FFF ,
		0x1FFF , 0x3FFF , 0x7FFF , 0xFFFF
	};
	
	private static final int AllOnes = 0xFFFF;
	
	/*
	 * processing of BitBlt.copyBits
	 */
	
	public static final Primitive primitiveCopyBits = () -> { w("primitiveCopyBits");
		fetchBitBltVariables();
		if (destForm == null) { return true; } // no target bitmap
		clipRange();
		if (w <= 0 || h <= 0) { return true; } // empty area to copy
		computeMasks();
		checkOverlap();
		calculateOffsets();
		copyLoop();
		
		// self was left on the stack by fetchBitBltVariables()
		return true;
	};
	
	private static void fetchBitBltVariables() {
		int nilPointer = Well.known().NilPointer;
		
		int bitBltOop = Interpreter.popStack();
		OTEntry bitBlt = Memory.ot(bitBltOop); // we assume the primitive is really attached only to a BitBlt message
		
		int oop = bitBlt.fetchPointerAt(BitBlt_DestForm_Index);
		destForm = (oop == nilPointer) ? null : Memory.ot(oop);
		oop = bitBlt.fetchPointerAt(BitBlt_SourceForm_Index);
		sourceForm = (oop == nilPointer) ? null : Memory.ot(oop);
		oop = bitBlt.fetchPointerAt(BitBlt_HalftoneForm_Index);
		halftoneForm = (oop == nilPointer) ? null : Memory.ot(oop);
		combinationRule = pointer2int(bitBlt.fetchPointerAt(BitBlt_CombinationRule_Index));
		destX = pointer2int(bitBlt.fetchPointerAt(BitBlt_DestX_Index));
		destY = pointer2int(bitBlt.fetchPointerAt(BitBlt_DestY_Index));
		width = pointer2int(bitBlt.fetchPointerAt(BitBlt_Width_Index));
		height = pointer2int(bitBlt.fetchPointerAt(BitBlt_Height_Index));
		sourceX = pointer2int(bitBlt.fetchPointerAt(BitBlt_SourceX_Index));
		sourceY = pointer2int(bitBlt.fetchPointerAt(BitBlt_SourceY_Index));
		clipX = pointer2int(bitBlt.fetchPointerAt(BitBlt_ClipX_Index));
		clipY = pointer2int(bitBlt.fetchPointerAt(BitBlt_ClipY_Index));
		clipWidth = pointer2int(bitBlt.fetchPointerAt(BitBlt_ClipWidth_Index));
		clipHeight = pointer2int(bitBlt.fetchPointerAt(BitBlt_ClipHeight_Index));
		
		sourceForm_width = (sourceForm != null) ? pointer2int(sourceForm.fetchPointerAt(Well.known().FormWidthIndex)) : 0x7FFFFFF0;
		sourceForm_height = (sourceForm != null) ? pointer2int(sourceForm.fetchPointerAt(Well.known().FormHeightIndex)) : 0x7FFFFFF0;

		Interpreter.unPop(1); // the primitive will ultimately return the receiver (self)
		
		if (Config.TRACE_BITBLT) {
			logf("-> bitblt members:\n");
			logf("  - destForm.........: %s\n", destForm);		
			logf("  - sourceForm.......: %s\n", sourceForm);		
			logf("  - halftoneForm.....: %s\n", halftoneForm);
			logf("  - combinationRule..: %d\n", combinationRule);
			logf("  - destX............: %d\n", destX);
			logf("  - destY............: %d\n", destY);
			logf("  - width............: %d\n", width);
			logf("  - height...........: %d\n", height);
			logf("  - sourceX..........: %d\n", sourceX);
			logf("  - sourceY..........: %d\n", sourceY);
			logf("  - clipX............: %d\n", clipX);
			logf("  - clipY............: %d\n", clipY);
			logf("  - clipWidth........: %d\n", clipWidth);
			logf("  - clipHeight.......: %d\n", clipHeight);
		}
	}
	
	private static void clipRange() {
		// implementation-specific extension to Bluebook:
		// check the clip region against the target and reduce the clip region or abort bitblt as appropriate 
		if (clipX < 0) {
			clipWidth += clipX;
			clipX = 0;
		}
		if (clipWidth <= 0) {
			w = -1;
			h = -1;
			logf("-> clip area outside destForm (clipX+clipWidth <= 0)\n");
			return;
		}
		if (clipY < 0) {
			clipHeight += clipY;
			clipY = 0;
		}
		if (clipHeight <= 0) {
			w = -1;
			h = -1;
			logf("-> clip area outside destForm (clipY+clipHeight <= 0)\n");
			return;
		}
		int destWidth = pointer2int(destForm.fetchPointerAt(Well.known().FormWidthIndex));
		int destHeight = pointer2int(destForm.fetchPointerAt(Well.known().FormHeightIndex));
		if (clipX >= destWidth) {
			w = -1;
			h = -1;
			logf("-> clip area outside destForm (clipX >= destWidth)\n");
			return;
		}
		if (clipY >= destHeight) {
			w = -1;
			h = -1;
			logf("-> clip area outside destForm (clipY >= destHeight)\n");
			return;
		}
		if ((clipX + clipWidth) >= destWidth) {
			clipWidth = (destWidth - clipX);
			logf("-> restricted clip width ((clipX + clipWidth) >= destWidth) to: %d\n", clipWidth);
		}
		if ((clipY + clipHeight) >= destHeight) {
			clipHeight = (destHeight - clipY);
			logf("-> restricted clip height ((clipY + clipHeight) >= destHeight) to: %d\n", clipHeight);
		}
		// back to Bluebook

		// clip and adjust destination&source origin and extent appropriately
		
		// first in x
		if (destX >= clipX) {
			sx = sourceX;
			dx = destX;
			w = width;
		} else {
			sx = sourceX + (clipX - destX);
			w = width - (clipX - destX);
			dx = clipX;
		}
		if ((dx + w) > (clipX + clipWidth)) {
			w = w - ((dx + w) - (clipX + clipWidth));
		}
		
		// then in y
		if (destY >= clipY) {
			sy = sourceY;
			dy = destY;
			h = height;
		} else {
			sy = sourceY + (clipY - destY);
			h = height - (clipY - destY);
			dy = clipY;
		}
		if ((dy + h) > (clipY + clipHeight)) {
			h = h - ((dy + h) - (clipY + clipHeight));
		}
		
		// re-adjust for source rectangle
		if (sx < 0) {
			dx = dx - sx;
			w = w + sx;
			sx = 0;
		}
		if ((sx + w) > sourceForm_width) {
			w = w - (sx + w - sourceForm_width);
		}
		if (sy < 0) {
			dy = dy - sy;
			h = h + sy;
			sy = 0;
		}
		if ((sy + h) > sourceForm_height) {
			h = h - (sy + h - sourceForm_height);
		}
	}
	
	private static void computeMasks() {
		// calculate skew and edge masks
		destBits = Memory.ot(destForm.fetchPointerAt(Well.known().FormBitsIndex));
		destRaster = ((pointer2int(destForm.fetchPointerAt(Well.known().FormWidthIndex)) - 1) / 16) + 1;
		sourceBits = (sourceForm != null) ? Memory.ot(sourceForm.fetchPointerAt(Well.known().FormBitsIndex)) : null;
		sourceRaster = (sourceForm != null) ? ((pointer2int(sourceForm.fetchPointerAt(Well.known().FormWidthIndex)) - 1) / 16) + 1 : 0;
		halftoneBits = (halftoneForm != null) ? Memory.ot(halftoneForm.fetchPointerAt(Well.known().FormBitsIndex)) : null;
		skew = (sx - dx) & 0x000F; // how many bits source gets skewed to right
		startBits = 16 - (dx & 0x000F); // how many bits in first word
		mask1 = RightMasks[startBits]; // Java arrays start at 0, so no: + 1
		endBits = 15 - ((dx + w - 1) & 0x000F); // how many bits in last word
		mask2 = RightMasks[endBits] ^ 0xFFFF; // Java arrays start at 0, so no: + 1
		skewMask = (skew == 0) ? 0 : RightMasks[16 - skew];
		
		// determine number of words stored per line; merge masks if necessary
		if (w <= startBits) { // wrong in Bluebook...: < is not sufficient (if same, the next source word will also be copied => additional 16 bits copied from source)
			mask1 = mask1 & mask2;
			mask2 = 0;
			nWords = 1;
		} else {
			nWords = ((w - startBits - 1) / 16) + 2;
		}
	}
	
	private static void checkOverlap() {
		// implementation-specific: check for modification of display-screen
		checkDisplayChange(destForm.objectPointer(), dy, h);
		// back to Bluebook
		
		// check for possible overlap of source and destination
		hDir = vDir = 1; // defaults for no overlap
		if (sourceForm == destForm && dy >= sy) {
			if (dy > sy) {
				// have to start at bottom
				vDir = -1;
				sy = sy + h - 1;
				dy = dy + h - 1;
			} else if (dx > sx) {
				// y's are equal, but x's are backward
				hDir = -1;
				// start at right
				sx = sx + w - 1;
				dx = dx + w - 1;
				// and fix up masks
				skewMask = skewMask ^ 0xFFFF;
				int t = mask1;
				mask1 = mask2;
				mask2 = t;
			}
		}
	}
	
	private static void calculateOffsets() {
		// check if need to preload buffer (i.e., two words of source needed for first word of destination)
		preload = sourceForm != null && skew != 0 && skew <= (sx & 0x000F);
		if (hDir < 0) { preload = (preload == false); }
		
		// calculate starting offsets
		sourceIndex = (sy * sourceRaster) + (sx / 16);
		destIndex = (dy * destRaster) + (dx / 16);
		
		// calculate increments from end of 1 line to start of next
		sourceDelta = (sourceRaster * vDir) - ((nWords + (preload ? 1 : 0)) * hDir); // (sourceRaster * vDir) - (nWords + ((preload ? 1 : 0) * hDir));
		destDelta = (destRaster * vDir) - (nWords * hDir);
	}
	
	@FunctionalInterface
	private interface Combiner {
		int merge(int sourceWord, int destinationWord);
	}
	
	private static final Combiner[] rules = {
		/*  0 */ (s,d) -> 0,
		/*  1 */ (s,d) -> s & d,
		/*  2 */ (s,d) -> s & (d ^ 0xFFFF),
		/*  3 */ (s,d) -> s,
		/*  4 */ (s,d) -> (s ^ 0xFFFF) & d,
		/*  5 */ (s,d) -> d,
		/*  6 */ (s,d) -> s ^ d,
		/*  7 */ (s,d) -> s | d,
		/*  8 */ (s,d) -> (s ^ 0xFFFF) & (d ^ 0xFFFF),
		/*  9 */ (s,d) -> (s ^ 0xFFFF) ^ d,
		/* 10 */ (s,d) -> d ^ 0xFFFF,
		/* 11 */ (s,d) -> s | (d ^ 0xFFFF),
		/* 12 */ (s,d) -> s ^ 0xFFFF,
		/* 13 */ (s,d) -> (s ^ 0xFFFF) | d,
		/* 14 */ (s,d) -> (s ^ 0xFFFF) | (d ^ 0xFFFF),
		/* 15 */ (s,d) -> AllOnes
	};
	
	private static void copyLoop() {
		if (Config.TRACE_BITBLT) {
			logf("-> bitblt copyLoop start values:\n");
			logf("  - sourceBits.......: %s %s\n", sourceBits, (sourceBits != null) ? Memory.getClassName(sourceBits.getClassOOP()) : "");
			logf("  - sourceRaster.....: %d\n", sourceRaster);
			logf("  - destBits.........: %s %s\n", destBits, (destBits != null) ? Memory.getClassName(destBits.getClassOOP()) : "");
			logf("  - destRaster.......: %d\n", destRaster);
			logf("  - halftoneBits.....: %s %s\n", halftoneBits, (halftoneBits != null) ? Memory.getClassName(halftoneBits.getClassOOP()) : "");
			logf("  - skew.............: %d\n", skew);
			logf("  - skewMask.........: 0x%04X\n", skewMask);
			logf("  - startBits........: %d\n", startBits);
			logf("  - mask1............: 0x%04X\n", mask1);
			logf("  - endBits..........: %d\n", endBits);
			logf("  - mask2............: 0x%04X\n", mask2);
			logf("  - preload..........: %s\n", preload);
			logf("  - nWords...........: %d\n", nWords);
			logf("  - hDir.............: %d\n", hDir);
			logf("  - vDir.............: %d\n", vDir);
			logf("  - sourceIndex......: %d\n", sourceIndex);
			logf("  - sourceDelta......: %d\n", sourceDelta);
			logf("  - destIndex........: %d\n", destIndex);
			logf("  - destDelta........: %d\n", destDelta);
			logf("  - sx...............: %d\n", sx);
			logf("  - sy...............: %d\n", sy);
			logf("  - dx...............: %d\n", dx);
			logf("  - dy...............: %d\n", dy);
			logf("  - w................: %d\n", w);
			logf("  - h................: %d\n", h);
		}
		
		Combiner combiner = rules[Math.max(0, Math.min(combinationRule, 15))];
		boolean havingHalftone = (halftoneForm != null);
		boolean havingSource = (sourceForm != null);
		int mask1_inverted = mask1 ^ 0xFFFF;
		int mask2_inverted = mask2 ^ 0xFFFF;
		int skewMask_inverted = skewMask ^ 0xFFFF;
		
		// here is the vertical loop
		for (int i = 1; i <= h; i++) {
			int halftoneWord;
			if (havingHalftone) {
				halftoneWord = halftoneBits.fetchWordAt(dy & 0x000F);
				dy += vDir;
			} else {
				halftoneWord = AllOnes;
			}
			int skewWord = halftoneWord;
			int prevWord;
			if (preload) {
				// load the 32-bit shifter
				prevWord = sourceBits.fetchWordAt(sourceIndex);
				sourceIndex += hDir;
			} else {
				prevWord = 0;
			}
			int mergeMask = mask1;
			int mergeMask_inverted = mask1_inverted;
			
			// here is the inner horizontal loop
			for (int word = 1; word <= nWords; word++) {
				if (havingSource) {
					prevWord &= skewMask;
					int thisWord = (word <= sourceRaster) ? sourceBits.fetchWordAtNoFail(sourceIndex) : 0; // pick up next word
					skewWord = (prevWord | (thisWord & skewMask_inverted));
					prevWord = thisWord;
					skewWord = (skewWord << skew) | (skewWord >> (16 - skew)); // 16-bit rotate
				}
				int destWord = destBits.fetchWordAt(destIndex);
				int mergeWord = combiner.merge(skewWord & halftoneWord, destWord);
				destBits.storeWordAt(destIndex, (mergeMask & mergeWord) | ((mergeMask_inverted) & destWord));
				sourceIndex += hDir;
				destIndex += hDir;
				if (word == (nWords - 1)) {
					mergeMask = mask2;
					mergeMask_inverted = mask2_inverted;
				} else {
					mergeMask = AllOnes;
					mergeMask_inverted = 0;
				}
			}
			
			sourceIndex += sourceDelta; 
			destIndex += destDelta;
		}
	}
	
	/*
	 * processing of BitBlt.drawLoopX
	 */
	
	public static final Primitive primitiveDrawLoopX = () -> { w("primitiveDrawLoopX");
		// "This is the Bresenham plotting algorithm (IBM Systems Journal Vol 
		// 4 No. 1, 1965). It chooses a principal direction, and maintains  
		// a potential, P.  When P's sign changes, it is time to move in the 
		// minor direction as well."
		
		int yDelta = pointer2int(popStack());
		int xDelta = pointer2int(popStack());
		int bitBltOop = popStack();
		OTEntry bitBlt = Memory.ot(bitBltOop); // we assume the primitive is really attached only to a BitBlt message
		
		// leave the receiver (a BitBlt instance) on the stack:
		// -> it will the receiver for the copyBits messages (which will also leave it on the stack)
		// -> it will be returned by this primitive
		Interpreter.unPop(1);
		
		int dx = sign(xDelta);
		int dy = sign(yDelta);
		int px = Math.abs(yDelta);
		int py = Math.abs(xDelta);
		
		primitiveCopyBits.execute();
		
		if (py > px) {
			// "more horizontal"
			int P = py / 2; // all positive, so java-/ == st80-//
			for (int i = 0; i < py; i++) {
				incrField(bitBlt, BitBlt_DestX_Index, dx);
				P = P - px;
				if (P < 0) {
					incrField(bitBlt, BitBlt_DestY_Index, dy);
					P = P + py;
				}
				primitiveCopyBits.execute();
			}
		} else {
			// "more vertical"
			int P = px / 2; // all positive, so java-/ == st80-//
			for (int i = 0; i < px; i++) {
				incrField(bitBlt, BitBlt_DestY_Index, dy);
				P = P - py;
				if (P < 0) {
					incrField(bitBlt, BitBlt_DestX_Index, dx);
					P = P + px;
				}
				primitiveCopyBits.execute();
			}
		}
		
		// return self, which should still be on the stack
		return true;
	};
	
	private static int sign(int value) {
		if (value == 0) { return 0; }
		return (value < 0) ? -1 : 1;
	}
	
	private static void incrField(OTEntry obj, int fieldIdx, int by) {
		int tmp = pointer2int(obj.fetchPointerAt(fieldIdx));
		obj.storePointerAt(fieldIdx, int2pointer(tmp + by));
	}
	
	/*
	 * access to relevant instance variables of a CharacterScanner and (inherited) a BitBlt
	 * (for drawCircle and scanCharacters)
	 */
	
	private static OTEntry currSelf = null; // self of the currently executing drawCircle or scanCharacters method
	
	private static int lastIndex() { return pointer2int(currSelf.fetchPointerAt(CharScan_LastIndex)); }
	private static int lastIndex(int value) { currSelf.storePointerAt(CharScan_LastIndex, int2pointer(value)); return value; }
	
	private static int sourceX() { return pointer2int(currSelf.fetchPointerAt(BitBlt_SourceX_Index)); }
	private static int sourceX(int value) { currSelf.storePointerAt(BitBlt_SourceX_Index, int2pointer(value)); return value; }
	
	private static int width() { return pointer2int(currSelf.fetchPointerAt(BitBlt_Width_Index)); }
	private static int width(int value) { currSelf.storePointerAt(BitBlt_Width_Index, int2pointer(value)); return value; }
	
	private static int destX() { return pointer2int(currSelf.fetchPointerAt(BitBlt_DestX_Index)); }
	private static int destX(int value) { currSelf.storePointerAt(BitBlt_DestX_Index, int2pointer(value)); return value; }
	
	private static int destY() { return pointer2int(currSelf.fetchPointerAt(BitBlt_DestY_Index)); }
	private static int destY(int value) { currSelf.storePointerAt(BitBlt_DestY_Index, int2pointer(value)); return value; }
	
	/*
	 * processing of BitBlt.drawCircle (DV6)
	 */
	
	// "Draw a circle with given radius centered at destX@destY, using a modified Bresenham line algorithm."
	public static final Primitive primitiveDrawCircle = () -> { w("primitiveCircle");
		int radius = pointer2int(popStack());
		int selfPointer = popStack();
		
		currSelf = Memory.ot(selfPointer);
		
		// leave the receiver (a BitBlt instance) on the stack:
		// -> it will the receiver for the copyBits messages (which will also leave it on the stack)
		// -> it will be returned by this primitive
		Interpreter.unPop(1);
		
		int centerX = destX();
		int centerY = destY();
		int x = 0;
		int y = - Math.abs(radius);
		int p = y;
		
		while(true) {
			destX(centerX + x); destY(centerY + y); primitiveCopyBits.execute();
			destX(centerX - x); primitiveCopyBits.execute();
			destY(centerY - y); primitiveCopyBits.execute();
			destX(centerX + x); primitiveCopyBits.execute();
			destX(centerX + y); destY(centerY + x); primitiveCopyBits.execute();
			destX(centerX - y); primitiveCopyBits.execute();
			destY(centerY - x); primitiveCopyBits.execute();
			destX(centerX + y); primitiveCopyBits.execute();
			
			if (p > 0) {
				y += 1;
				p = p + y + y + 1;
			}
			x += 1;
			p = p + x + x + 1;
			
			if ((x + y) > 0) { break; }
		}
		
		destX(centerX);
		destY(centerY);
		return true;
	};
	
	
	
	/*
	 * ************************************************************ class CharacterScanner (subclass of BitBlt)
	 */
	
	
	/*
	 * field indices
	 */
	
	private static final int CharScan_LastIndex = 14;
	private static final int CharScan_XTable = 15;
	private static final int CharScan_StopConditions = 16;
	private static final int CharScan_Text = 17;
	private static final int CharScan_TextStyle = 18;
	private static final int CharScan_LeftMargin = 19;
	private static final int CharScan_RightMargin = 20;
	private static final int CharScan_Font = 21;
	private static final int CharScan_Line = 22;
	private static final int CharScan_RunStopIndex = 23;
	private static final int CharScan_SpaceCount = 24;
	private static final int CharScan_SpaceWidth = 25;
	private static final int CharScan_OutputMedium = 26;
	
	/*
	 * special values from TextConstants pool
	 */
	private static final int EndOfRun = 257;
	private static final int CrossedX = 258;
	
	/*
	 * implementation of CharacterScanner.scanCharacters
	 */
	
	public static final Primitive primitiveScanCharacters = () -> {
		int displayPointer = popStack();
		int stopsPointer = popStack();
		int rightX = pointer2int(popStack());
		int sourceStringPointer = popStack();
		int stopIndex = pointer2int(popStack());
		int startIndex = pointer2int(popStack());
		int selfPointer = stackTop(); // leave self on the stack for CopyBits
		
		currSelf = Memory.ot(selfPointer);
		
		OTEntry sourceString = Memory.ot(sourceStringPointer);
		OTEntry stops = Memory.ot(stopsPointer);
		boolean display = (displayPointer == Well.known().TruePointer);
		
		OTEntry xTable = Memory.ot(currSelf.fetchPointerAt(CharScan_XTable));
		
		lastIndex(startIndex);
		while(lastIndex() <= stopIndex) {
			int ascii = sourceString.fetchByteAt(lastIndex() - 1);
			if (stops.fetchPointerAt(ascii) != Well.known().NilPointer) {
				popStack(); // drop self
				push(stops.fetchPointerAt(ascii));
				return success();
			}
			sourceX(pointer2int(xTable.fetchPointerAt(ascii)));
			width(pointer2int(xTable.fetchPointerAt(ascii + 1)) - sourceX());
			int nextDestX = destX() + width();
			if (nextDestX > rightX) {
				popStack(); // drop self
				push(stops.fetchPointerAt(CrossedX - 1));
				return success();
			}
			if (display) {
				primitiveCopyBits.execute();
			}
			destX(nextDestX);
			lastIndex(lastIndex() + 1);
		}
		lastIndex(stopIndex);
		
		popStack(); // drop self
		push(stops.fetchPointerAt(EndOfRun - 1));
		return success();
	};
	
}
