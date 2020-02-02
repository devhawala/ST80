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

package dev.hawala.st80vm.memory;

import java.util.ArrayList;
import java.util.List;

import dev.hawala.st80vm.Config;

/**
 * Object table entry for all "true" objects, i.e. that are not of (Smalltalk) class SmallInteger.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
/* package-access */ class OTObjectEntry implements OTEntry {
	
	private static void logf(String pattern, Object... args) {
		if (Config.LOG_MEM_OPS) { System.out.printf(pattern, args); }
	}

	private short count;
	private boolean oddLength;
	private boolean pointerFields;
	private boolean freeEntry;
	private int address; // (segment.location)
	
	private final int objectPointer;
	
	private int fieldWordsLimit;
	private int fieldBytesLimit;
	
	private int gcGeneration = -1;
	
	public OTObjectEntry(int oop) {
		this.objectPointer = oop;
		this.free();
	}
	
	@Override
	public void fromWords(short w0, short w1) {
		this.count = (short)((w0 >> 8) & 0xFF);
		this.oddLength = (w0 & 0x0080) != 0;
		this.pointerFields = (w0 & 0x0040) != 0;
		this.freeEntry = (w0 & 0x0020) != 0;
		this.address = ((w0 & 0x000F) << 16) | (w1 & 0xFFFF);
		
		this.fieldWordsLimit = this.getSize() - 2;
		this.fieldBytesLimit = this.fieldWordsLimit * 2;
		if (this.oddLength) { this.fieldBytesLimit--; }
	}
	
	@Override
	public short getWord0() {
		int w = ((this.count & 0xFF) << 8)
			  | (this.oddLength     ? 0x0080 : 0x0000)
			  | (this.pointerFields ? 0x0040 : 0x0000)
			  | (this.freeEntry     ? 0x0020 : 0x0000)
			  | this.segment();
		return (short)w;
	}
	
	@Override
	public short getWord1() {
		return (short)this.location();
	}
	
	@Override
	public int objectPointer() {
		return this.objectPointer;
	}
	
	@Override
	public boolean isSmallInt() {
		return false;
	}
	
	@Override
	public int intValue() {
		throw MisuseHandler.ofObjectMethod("intValue()");
	}

	@Override
	public boolean isObject() {
		return true;
	}
	
	public short count() { return this.count; }
	public boolean oddLength() { return this.oddLength; }
	public boolean pointerFields() { return this.pointerFields; }
	public boolean freeEntry() { return this.freeEntry; }
	public int address() { return this.address; }
	public int segment() { return (this.address >> 16) & 0x000F; }
	public int location() { return this.address & 0xFFFF; }
	
	/* package-access */
	OTEntry free() {
		this.count = 0;
		this.oddLength = false;
		this.pointerFields = false;
		this.freeEntry = true;
		this.address = 0;
		return this;
	}
	
	/* package-access */
	OTEntry allocate(int address, boolean pointerFields, boolean oddLength) {
		this.count = 0; // object not yet in use, usage starts with first storePointer() (puttin it on the interpretation stack, storing it somewhere
		this.oddLength = oddLength;
		this.pointerFields = pointerFields;
		this.freeEntry = false;
		this.address = address;
		
		this.fieldWordsLimit = this.getSize() - 2;
		this.fieldBytesLimit = this.fieldWordsLimit * 2;
		if (this.oddLength) { this.fieldBytesLimit--; }
		
		return this;
	}
	
	@Override
	public int getSize() {
		return (this.freeEntry && this.address == 0) ? 0 : Memory.heapMemory[this.address] & 0xFFFF;
	}
	
	@Override
	public int getClassOOP() {
		return (this.freeEntry && this.address == 0) ? 0 : Memory.heapMemory[this.address + 1] & 0xFFFF;
	}
	
	@Override
	public int getWordLength() {
		return this.fieldWordsLimit;
	}
	
	@Override
	public int getByteLength() {
		return this.fieldBytesLimit;
	}
	
	private void checkFree() {
		if (this.freeEntry) {
			throw new RuntimeException(String.format("Misuse ERROR: using fetch/store on free-ed object 0x%04X", this.objectPointer));
		}
	}

	@Override
	public int fetchPointerAt(int wordOffset) {
		checkFree();
		if (wordOffset < 0 || wordOffset >= this.fieldWordsLimit) {
			throw MisuseHandler.ofObjectMemory("getWordAt", wordOffset, null);
		}
		return Memory.heapMemory[this.address + 2 + wordOffset] & 0xFFFF;
	}

	@Override
	public OTEntry storePointerAt(int wordOffset, int valuePointer) {
		checkFree();
		if (wordOffset < 0 || wordOffset >= this.fieldWordsLimit) {
			throw MisuseHandler.ofObjectMemory("setWordAt", wordOffset, null);
		}

		int oldValuePointer = Memory.heapMemory[this.address + 2 + wordOffset] & 0xFFFF; 
		OTEntry oldObject = Memory.ot(oldValuePointer);
		OTEntry newObject = Memory.ot(valuePointer);
		
		if (valuePointer != Well.known().NilPointer) { newObject.countUp(); }
		if (oldValuePointer != Well.known().NilPointer) { oldObject.countDown(); }
		
		Memory.heapMemory[this.address + 2 + wordOffset] = (short)(valuePointer & 0xFFFF);
		return this;
	}

	@Override
	public int fetchWordAt(int wordOffset) {
		checkFree();
		if (wordOffset < 0 || wordOffset >= this.fieldWordsLimit) {
			throw MisuseHandler.ofObjectMemory("getWordAt", wordOffset, null);
		}
		return Memory.heapMemory[this.address + 2 + wordOffset] & 0xFFFF;
	}

	@Override
	public int fetchWordAtNoFail(int wordOffset) {
		checkFree();
		if (wordOffset < 0 || wordOffset >= this.fieldWordsLimit) {
			return 0;
		}
		return Memory.heapMemory[this.address + 2 + wordOffset] & 0xFFFF;
	}

	@Override
	public OTEntry storeWordAt(int wordOffset, int valueWord) {
		checkFree();
		if (wordOffset < 0 || wordOffset >= this.fieldWordsLimit) {
			throw MisuseHandler.ofObjectMemory("setWordAt", wordOffset, null);
		}
		Memory.heapMemory[this.address + 2 + wordOffset] = (short)(valueWord & 0xFFFF);
		return this;
	}

	@Override
	public int fetchByteAt(int byteOffset) {
		checkFree();
		if (byteOffset < 0 || byteOffset >= this.fieldBytesLimit) {
			throw MisuseHandler.ofObjectMemory("getByteAt", byteOffset, null);
		}
		int wordOffset = byteOffset / 2;
		int word = Memory.heapMemory[this.address + 2 + wordOffset] & 0xFFFF;
		if ((byteOffset & 1) == 0) {
			return (word >> 8) & 0x00FF;
		} else {
			return word & 0x00FF;
		}
	}

	@Override
	public OTEntry storeByteAt(int byteOffset, int valueByte) {
		checkFree();
		if (byteOffset < 0 || byteOffset >= this.fieldBytesLimit) {
			throw MisuseHandler.ofObjectMemory("setByteAt", byteOffset, null);
		}
		int wordOffset = byteOffset / 2;
		int word = Memory.heapMemory[this.address + 2 + wordOffset] & 0xFFFF;
		
		if ((byteOffset & 1) == 0) {
			word = ((valueByte << 8) & 0xFF00) | (word & 0x00FF);
		} else {
			word = (word & 0xFF00) | (valueByte & 0x00FF);
		}
		Memory.heapMemory[this.address + 2 + wordOffset] = (short)word;
		
		return this;
	}

	@Override
	public void countUp() {
		if (this.count < 128) {
			this.count++;
		}
	}

	@Override
	public void countDown() {
		if (this.count >= 128) { return; } // this object is no longer reference-counted
		if (this.count == 0) { return; }  // WHAT???
		this.count--;
		if (this.count == 0) {
			// free this object and countDown() all referenced objects
			freeObject(this);
		}
	}

	@Override
	public boolean setUsed(int generation) {
		if (this.gcGeneration == generation) {
			return false; // already in this generation
		}
		this.gcGeneration = generation;
		return true; // was not in this generation => process referenced objects
	}

	@Override
	public boolean isUsed(int generation) {
		return (this.gcGeneration == generation);
	}
	
	@Override
	public String toString() {
		String memInfo = (this.freeEntry && this.address == 0)
				? " FREE"
				: String.format(" mem( size=%3d , class=0x%04X )", this.getSize(), this.getClassOOP());
		return String.format(
				"Object[ 0x%04X%s count=%3d address=0x%06X%s%s%s ] (%s)",
				this.objectPointer,
				memInfo,
				this.count,
				this.address,
				(this.freeEntry ? " free" : "     "),
				(this.pointerFields ? " ptrFlds" : "        "),
				(this.oddLength ? " oddLen" : "       "),
				Memory.getClassNameOf(this.objectPointer)
				);
	}
	
	private int nextObjectPointerInFreeList = -1;
	
	/* package-access */
	void enqueueInFreeList(int prevOop) {
		this.nextObjectPointerInFreeList = prevOop;
		this.freeEntry = false; // just to be sure => objectTable entries in a free-list have: count == 0 && freeEntry == false
	}
	
	/* package-access */
	int dequeueFromFreeList(int classOop, boolean pointerFields, boolean oddLength) {
		int nextOop = this.nextObjectPointerInFreeList;
		this.nextObjectPointerInFreeList = -1;
		
		this.count = 0; // object not yet in use, usage starts with first storePointer() (putting it on the interpretation stack, storing it somewhere)
		this.freeEntry = false;
		this.pointerFields = pointerFields;
		this.oddLength = oddLength;
		Memory.heapMemory[this.address + 1] = (short)classOop;
		
		this.fieldWordsLimit = this.getSize() - 2;
		this.fieldBytesLimit = this.fieldWordsLimit * 2;
		if (this.oddLength) { this.fieldBytesLimit--; }
		
		return nextOop;
	}
	
	/* package-access */
	int dropFromFreeList() {
		int nextOop = this.nextObjectPointerInFreeList;
		this.nextObjectPointerInFreeList = -1;
		this.free(); // reset to true free ObjectTable entry
		Memory.noteFreeOop(this.objectPointer);
		return nextOop;
	}
	
	/* package-access */
	void relocatedInHeap(int toHeapLocation) {
		this.address = toHeapLocation;
	}
	
	private static boolean freeObjectsSuspended = false;
	private static final List<OTObjectEntry> candidatesForRelease = new ArrayList<>();
	
	/* package-access */
	static void suspendFreeingObjects() {
		freeObjectsSuspended = true;
		logf("**** freeing object suspended\n");
	}
	
	/* package-access */
	static void resumeFreeingObjects() {
		if (freeObjectsSuspended) {
			logf("**** freeing object resumed, in suspended list: %d\n", candidatesForRelease.size());
			freeObjectsSuspended = false;
			for (OTObjectEntry e : candidatesForRelease) {
				if (e.count == 0) {
					Memory.releaseObject(e);
				}
			}
			candidatesForRelease.clear();
		}
	}
	
	private static void freeObject(OTObjectEntry e) {
		if (freeObjectsSuspended) {
			if (!candidatesForRelease.contains(e)) {
				candidatesForRelease.add(e);
			}
		} else {
			logf("**** freeing object 0x%04X , class = 0x%04X (%s)\n", e.objectPointer(), e.getClassOOP(), Memory.getClassName(e.getClassOOP()));
			Memory.releaseObject(e);
		}
	}
	
}
