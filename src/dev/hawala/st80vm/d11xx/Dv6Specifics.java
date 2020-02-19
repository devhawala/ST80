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

package dev.hawala.st80vm.d11xx;

import static dev.hawala.st80vm.interpreter.Interpreter.positive16BitValueOf;
import static dev.hawala.st80vm.interpreter.InterpreterBase.popStack;
import static dev.hawala.st80vm.interpreter.InterpreterBase.push;
import static dev.hawala.st80vm.interpreter.InterpreterBase.success;
import static dev.hawala.st80vm.interpreter.InterpreterBase.unPop;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.interpreter.Interpreter;
import dev.hawala.st80vm.interpreter.Interpreter.Primitive;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.OTEntry;
import dev.hawala.st80vm.memory.Well;

/**
 * Implementation of most 1108/1186 specific primitives (other than file system
 * and bitblt related) as defined/referenced in ST80-DV6.sources.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Dv6Specifics {
	
private Dv6Specifics() { }
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> Dv6Specifics." + name); } }
	
	private static void gclogf(String pattern, Object... args) {
		if (Config.LOG_MEM_GC) { System.out.printf(pattern, args); }
	}

	// (primitive 138) 
	// primitiveGarbageCollect: purgeableDictionaryClass purging: purgeableDictionaryArray using: replacementKey
	//
	// Performs a mark and sweep garbage collection. Also deallocates
	// all objects that are uniquely referred to as keys from any of the dictionaries
	// in purgeableDictionaryArray, which should all be instances of subClasses of 
	// purgeableDictionaryClass. When it is necessary to remove a key, it is
	// replaced with replacementKey. Primitive fails if:
	//   purgeableDictionaryClass is not really a class; or
	//   purgeableDictionaryArray is not an Array; or
	//   purgeableDictionaryArray entries are not instances of purgeableDictionaryClass; or
	//   purgeableDictionaryArray entries are empty.
	public static final Primitive primitiveGarbageCollect = () -> { w("primitiveGarbageCollect");
		int replacementKeyPointer = popStack();
		int purgeableDictionaryArrayPointer = popStack();
		int purgeableDictionaryClassPointer = popStack();
		// leave self (receiver) on the stack as return value
		
		if (Config.LOG_MEM_GC) { 
			OTEntry purgeableDictionaryClass = Memory.ot(purgeableDictionaryClassPointer);
			OTEntry purgeableDictionaryArray = Memory.ot(purgeableDictionaryArrayPointer);
			OTEntry replacementKey = Memory.ot(replacementKeyPointer);
			
			gclogf("\n## primitiveGarbageCollect() -- arguments:");
			gclogf("\n##   purgeableDictionaryClass: %s", purgeableDictionaryClass.toString());
			gclogf("\n##   purgeableDictionaryArray: %s", purgeableDictionaryArray.toString());
			for (int i = 0; i < purgeableDictionaryArray.getWordLength(); i++) {
				OTEntry e = Memory.ot(purgeableDictionaryArray.fetchPointerAt(i));
				gclogf("\n##      [%3d]: %s", i, e.toString());
				for (int idx = 0; idx < e.getWordLength(); idx++) {
					OTEntry dictEntry = Memory.ot(e.fetchPointerAt(idx));
					gclogf("\n##           [%3d]: %s", idx, dictEntry.toString());
				}
			}
			gclogf("\n##   replacementKey: %s", replacementKey.toString());
		}
		
		Memory.gc("primitiveGarbageCollect", purgeableDictionaryClassPointer, purgeableDictionaryArrayPointer, replacementKeyPointer);

		gclogf("\n## primitiveGarbageCollect() -- done");
		
		return true;
	};

	// (primitive 176)
	// specialReplaceFrom: start to: stop with: replacement startingAt: repStart
	//
	// Destructively replace elements in the receiver (a ByteArray
	// or String) starting at start (a byte index) with replacement (a WordArray)
	// starting at repStart (a word index). Answer the receiver. Note: Complete
	// range checks are performed by the primitive. Furthermore, the primitive
	// requires that:
	//   self is a byte-type object (such as a ByteArray or String),
	//   replacement is a non-pointer word-type object (such as a WordArray),
	//   start is a positive integer <64K,
	//   stop is a positive integer <64K,
	//   repStart is an odd positive integer <64K.
	public static final Primitive primitiveSpecialReplaceInBytes = () -> { w("primitiveSpecialReplaceInBytes");
		int repStart = positive16BitValueOf(popStack());
		int replacementPointer = popStack();
		int stop = positive16BitValueOf(popStack());
		int start = positive16BitValueOf(popStack());
		int selfPointer = popStack();
		if (!success()) {
			unPop(5);
			return false;
		}
		push(selfPointer); // return self at end
		
		OTEntry self = Memory.ot(selfPointer); // String/ByteArray
		int maxSelfIndex = self.getByteLength();
		OTEntry replacement = Memory.ot(replacementPointer); // WordArray, accessed byte-wise
		int maxReplIndex = replacement.getByteLength();
		
		// Smalltalk indexes start at 1
		int selfIndex = start - 1;
		int replIndex = (repStart - 1) * 2;
		while(selfIndex < stop) {
			if (selfIndex >= maxSelfIndex) { break; }
			if (replIndex >= maxReplIndex) { break; }
			int b = replacement.fetchByteAt(replIndex++);
			self.storeByteAt(selfIndex++, b);
		}
		
		return true;
	};

	// (primitive 177)
	// specialReplaceFrom: start to: stop with: replacement startingAt: repStart
	//
	// Destructively replace elements in the receiver (a WordArray)
	// starting at start (a word index) with replacement (a String or ByteArray)
	// starting at repStart (a byte index). Answer the receiver. Note: Complete
	// range checks are performed by the primitive. Furthermore, the primitive
	// requires that:
	//    self is a non-pointer word-type object (such as a WordArray),
	//    replacement is a byte-type object (such as a String or ByteArray),
	//    start is a positive integer <64K,
	//    stop is a positive integer <64K,
	//    repStart is an odd positive integer <64K.
	public static final Primitive primitiveSpecialReplaceInWords = () -> { w("primitiveSpecialReplaceInWords");
		int repStart = positive16BitValueOf(popStack());
		int replacementPointer = popStack();
		int stop = positive16BitValueOf(popStack());
		int start = positive16BitValueOf(popStack());
		int selfPointer = popStack();
		if (!success()) {
			unPop(5);
			return false;
		}
		push(selfPointer); // return self at end
		
		OTEntry self = Memory.ot(selfPointer); // WordArray
		int maxSelfIndex = self.getWordLength();
		OTEntry replacement = Memory.ot(replacementPointer); // String/ByteArray
		int maxReplIndex = replacement.getByteLength();
		
		// Smalltalk indexes start at 1
		int selfIndex = start - 1;
		int replIndex = repStart - 1;
		while(selfIndex < stop && selfIndex < maxSelfIndex) {
			if (replIndex >= maxReplIndex) { break; }
			int b1 = replacement.fetchByteAt(replIndex++);
			int b2 = (replIndex >= maxReplIndex) ? 0 : replacement.fetchByteAt(replIndex++);
			int w = (b1 << 8) | b2;
			self.storeWordAt(selfIndex++, w);
		}
		
		return true;
	};

	// (primitive 197)
	// firstOwner
	//
	// Answer the object with the lowest oop which has a field that points to the
	// receiver. Answer nil if none found.
	public static final Primitive primitiveFirstOwner = () -> { w("primitiveFirstOwner");
		int self = popStack();
		// nil is the first valid object and does not reference anything, so it is a safe start to find the first owner
		int firstOwner = Memory.getNextOwnerOf(self, Well.known().NilPointer);
		push(firstOwner);
		return true;
	};

	// (primitive 198)
	// nextOwnerAfter: anObject
	//
	// Answer the object with the lowest oop after anObject which has a field that points
	// to the receiver. Answer nil if none found.
	public static final Primitive primitiveNextOwner = () -> { w("primitiveNextOwner");
		int currentOwner = popStack();
		int self = popStack();
		int nextOwner = Memory.getNextOwnerOf(self, currentOwner);
		push(nextOwner);
		return true;
	};

	// (primitive 253)
	//
	// (undocumented)
	public static final Primitive primitiveDefer = () -> { w("primitiveDefer");
		// leave self (receiver) on the stack as return value
		//System.out.printf("\n+++++++++ primitive defer()");
		
		/*
		 * as the ultimate purpose of this primitive is unclear (it is called when
		 * "defer" is selected on the background menu or when the "root project is closed"),
		 * it is mapped to a plain garbage collection (allowing the user to request it
		 * explicitly)
		 */
		Memory.gc("primitiveDefer");
	
		return true;
	};

	// (primitive 143)
	// countBits
	// 
	// Answer the total number of 1 bits in all of the receiver's fields.
	public static final Primitive primitiveCountBits = () -> { w("primitiveCountBits");
		System.out.printf("\n+++++++++ primitive countBits()");
		
		int selfPointer = popStack();
		OTEntry self = Memory.ot(selfPointer);
		
		int bytes = self.getByteLength();
		int ones = 0;
		for (int i = 0; i < bytes; i++) {
			int b = self.fetchByteAt(i);
			if ((b & 0x80) != 0) { ones++; }
			if ((b & 0x40) != 0) { ones++; }
			if ((b & 0x20) != 0) { ones++; }
			if ((b & 0x10) != 0) { ones++; }
			if ((b & 0x08) != 0) { ones++; }
			if ((b & 0x04) != 0) { ones++; }
			if ((b & 0x02) != 0) { ones++; }
			if ((b & 0x01) != 0) { ones++; }
		}
		
		push(Interpreter.positive32BitIntegerFor(ones));
		
		return true;
	};

	// (primitive 222)
	// localTimeParametersInto: aWordArray
	//
	// The argument is a word indexable object of length at least two. Store 
	// into the first two words of the argument the local time parameters,
	// a 32-bit unsigned number with the following format:
	//	1st word (aWordArray at: 1):
	//		sign			bit 1	Zero if west of Greenwich, one if east
	//		zoneH		bit 4	Local time zone in hours from Greenwich
	//		blank		bit 2
	//		beginDST	bit 9	Day of year on or before which DST starts
	//	2nd word (aWordArray at: 2):
	//		blank		bit 1
	//		zoneM		bit 6	Additional minutes of local time zone
	//		endDST		bit 9	Day of year on or before which DST ends
	
	private static int localGmtMinuteOffset = 0;
	private static int localDstStart = 0;
	private static int localDstEnd = 0;
	
	private static int localTimeWord1 = 0;
	private static int localTimeWord2 = 0;
	
	public static final Primitive primitiveLocalTimeParametersInto = () -> { w("primitiveLocalTimeParametersInto");
		int wordsPointer = popStack();
		// leave self on the stack as return value
		
		OTEntry words = Memory.ot(wordsPointer);
		if (words.getWordLength() < 2) {
			unPop(1);
			return false;
		}
		
		words.storeWordAt(0, localTimeWord1);
		words.storeWordAt(1, localTimeWord2);
		
		return true;
	};
	
	public static void setLocalTimeParameters(int gmtMinuteOffset, int dstStart, int dstEnd) {
		localGmtMinuteOffset = gmtMinuteOffset;
		localDstStart = dstStart;
		localDstEnd = dstEnd;
		
		int signBit = (localGmtMinuteOffset > 0) ? 0x8000 : 0; // east of gmt is is positive, so set the bit if east
		int minutes = Math.abs(localGmtMinuteOffset) % 60;
		int hours = Math.abs(localGmtMinuteOffset) / 60;
		
		localTimeWord1
			= signBit
			| ((hours & 0x000F) << 11)
			| (localDstStart & 0x01FF);
		localTimeWord2
			= ((minutes & 0x003F) << 9)
			| (localDstEnd & 0x01FF);
	}

}
