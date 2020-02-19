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
 * Definition of the functionality of Object Table entries allowing the
 * uniform access to objects, independently of the Smalltalk class. 
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public interface OTEntry {
	
	/*
	 * identification and technical object type of the object
	 */
	
	int objectPointer();
	int linearObjectPointer();
	
	boolean isSmallInt();
	boolean isObject();
	
	/*
	 * the next method is possible only if isSmallInt() -> true
	 */
	
	int intValue();
	
	/*
	 * all next methods are possible only if isObject() -> true
	 */
	
	// conversion from / to external 2-word representation in a snapshot
	
	void fromWords(short w0, short w1);
	short getWord0();
	short getWord1();
	
	// r/o: access to the fields as defined by Bluebook

	short count();
	boolean oddLength();
	boolean pointerFields();
	boolean freeEntry();
	int address();
	int segment();
	int location();

	// r/o: heap fields not in the Smalltalk-object's instance variable fields
	
	int getSize();
	int getClassOOP();
	
	// r/o: number of items in the Smalltalk-object's instance variable fields
	
	int getWordLength();
	int getByteLength();
	
	// r/w:  access  items in the Smalltalk-object's instance variable fields (as defined by Bluebook)
	
	int fetchPointerAt(int wordOffset);
	OTEntry storePointerAt(int wordOffset, int valuePointer);
	
	int fetchWordAt(int wordOffset);
	int fetchWordAtNoFail(int wordOffset);
	OTEntry storeWordAt(int wordOffset, int valueWord);
	
	int fetchByteAt(int byteOffset);
	OTEntry storeByteAt(int byteOffset, int valueByte);
	
	/*
	 * the following methods are always supported, possibly implemented as dummies
	 */
	
	// for reference counting
	
	void countUp();
	void countDown();
	
	// for garbage collection
	
	boolean setUsed(int generation); // return true if the object was *not* used in the given generation, meaning: continue recursive processing of referenced objects! 
	boolean isUsed(int generation);
	
}
