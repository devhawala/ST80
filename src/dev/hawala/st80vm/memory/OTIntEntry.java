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

/**
 * Object table entry for a SmallInteger object (i.e. the object pointer
 * implicitly contains the integer value and is in fact a singleton, the object
 * therefore does not need and has no heap area).
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
/*package-access*/ class OTIntEntry implements OTEntry {

	private final int objectPointer;
	private final int value;
	
	OTIntEntry(int otIndex) {
		this.objectPointer = otIndex;
		this.value = ((short)otIndex) >> 1; // this should allow negative values
	}
	
	@Override
	public int objectPointer() {
		return this.objectPointer;
	}

	@Override
	public boolean isSmallInt() {
		return true;
	}
	
	@Override
	public int intValue() {
		return this.value;
	}

	@Override
	public boolean isObject() {
		return false;
	}
	
	@Override
	public void fromWords(short w0, short w1) {
		throw MisuseHandler.ofIntMethod("fromWords");
	}
	
	@Override
	public short getWord0() {
		throw MisuseHandler.ofIntMethod("getWord0");
	}
	
	@Override
	public short getWord1() {
		throw MisuseHandler.ofIntMethod("getWord1");
	}

	@Override
	public short count() {
		throw MisuseHandler.ofIntMethod("count");
	}

	@Override
	public boolean oddLength() {
		throw MisuseHandler.ofIntMethod("oddLength");
	}

	@Override
	public boolean pointerFields() {
		throw MisuseHandler.ofIntMethod("pointerFields");
	}

	@Override
	public boolean freeEntry() {
		return false; // a SmallInteger is never a free object table entry
	}

	@Override
	public int address() {
		throw MisuseHandler.ofIntMethod("address");
	}

	@Override
	public int segment() {
		throw MisuseHandler.ofIntMethod("segment");
	}

	@Override
	public int location() {
		throw MisuseHandler.ofIntMethod("location");
	}

	@Override
	public int getSize() {
		throw MisuseHandler.ofIntMethod("getSize");
	}

	@Override
	public int getClassOOP() {
		return Well.known().ClassSmallIntegerPointer;
	}
	
	@Override
	public int getWordLength() {
		return 0;
	}
	
	@Override
	public int getByteLength() {
		return 0;
	}

	@Override
	public int fetchPointerAt(int wordOffset) {
		throw MisuseHandler.ofIntMethod("fetchPointerAt");
	}

	@Override
	public OTEntry storePointerAt(int wordOffset, int valuePointer) {
		throw MisuseHandler.ofIntMethod("storePointerAt");
	}

	@Override
	public int fetchWordAt(int wordOffset) {
		throw MisuseHandler.ofIntMethod("fetchWordAt");
	}

	@Override
	public int fetchWordAtNoFail(int wordOffset) {
		throw MisuseHandler.ofIntMethod("fetchWordAtNoFail");
	}

	@Override
	public OTEntry storeWordAt(int wordOffset, int valueWord) {
		throw MisuseHandler.ofIntMethod("storeWordAt");
	}

	@Override
	public int fetchByteAt(int byteOffset) {
		throw MisuseHandler.ofIntMethod("fetchByteAt");
	}

	@Override
	public OTEntry storeByteAt(int  byteOffset, int valueByte) {
		throw MisuseHandler.ofIntMethod("storeByteAt");
	}

	@Override
	public void countUp() {
		// irrelevant for SmallInteger
	}

	@Override
	public void countDown() {
		// irrelevant for SmallInteger
	}

	@Override
	public boolean setUsed(int generation) {
		return false; // SmallIntegers are always "in use" and have no references
	}

	@Override
	public boolean isUsed(int generation) {
		return true; // SmallIntegers are always "in use"
	}
	
	private String strVal = null;
	
	@Override
	public String toString() {
		if (this.strVal == null) {
			this.strVal = String.format("SmallInteger[ %d ]", this.value);
		}
		return this.strVal;
	}

}
