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

package dev.hawala.st80vm.interpreter;

import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.OTEntry;
import dev.hawala.st80vm.memory.Well;

/**
 * Low-level functionality for the Smalltalk interpreter. This class is simply
 * a support class for the interpreter proper in class {@link Interpreter} which
 * mainly implements the instructions and some interpreter-related primitives.
 * <p>
 * The functionality uses as far as possible the same names as the corresponding
 * items in the Bluebook, so both the specification in the book can easily be
 * related to this implementation. Furthermore, it was tried to keep the Java
 * implementation as close as possible to the Smalltalk specification code used
 * in the Bluebook.
 * </p>
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class InterpreterBase {
	
	protected InterpreterBase() { }
	
	// what kind of object memory model is this image:
	// - (false) a nominal Bluebook image (32k objects, 32k small integers)
	// - (true) a 1186/1108 "stretch" image (48k objects, 16k small integers)
	protected static boolean isStretch = false;
	
	/*
	 * Registers of the interpreter
	 */
	
	// Bluebook p. 583: "Context-related Registers of the Interpreter"
	protected static int activeContext = -1;
	protected static int homeContext = -1;
	protected static int method = -1;
	protected static int receiver = -1;
	protected static int instructionPointer = -1;
	protected static int stackPointer = -1;
	
	// Bluebook p. 587: "Class-related Registers of the Interpreter"
	protected static int messageSelector = -1;
	protected static int argumentCount = -1;
	protected static int newMethod = -1;
	protected static int primitiveIndex = -1;
	
	
	/*
	 * Bluebook pp. 616: primitive support
	 */
	
	private static boolean success = true;
	
	public static boolean success(boolean successValue) {
		success &= successValue;
		return success;
	}
	
	public static boolean success() {
		return success;
	}
	
	protected static void initPrimitive() {
		success = true;
	}
	
	public static boolean primitiveFail() {
		success = false;
		return false;
	}
	
	
	/*
	 * Bluebook p. 573..: "special routines to access fields that contain Smallintegers" etc.
	 */
	
	public static final int INVALID_INTEGER = 0x7FFFFFFF;
	
	public static int fetchInteger(int fieldIndex, int objectPointer) {
		int integerPointer = Memory.fetchPointer(fieldIndex, objectPointer);
		if (Memory.isIntegerObject(integerPointer)) {
			return Memory.integerValueOf(integerPointer);
		}
		primitiveFail();
		return INVALID_INTEGER; // we must return an int, so: last Integer => out-of-bounds when accessing anything in st-80 tables or virtual memory!
	}
	
	public static void storeInteger(int fieldIndex, int objectPointer, int integerValue) {
		if (Memory.isIntegerValue(integerValue)) {
			int integerPointer = Memory.integerObjectOf(integerValue);
			Memory.storePointer(fieldIndex, objectPointer, integerPointer);
		} else {
			primitiveFail();
		}
	}
	
	protected static void transfer(int count, int firstFrom, int fromOop, int firstTo, int toOop) {
		int fromIndex = firstFrom;
		int lastFrom = firstFrom + count;
		int toIndex = firstTo;
		int nil = Well.known().NilPointer;
		while (fromIndex < lastFrom) {
			int oop = Memory.fetchPointer(fromIndex, fromOop);
			Memory.storePointer(toIndex, toOop, oop);
			Memory.storePointer(fromIndex, fromOop, nil);
			fromIndex++;
			toIndex++;
		}
	}
	
	protected static final int[] EXTRACT_BITMASK = {
			0x0000,
			0x0001, 0x0003, 0x0007, 0x000F,
			0x001F, 0x003F, 0x007F, 0x00FF,
			0x01FF, 0x03FF, 0x07FF,	0x0FFF,
			0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
			
	};
	
	protected static int extractBits(int firstBitIndex, int lastBitIndex, int anInteger) {
		if (firstBitIndex < 0 || firstBitIndex > 15) { throw new RuntimeException("extractBits(): invalid firstBitIndex = " + firstBitIndex); }
		if (lastBitIndex < 0 || lastBitIndex > 15) { throw new RuntimeException("extractBits(): invalid lastBitIndex = " + lastBitIndex); }
		return (anInteger >> (15 - lastBitIndex)) & EXTRACT_BITMASK[lastBitIndex - firstBitIndex + 1];
	}
	
	protected static int highByteOf(int anInteger) {
		return (anInteger >> 8) & 0x00FF;
	}
	
	protected static int lowByteOf(int anInteger) {
		return anInteger & 0x00FF;
	}
	
	/*
	 * Bluebook p. 576..: "Compiled Methods"
	 */
	
	protected static int headerOf(int methodPointer) {
		return Memory.fetchPointer(Well.known().HeaderIndex, methodPointer);
	}
	
	protected static int literal(int offset, int methodPointer) {
		return Memory.fetchPointer(offset + Well.known().LiteralStartIndex, methodPointer);
	}
	
	protected static int temporaryCountOf(int methodPointer) {
		return extractBits(3, 7, headerOf(methodPointer));
	}
	
	protected static int largeContextFlagOf(int methodPointer) {
		if (isStretch) {
			int flag = flagValueOf(methodPointer);
			if (flag == 7) {
				return extractBits(0, 0, headerExtensionOf(methodPointer));
			} else if (flag == 5 || flag == 6) { // primitive returns of self resp. instance variable
				return 0;
			} else {
				// int minSize = temporaryCountOf(methodPointer); // + expected stack size (but this is unknown!)
				return 1; // just to be sure (unclear if a largeContext forces an headerExtension, so be on the safe side) 
			}
		} else {
			return extractBits(8, 8, headerOf(methodPointer));
		}
	}
	
	protected static int literalCountOf(int methodPointer) {
		return literalCountOfHeader(headerOf(methodPointer));
	}
	
	public static int literalCountOfHeader(int headerPointer) {
		if (isStretch) {
			return extractBits(8, 13, headerPointer);
		} else {
			return extractBits(9, 14, headerPointer);
		}
	}
	
	public static int objectPointerCountOf(int methodPointer) {
		return literalCountOf(methodPointer) + Well.known().LiteralStartIndex;
	}
	
	protected static int initialInstructionPointerOfMethod(int methodPointer) {
		return ((literalCountOf(methodPointer) + Well.known().LiteralStartIndex) * 2) + 1;
	}
	
	protected static int flagValueOf(int methodPointer) {
		return extractBits(0, 2, headerOf(methodPointer));
	}
	
	protected static int fieldIndexOf(int methodPointer) {
		return extractBits(3, 7, headerOf(methodPointer));
	}
	
	protected static int headerExtensionOf(int methodPointer) {
		int literalCount = literalCountOf(methodPointer);
		return literal(literalCount - 2, methodPointer);
	}
	
	protected static int argumentCountOf(int methodPointer) {
		int flagValue = flagValueOf(methodPointer);
		if (flagValue < 5) { return flagValue; }
		if (flagValue < 7) { return 0; }
		if (isStretch) {
			return extractBits(1, 5, headerExtensionOf(methodPointer));
		} else {
			return extractBits(2, 6, headerExtensionOf(methodPointer));
		}
	}
	
	protected static int primitiveIndexOf(int methodPointer) {
		int flagValue = flagValueOf(methodPointer);
		if (flagValue == 7) {
			if (isStretch) {
				return extractBits(6, 13, headerExtensionOf(methodPointer));
			} else {
				return extractBits(7, 14, headerExtensionOf(methodPointer));
			}
		}
		return 0;
	}
	
	protected static int methodClassOf(int methodPointer) {
		int literalCount = literalCountOf(methodPointer);
		int association = literal(literalCount - 1, methodPointer);
		int assocClass = Memory.fetchClassOf(association);
		if (assocClass == Well.known().ClassAssociationPointer) {
			return Memory.fetchPointer(Well.known().ValueIndex, association);
		} else {
			System.out.printf("## methodClassOf(0x%04X): association not of class Association, but: %s\n", methodPointer, Memory.getClassName(assocClass));
			return Well.known().NilPointer;
		}
	}
	
	/*
	 * Bluebook p. 580..: "Contexts"
	 */
	
	protected static int instructionPointerOfContext(int contextPointer) {
		return fetchInteger(Well.known().InstructionPointerIndex, contextPointer);
	}
	
	protected static void storeInstructionPointerValue(int value, int contextPointer) {
		storeInteger(Well.known().InstructionPointerIndex, contextPointer, value);
	}
	
	protected static int stackPointerOfContext(int contextPointer) {
		return fetchInteger(Well.known().StackPointerIndex, contextPointer);
	}
	
	protected static void storeStackPointerValue(int value, int contextPointer) {
		storeInteger(Well.known().StackPointerIndex, contextPointer, value);
	}
	
	protected static int argumentCountOfBlock(int blockPointer) {
		return fetchInteger(Well.known().BlockArgumentCountIndex, blockPointer);
	}
	
	protected static void fetchContextRegisters() {
		if (isBlockContext(activeContext)) {
			homeContext = Memory.fetchPointer(Well.known().HomeIndex, activeContext);
		} else {
			homeContext = activeContext;
		}
		receiver = Memory.fetchPointer(Well.known().ReceiverIndex, homeContext);
		method = Memory.fetchPointer(Well.known().MethodIndex, homeContext);
		instructionPointer = instructionPointerOfContext(activeContext) - 1;
		stackPointer = stackPointerOfContext(activeContext) + Well.known().TempFrameStart - 1;
	}
	
	protected static boolean isBlockContext(int contextPointer) {
		int methodOrArguments = Memory.fetchPointer(Well.known().MethodIndex, contextPointer);
		return Memory.isIntegerObject(methodOrArguments);
	}
	
	protected static void storeContextRegisters() {
		storeInstructionPointerValue(instructionPointer + 1, activeContext);
		storeStackPointerValue(stackPointer - Well.known().TempFrameStart + 1, activeContext);
	}
	
	public static void push(int object) {
		stackPointer += 1;
		Memory.storePointer(stackPointer, activeContext, object);
	}
	
	public static int popStack() {
		int stackTop = Memory.fetchPointer(stackPointer, activeContext);
		stackPointer -= 1;
		return stackTop;
	}
	
	public static int stackTop() {
		return  Memory.fetchPointer(stackPointer, activeContext);
	}
	
	public static int stackValue(int offset) {
		return  Memory.fetchPointer(stackPointer - offset, activeContext);
	}
	
	public static void pop(int number) {
		stackPointer -= number;
	}
	
	public static void unPop(int number) {
		stackPointer += number;
	}
	
	protected static void newActiveContext(int aContext) {
		storeContextRegisters();
		Memory.decreaseReferencesTo(activeContext);
		activeContext = aContext;
		Memory.increaseReferencesTo(activeContext);
		fetchContextRegisters();
	}
	
	protected static int sender() {
		return Memory.fetchPointer(Well.known().SenderIndex, homeContext);
	}
	
	protected static int caller() {
		return Memory.fetchPointer(Well.known().SenderIndex, activeContext);
	}
	
	protected static int temporary(int offset) {
		return Memory.fetchPointer(offset + Well.known().TempFrameStart, homeContext);
	}
	
	protected static int literal(int offset) {
		return literal(offset, method);
	}
	
	// additional
	public static int argumentCount() {
		return argumentCount;
	}
	
	/*
	 * Bluebook p. 586..: "Classes"
	 */
	
	protected static int hash(int objectPointer) {
		return Memory.objectPointerAsOop(objectPointer); // works for Bluebook and for DV6-Stretch
//		return (objectPointer >> 1) & 0x7FFF; // works only for Bluebook, but not for DV6-Strech as SmallInteger representation changed
	}
	
	protected static boolean lookupMethodInDictionary(int dictionary) {
		int length = Memory.fetchWordLengthOf(dictionary);
//		if (isStretch) {
//			for (int idx = Well.known().SelectorStart; idx < length; idx++) {
//				int nextSelector = Memory.fetchPointer(idx, dictionary);
//				if (nextSelector == messageSelector) {
//					int methodArray = Memory.fetchPointer(Well.known().MethodArrayIndex, dictionary);
//					newMethod = Memory.fetchPointer(idx - Well.known().SelectorStart, methodArray);
//					primitiveIndex = primitiveIndexOf(newMethod);
//					return true;
//				}
//			}
//			return false;
//		}
		int mask = length - Well.known().SelectorStart - 1;
		int index = (mask & hash(messageSelector)) + Well.known().SelectorStart;
		int startIndex = index;
		while(true) {
			int nextSelector = Memory.fetchPointer(index, dictionary);
			if (nextSelector == Well.known().NilPointer) {
				return false;
			}
			if (nextSelector == messageSelector) {
				int methodArray = Memory.fetchPointer(Well.known().MethodArrayIndex, dictionary);
				newMethod = Memory.fetchPointer(index - Well.known().SelectorStart, methodArray);
				primitiveIndex = primitiveIndexOf(newMethod);
				return true;
			}
			index += 1;
			if (index == length) {
				index = Well.known().SelectorStart;
			}
			if (index == startIndex) {
				return false;
			}
		}
	}
	
	protected static boolean lookupMethodInClass(int classPointer) {
		int currentClass = classPointer;
		while (currentClass != Well.known().NilPointer) {
			int dictionary = Memory.fetchPointer(Well.known().MessageDictionaryIndex, currentClass);
			if (lookupMethodInDictionary(dictionary)) {
				return true;
			}
			currentClass = superclassOf(currentClass);
		}
		if (messageSelector == Well.known().DoesNotUnderstandSelector) {
			throw new RuntimeException("Recursive not understood error encountered");
		}
		createActualMessage();
		messageSelector = Well.known().DoesNotUnderstandSelector;
		return lookupMethodInClass(classPointer);
	}
	
	protected static int superclassOf(int classPointer) {
		return Memory.fetchPointer(Well.known().SuperclassIndex, classPointer);
	}
	
	protected static void createActualMessage() {
		int argumentArray = Memory.instantiateClassWithPointers(Well.known().ClassArrayPointer, argumentCount);
		int message = Memory.instantiateClassWithPointers(Well.known().ClassMessagePointer, Well.known().MessageSize);
		Memory.storePointer(Well.known().MessageSelectorIndex, message, messageSelector);
		Memory.storePointer(Well.known().MessageArgumentsIndex, message, argumentArray);
		transfer(argumentCount, stackPointer - (argumentCount - 1), activeContext, 0, argumentArray);
		pop(argumentCount);
		push(message);
		argumentCount = 1;
	}
	
	protected static int instanceSpecificationOf(int classPointer) {
		return Memory.fetchPointer(Well.known().InstanceSpecificationIndex, classPointer);
	}
	
	public static boolean isPointers(int classPointer) {
		int pointersFlag = extractBits(0, 0, instanceSpecificationOf(classPointer));
		return (pointersFlag == 1);
	}
	
	public static boolean isWords(int classPointer) {
		int wordsFlag = extractBits(1, 1, instanceSpecificationOf(classPointer));
		return (wordsFlag == 1);
	}
	
	public static boolean isIndexable(int classPointer) {
		int indexableFlag = extractBits(2, 2, instanceSpecificationOf(classPointer));
		return (indexableFlag == 1);
	}
	
	public static int fixedFieldsOf(int classPointer) {
		if (isStretch) {
			return extractBits(3, 13, instanceSpecificationOf(classPointer));
		} else {
			return extractBits(4, 14, instanceSpecificationOf(classPointer));
		}
	}
	
	/*
	 * initialization for restarting a freshly loaded virtual image
	 */
	
	public static void setVirtualImageRestartContext(int restartContext) {
		activeContext = restartContext;
		Memory.increaseReferencesTo(activeContext);
		fetchContextRegisters();
	}
	
	/*
	 * Test & debugging support
	 */
	
	public static void disassembleActiveMethodContext() {
		disassembleMethodContext(activeContext);
	}
	
	public static void disassembleMethodContext(int contextPointer) {
		// setup
		isStretch = Memory.isStretch();
		
		// get the method-context
		OTEntry context = Memory.ot(contextPointer);
		if (context.getClassOOP() == Well.known().ClassBlockContextPointer) {
			System.out.println("disassemble(): redirecting form BlockContext to MethodContext");
			contextPointer = Memory.fetchPointer(Well.known().HomeIndex, contextPointer);
			context = Memory.ot(contextPointer);
		}
		if (context.getClassOOP() != Well.known().ClassMethodContextPointer) {
			throw new RuntimeException("disassemble(): not a MethodContext");
		}
		
		// get the characteristics of the method-context
		int receiver = context.fetchPointerAt(Well.known().ReceiverIndex);
		int sender = context.fetchPointerAt(Well.known().SenderIndex);
		int instructionPointer = instructionPointerOfContext(contextPointer); // 'real' int (as already converted from SmallInteger), 1-based from object-fields start 
		int stackPointer = stackPointerOfContext(contextPointer); // 'real' int (as already converted from SmallInteger), 1-based from tempFrame start
		
		// get characteristics of the invoked method(message)
		int methodPointer = context.fetchPointerAt(Well.known().MethodIndex);
		OTEntry method = Memory.ot(methodPointer);
		int temporaryCount = temporaryCountOf(methodPointer);
		int largeContextFlag = largeContextFlagOf(methodPointer);
		int literalCount = literalCountOf(methodPointer);
		int initialInstructionPointer = initialInstructionPointerOfMethod(methodPointer); // 'real' int, 1-based from object-fields start
		int flagValue = flagValueOf(methodPointer);
		int argumentCount = argumentCountOf(methodPointer);
		int primitiveIndex = primitiveIndexOf(methodPointer);
		int methodClass = methodClassOf(methodPointer);
		
		int stackBase = Well.known().TempFrameStart + temporaryCount;
		int stackP = stackPointer + Well.known().TempFrameStart - 1;
		int currInstrPointer = instructionPointer - 1; // 0-based
		int instrBase = initialInstructionPointer - 1; // 0-based
		int instructionBytes = method.getByteLength() - instrBase - (method.oddLength() ? 1 : 0);
		
		// give some overview info
		logf("\nMethodContext: 0x%04X\n", contextPointer);
		logf("  - raw[%d]: ", context.getWordLength());
		for (int i = 0; i < context.getWordLength(); i++) { logf(" %04X", context.fetchWordAt(i)); }
		logln("");
		logf("  - sender............: 0x%04X\n", sender);
		logf("  - receiver..........: 0x%04X\n", receiver);
		logf("  - instructionPointer: 0x%04X\n", instructionPointer);
		logf("  - stackPointer......: %d (stackBase: %d, stackP: %d => stackDepth = %d)\n", stackPointer, stackBase, stackP, stackP - stackBase + 1);
		logf("       eval-stack: "); for (int i = stackBase; i <= stackP; i++) { logf(" 0x%04X", context.fetchWordAt(i)); } logln("");
		logf("  - methodPointer.....: %s\n", method.toString());
		logf("     - literalCount......: %d\n", literalCount);
		logf("     - argumentCount.....: %d\n", argumentCount);
		logf("     - temporaryCount....: %d\n", temporaryCount);
		logf("     - largeContext......: %s\n", Boolean.toString(largeContextFlag == 1));
		logf("     - flagValue.........: %d\n", flagValue);
		logf("     - primitiveIndex....: %d\n", primitiveIndex);
		logf("     - methodClass.......: 0x%04X\n", methodClass);
		logf("     - initialInstr.Pnter: %d\n", initialInstructionPointer);
		logf("     - instructions start: %d (byte-offset)\n", instrBase);
		logf("     - instruction bytes.: %d\n", instructionBytes);
		
		// dump the literals
		logln("     - literals:");
		for (int i = 0; i < literalCount; i++) {
			OTEntry lit = Memory.ot(literal(i, methodPointer));
			String str = (lit.getClassOOP() == Well.known().ClassSymbolPointer) ? "#[ " + getSymbolText(lit) + " ]" : lit.toString();
			logf("       - [%d]: %s\n", i, str);
		}
		
		// dump the raw code
		int wordsDumped = 0;
		int wordIdx =  Well.known().LiteralStartIndex + literalCount;
		int limit = method.getWordLength();
		logf("     - raw instruction code (hex, words):");
		while (wordIdx < limit) {
			if ((wordsDumped % 16) == 0) {
				logf("\n        ");
			}
			logf(" %04X", method.fetchWordAt(wordIdx));
			wordIdx++;
			wordsDumped++;
		}
		logln("");
		
		// do the disassembling
		logln("     - instructions:");
		int ip = 0;
		String[] options1 = {"receiver", "true", "false", "nil", "-1", "0", "1", "2"};
		String[] options2 = {"Receiver Variable", "Temporary Location", "Literal Constant", "Literal Variable"};
		while(ip < instructionBytes) {
			int instrIp = ip;
			int code = method.fetchByteAt(instrBase + ip);
			ip++;
			logf("       %s ip = %3d - %3d ", ((instrIp + instrBase) == currInstrPointer) ? ">" : " ", instrIp, code); 
			if (inRange(code, 0, 15)) {
				logf("push receiver variable #%d", code & 0x0F);
			} else if (inRange(code, 16, 31)) {
				logf("push temporary location #%d", code & 0x0F);
			} else if (inRange(code, 32, 63)) {
				logf("push literal constant #%d", code & 0x1F);
			} else if (inRange(code, 64, 95)) {
				logf("push literal variable #%d", code & 0x1F);
			} else if (inRange(code, 96, 103)) {
				logf("pop and store receiver variable #%d", code & 0x07);
			} else if (inRange(code, 104, 111)) {
				logf("pop and store temporary location #%d", code & 0x07);
			} else if (inRange(code, 112, 119)) {
				logf("push %s", options1[code & 0x07]);
			} else if (inRange(code, 120, 123)) {
				logf("return %s from message", options1[code & 0x03]);
			} else if (code == 124) {
				logf("return stack-top from message");
			} else if (code == 125) {
				logf("return stack-top from block");
			} else if (code == 128) {
				int ext = method.fetchByteAt(instrBase + ip);
				ip++;
				int opt = (ext >> 6) & 0x03;
				logf("push %s #%d", options2[opt], ext & 0x3F);
			} else if (code == 129) {
				int ext = method.fetchByteAt(instrBase + ip);
				ip++;
				int opt = (ext >> 6) & 0x03;
				logf("store %s #%d", (opt == 2) ? "Illegal" : options2[opt], ext & 0x3F);
			} else if (code == 130) {
				int ext = method.fetchByteAt(instrBase + ip);
				ip++;
				int opt = (ext >> 6) & 0x03;
				logf("pop and store %s #%d", (opt == 2) ? "Illegal" : options2[opt], ext & 0x3F);
			} else if (code == 131) {
				int ext = method.fetchByteAt(instrBase + ip);
				ip++;
				logf("send literal selector #%d with %d arguments", ext & 0x1F, (ext >> 5) & 0x07);
			} else if (code == 132) {
				int ext1 = method.fetchByteAt(instrBase + ip);
				ip++;
				int ext2 = method.fetchByteAt(instrBase + ip);
				ip++;
				logf("send literal selector #%d with %d arguments", ext2, ext1);
			} else if (code == 133) {
				int ext = method.fetchByteAt(instrBase + ip);
				ip++;
				logf("send literal selector #%d to superclass with %d arguments", ext & 0x1F, (ext >> 5) & 0x07);
			} else if (code == 134) {
				int ext1 = method.fetchByteAt(instrBase + ip);
				ip++;
				int ext2 = method.fetchByteAt(instrBase + ip);
				ip++;
				logf("send literal selector #%d to superclass with %d arguments", ext2, ext1);
			} else if (code == 135) {
				logf("pop stack-top");
			} else if (code == 136) {
				logf("duplicate stack-top");
			} else if (code == 137) {
				logf("push active-context");
			} else if (inRange(code, 144, 151)) {
				logf("jump %d", (code & 0x07) + 1);
			} else if (inRange(code, 152, 159)) {
				logf("pop and jump on false %d", (code & 0x07) + 1);
			} else if (inRange(code, 160, 167)) {
				int ext = method.fetchByteAt(instrBase + ip);
				ip++;
				int rawLower = code & 0x07;
				logf("jump %d", ((rawLower - 4) * 256) + ext);
			} else if (inRange(code, 168, 171)) {
				int ext = method.fetchByteAt(instrBase + ip);
				ip++;
				int rawLower = code & 0x03;
				logf("pop and jump on true %d", (rawLower * 256) + ext);
			} else if (inRange(code, 172, 175)) {
				int ext = method.fetchByteAt(instrBase + ip);
				ip++;
				int rawLower = code & 0x03;
				logf("pop and jump on false %d", (rawLower * 256) + ext);
			} else if (inRange(code, 176, 191)) {
				logf("send arithmetic message #%d", code & 0x0F);
			} else if (inRange(code, 192, 207)) {
				logf("send special message #%d", code & 0x0F);
			} else if (inRange(code, 208, 223)) {
				logf("send literal selector #%d with no arguments", code & 0x0F);
			} else if (inRange(code, 224, 239)) {
				logf("send literal selector #%d with 1 argument", code & 0x0F);
			} else if (inRange(code, 240, 255)) {
				logf("send literal selector #%d with 2 arguments", code & 0x0F);
			} else {
				logf("?unused/invalid?");
			}
			logln("");
		}
	}
	
	private static void logf(String pattern, Object... args) {
		System.out.printf(pattern, args);
	}
	
	private static void logln(String text) {
		System.out.println(text);
	}
	
	private static StringBuilder tempSb = new StringBuilder();
	private static String getSymbolText(OTEntry symbol) {
		if (symbol.getClassOOP() != Well.known().ClassSymbolPointer) {
			return "";
		}
		tempSb.setLength(0);
		for (int i = 0; i < symbol.getByteLength(); i++) {
			tempSb.append((char)symbol.fetchByteAt(i));
		}
		return tempSb.toString();
	}
	
	private static boolean inRange(int value, int lower, int upper) {
		return (value >= lower && value <= upper);
	}

}
