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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.alto.AltoDisk;
import dev.hawala.st80vm.d11xx.Dv6Specifics;
import dev.hawala.st80vm.d11xx.TajoDisk;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.Well;
import dev.hawala.st80vm.primitives.BitBlt;
import dev.hawala.st80vm.primitives.FloatIeee;
import dev.hawala.st80vm.primitives.InputOutput;
import dev.hawala.st80vm.primitives.SmallInteger;
import dev.hawala.st80vm.primitives.Storage;

/**
 * Interpreter for Smalltalk bytecode as defined in the Bluebook, implementing
 * the main interpreter loop, all byte-code instructions and a set of the more
 * interpreter-related primitives (control primitives, array primitives).
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Interpreter extends InterpreterBase {
	
	protected Interpreter() { }
	
	/*
	 * statistics
	 */
	
	private static long startTs = 0;
	private static long instrCount = 0;
	private static long messageAsSmalltalkCount = 0;
	private static long messageAsPrimitiveCount = 0;
	private static long messageAsSpecialPrimitiveCount = 0;
	private static long methodCacheHits = 0;
	private static long methodCacheFails = 0;
	private static long methodCacheResets = 0;
	private static long maxInstrPerSsecond = 0;
	private static long maxMsgPerSecond = 0;
	
	public static void printStats(PrintStream ps) {
		long uptimeMs = System.currentTimeMillis() - startTs;
		long instrPerSecond = instrCount * 1000 / uptimeMs;
		long totalMsgs = messageAsSmalltalkCount + messageAsPrimitiveCount + messageAsSpecialPrimitiveCount;
		long msgPerSecond = totalMsgs * 1000 / uptimeMs;
		long cacheUsage = methodCacheHits + methodCacheFails;
		long cacheHitPart = (methodCacheHits * 1000) / cacheUsage;
		ps.printf("uptime: %d ms , total instructions: %d (avg: %dk/s , max: %dk/s)\n", uptimeMs, instrCount, instrPerSecond/1000, maxInstrPerSsecond / 1000);
		ps.printf(
			"messages as :: smalltalk: %d , primitive: %d , specialPrimitive: %d (total: %d , avg: %dk/s , max: %dk/s)\n",
			messageAsSmalltalkCount, messageAsPrimitiveCount, messageAsSpecialPrimitiveCount, totalMsgs, msgPerSecond / 1000, maxMsgPerSecond / 1000);
		ps.printf(
			"method cache :: hits: %d , fails: %d , resets: %d (total: %d , hit-%%: %2.1f)\n",
			methodCacheHits, methodCacheFails, methodCacheResets, cacheUsage, (float)(cacheHitPart / 10));
	}
	
	/*
	 * Bluebook p. 594: interpreter
	 */
	
	private static int fetchByte() {
		int b = Memory.fetchByte(instructionPointer, method);
		instructionPointer += 1;
		return b;
	}
	
	public static void interpret() {
		// setup the interpretation machine
		isStretch = Memory.isStretch();
		installInstructions();
		installPrimitives();
		startTs = System.currentTimeMillis();
		
		// the interpretation loop is left either:
		// -> with a 'QuitSignal' exception sent by primitives 113 (Quit) or 114 (ExitToDebugger)
		// -> or with a RuntimeException in case of serious implementation error (or misbehaving Smalltalk code)
		while(true) {
			checkProcessSwitch(); // this also does some throttling when synchronizing with event-handling, also does screen refreshs
			int currentBytecode = fetchByte();
			if (Config.LOG_INSTRS) {
				System.out.printf("\n[%d] : <%d> %s ", instrCount, currentBytecode, instrNames[currentBytecode]);
			}
			instructions[currentBytecode].execute(currentBytecode);
			instrCount++;
		}
	}
	
	/*
	 * instruction dispatch table and instruction installation
	 */
	
	private static final String[] instrNames = new String[256];

	@FunctionalInterface
	private interface Instruction {
		void execute(int byteCode);
	}
	
	private static final Instruction[] instructions = new Instruction[256];
	
	private static final Instruction UNIMPLEMENTED_INSTRUCTION = byteCode -> {
		throw new RuntimeException("Unimplemented smalltalk instruction byteCode: " + byteCode);
	};
	
	private static void installSingle(int byteCode, Instruction impl, String name) {
		// possibly add logging?
		instructions[byteCode] = impl;
		instrNames[byteCode] = name;
	}
	
	private static void installRange(int fromByteCode, int toByteCode, Instruction impl, String name) {
		for (int i = fromByteCode; i <= toByteCode; i++) {
			installSingle(i, impl, name);
		}
	}
	
	private static void installInstructions() {
		
		// assign instruction implementations to byteCodes according to Bluebook
		installRange(0, 15, pushReceiverVariable, "pushReceiverVariable");
		installRange(16, 31, pushTemporaryVariable, "pushTemporaryVariable");
		installRange(32, 63, pushLiteralConstant, "pushLiteralConstant");
		installRange(64, 95, pushLiteralVariable, "pushLiteralVariable");
		installRange(96, 103, storeAndPopReceiverVariable, "storeAndPopReceiverVariable");
		installRange(104, 111, storeAndPopTemporaryVariable, "storeAndPopTemporaryVariable");
		installSingle(112, pushReceiver, "pushReceiver");
		installSingle(113, pushTrue, "pushTrue");
		installSingle(114, pushFalse, "pushFalse");
		installSingle(115, pushNil, "pushNil");
		installSingle(116, pushMinusOne, "pushMinusOne");
		installSingle(117, pushZero, "pushZero");
		installSingle(118, pushOne, "pushOne");
		installSingle(119, pushTwo, "pushTwo");
		installSingle(120, returnReceiver,            "returnReceiver                               RETURN receiver");
		installSingle(121, returnTrue,                "returnTrue                                   RETURN true");
		installSingle(122, returnFalse,               "returnFalse                                  RETURN false");
		installSingle(123, returnNil,                 "returnNil                                    RETURN nil");
		installSingle(124, returnStackTopFromMessage, "returnStackTopFromMessage                    RETURN (stack top from message)");
		installSingle(125, returnStackTopFromBlock,   "returnStackTopFromBlock                      RETURN (stack top from block)");
		/* 126-127: unused */ 
		installSingle(128, extendedPush, "extendedPush");
		installSingle(129, extendedStore, "extendedStore");
		installSingle(130, extendedStoreAndPop, "extendedStoreAndPop");
		installSingle(131, singleExtendedSend,        "singleExtendedSend        SEND msg");
		installSingle(132, doubleExtendedSend,        "doubleExtendedSend        SEND msg");
		installSingle(133, singleExtendedSuper,       "singleExtendedSuper       SEND super msg");
		installSingle(134, doubleExtendedSuper,       "doubleExtendedSuper       SEND super msg");
		installSingle(135, popStack, "popStack");
		installSingle(136, duplicateTop, "duplicateTop");
		installSingle(137, pushActiveContext, "pushActiveContext");
		/* 138-143: unused */ 
		installRange(144, 151, shortUnconditionalJump, "shortUnconditionalJump");
		installRange(152, 159, shortConditionalJump, "shortConditionalJump");
		installRange(160, 167, longUnconditionalJump, "longUnconditionalJump");
		installRange(168, 175, longConditionalJump, "longConditionalJump");
		installRange(176, 207, sendSpecialSelector,   "sendSpecialSelector       SEND special selector");
		installRange(208, 255, sendLiteralSelector,   "sendLiteralSelector       SEND literal selector");
		

		// set all remaining byteCodes to "unimplemented" abort
		for (int i = 0; i < instructions.length; i++) {
			if (instructions[i] == null) {
				instructions[i] = UNIMPLEMENTED_INSTRUCTION;
				instrNames[i] = "invalid bytecode #" + i;
			}
		}
		
	}
	
	private static final String[] extendedOptions = {"Receiver Variable", "Temporary Location", "Literal Constant", "Literal Variable"};
	// if (Config.LOG_INSTRS) { System.out.printf("", ); }
	
	/*
	 * implementation of: Stack Bytecodes 
	 */
	
	private static Instruction pushReceiverVariable = byteCode -> {
		int fieldIndex = byteCode & 0x0F;
		int value = Memory.fetchPointer(fieldIndex, receiver);
		if (Config.LOG_INSTRS) { System.out.printf(" fieldIndex: %d , value:0x%04X", fieldIndex, value); }
		push(value);
	};
	
	private static Instruction pushTemporaryVariable = byteCode -> {
		int fieldIndex = byteCode & 0x0F;
		int value = temporary(fieldIndex);
		if (Config.LOG_INSTRS) { System.out.printf(" fieldIndex: %d , value:0x%04X", fieldIndex, value); }
		push(value);
	};
	
	private static Instruction pushLiteralConstant = byteCode -> {
		int fieldIndex = byteCode & 0x1F;
		int value = literal(fieldIndex);
		if (Config.LOG_INSTRS) { System.out.printf(" fieldIndex: %d , value:0x%04X", fieldIndex, value); }
		push(value);
	};
	
	private static Instruction pushLiteralVariable = byteCode -> {
		int fieldIndex = byteCode & 0x1F;
		int association = literal(fieldIndex);
		int value = Memory.fetchPointer(Well.known().ValueIndex, association);
		if (Config.LOG_INSTRS) { System.out.printf(" fieldIndex: %d , value:0x%04X", fieldIndex, value); }
		push(value);
	};
	
	private static Instruction extendedPush = byteCode -> {
		int descriptor = fetchByte();
		int variableType = extractBits(8, 9, descriptor);
		int variableIndex = extractBits(10, 15, descriptor);
		final int value;
		if (variableType == 0) {
			value = Memory.fetchPointer(variableIndex, receiver);
		} else if (variableType == 1) {
			value = temporary(variableIndex);
		} else if (variableType == 2) {
			value = literal(variableIndex);
		} else {
			int association = literal(variableIndex);
			value = Memory.fetchPointer(Well.known().ValueIndex, association);
		}
		if (Config.LOG_INSTRS) { System.out.printf(" variableType: %s , variableIndex: %d, value: 0x%04X", extendedOptions[variableType], variableIndex, value); }
		push(value);
	};
	
	private static Instruction pushReceiver = byteCode -> push(receiver);
	
	private static Instruction duplicateTop = byteCode -> push(stackTop());
	
	private static Instruction pushTrue = byteCode -> push(Well.known().TruePointer);
	
	private static Instruction pushFalse = byteCode -> push(Well.known().FalsePointer);
	
	private static Instruction pushNil = byteCode -> push(Well.known().NilPointer);
	
	private static Instruction pushMinusOne = byteCode -> push(Well.known().MinusOnePointer);
	
	private static Instruction pushZero = byteCode -> push(Well.known().ZeroPointer);
	
	private static Instruction pushOne = byteCode -> push(Well.known().OnePointer);
	
	private static Instruction pushTwo = byteCode -> push(Well.known().TwoPointer);
	
	private static Instruction pushActiveContext = byteCode -> push(activeContext);
	
	private static Instruction storeAndPopReceiverVariable = byteCode -> {
		int variableIndex = extractBits(13, 15, byteCode);
		int value = popStack();
		if (Config.LOG_INSTRS) { System.out.printf(" variableIndex: %d , value: 0x%04X", variableIndex, value); }
		Memory.storePointer(variableIndex, receiver, value);
	};
	
	private static Instruction storeAndPopTemporaryVariable = byteCode -> {
		int variableIndex = extractBits(13, 15, byteCode);
		int value = popStack();
		if (Config.LOG_INSTRS) { System.out.printf(" variableIndex: %d , value: 0x%04X", variableIndex, value); }
		Memory.storePointer(variableIndex + Well.known().TempFrameStart, homeContext, value);
	};
	
	private static Instruction extendedStore = byteCode -> {
		int descriptor = fetchByte();
		int variableType = extractBits(8, 9, descriptor);
		int variableIndex = extractBits(10, 15, descriptor);
		final int value = stackTop();
		if (Config.LOG_INSTRS) { System.out.printf(" variableType: %s , variableIndex: %d, value: 0x%04X", extendedOptions[variableType], variableIndex, value); }
		if (variableType == 0) {
			Memory.storePointer(variableIndex, receiver, value);
		} else if (variableType == 1) {
			Memory.storePointer(variableIndex + Well.known().TempFrameStart, homeContext, value);
		} else if (variableType == 2) {
			throw new RuntimeException("extendedStoreBytecode :: illegal store (variableType == 2)");
		} else {
			int association = literal(variableIndex);
			Memory.storePointer(Well.known().ValueIndex, association, value);
		}
	};
	
	private static Instruction extendedStoreAndPop = byteCode -> {
		extendedStore.execute(byteCode);
		popStack();
	};
	
	private static Instruction popStack = byteCode -> {
		popStack();
	};
	
	/*
	 * implementation of: Jump Bytecodes 
	 */
	
	private static void jump(int offset) {
		if (Config.LOG_INSTRS) { System.out.printf(" jumping with offset: %d", offset); }
		instructionPointer += offset;
	}
	
	private static Instruction shortUnconditionalJump = byteCode -> {
		int offset = extractBits(13, 15, byteCode);
		jump(offset + 1);
	};
	
	private static Instruction longUnconditionalJump = byteCode -> {
		int offset = extractBits(13, 15, byteCode);
		jump(((offset - 4) << 8) + fetchByte());
	};
	
	private static void jumpIf(int conditionPointer, int offset) {
		int booleanPointer = popStack();
		if (Config.LOG_INSTRS) { System.out.printf(" condition: 0x%04X , actual: 0x%04X", conditionPointer, booleanPointer); }
		if (booleanPointer == conditionPointer) {
			jump(offset);
		} else if (booleanPointer != Well.known().TruePointer && booleanPointer != Well.known().FalsePointer) {
			System.out.printf("\n###\n##### jumpIf() -> mustBeBoolean: %s\n###\n\n", Memory.ot(booleanPointer).toString());
			unPop(1);
			sendMustBeBoolean();
		}
	}
	
	private static void sendMustBeBoolean() {
		sendSelector(Well.known().MustBeBooleanSelector, 0);
	}
	
	private static Instruction shortConditionalJump = byteCode -> {
		int offset = extractBits(13, 15, byteCode);
		jumpIf(Well.known().FalsePointer, offset + 1);
	};
	
	private static Instruction longConditionalJump = byteCode -> {
		int offset = extractBits(14, 15, byteCode);
		offset = (offset << 8) + fetchByte();
		if (byteCode >= 168 && byteCode <= 171) {
			jumpIf(Well.known().TruePointer, offset);
		} else {
			jumpIf(Well.known().FalsePointer, offset);
		}
		
	};
	
	/*
	 * implementation of: Send Bytecodes 
	 */
	
	// implementation specific: handle CPU throttling on "yield" messages
	// purpose: reduce usage of the underlying real CPU when the Smalltalk environment seems to be idle
	// -> a 'yield' message waits 10 ms before continuing
	// -> an enqueued asynchronous event (mouse, keyboard) stops the wait phase and lets the machine run again immediately
	private static final int WAIT_ON_YIELD = 10; // milliseconds
	private static final Object yieldSyncer = new Object();
	
	private static void sendSelector(int selector, int count) {
		messageSelector = selector;
		argumentCount = count;
		int newReceiver = stackValue(count);
		if (Config.LOG_INSTRS) {
			String selectorName = Memory.getSymbolText(messageSelector);
			String receiverClassname = Memory.getClassNameOf(newReceiver);
			System.out.printf("\n=> on 0x%04X (%s) >>> %s",
					newReceiver,
					receiverClassname,
					selectorName);
		}
		if (messageSelector == Well.known().YieldSelector) {
			synchronized(yieldSyncer) {
				try { yieldSyncer.wait(WAIT_ON_YIELD); } catch (InterruptedException e) { }
			}
		}
		sendSelectorToClass(Memory.fetchClassOf(newReceiver));
	}
	
	private static void sendSelectorToClass(int classPointer) {
		findNewMethodInClass(classPointer);
		executeNewMethod();
	}
	
	private static final int[] methodCache = new int[1024]; // for method caching as described in Bluebook p. 605
	
	private static void findNewMethodInClass(int classPointer) {
//		int hash = ((messageSelector & classPointer) & 0x00FF) << 2; // Bluebook version, but: 1. lowest bit is same for both, 2. better use XOR instead of AND ??
		int hash = ((messageSelector ^ classPointer) & 0x01FE) << 1;
		if (methodCache[hash] == messageSelector && methodCache[hash + 1] == classPointer) {
			newMethod = methodCache[hash + 2];
			primitiveIndex = methodCache[hash + 3];
			methodCacheHits++;
		} else {
			lookupMethodInClass(classPointer);
			methodCache[hash] = messageSelector;
			methodCache[hash + 1] = classPointer;
			methodCache[hash + 2] = newMethod;
			methodCache[hash + 3] = primitiveIndex;
			methodCacheFails++;
		}
	}
	
	private static void executeNewMethod() {
		if (!primitiveResponse()) {
			messageAsSmalltalkCount++;
			activateNewMethod();
		} else {
			messageAsPrimitiveCount++;
		}
	}
	
	private static void activateNewMethod() {
		int contextSize = (largeContextFlagOf(newMethod) == 1)
				? 32 + Well.known().TempFrameStart
				: 12 + Well.known().TempFrameStart;
		int newContext = Memory.instantiateClassWithPointers(Well.known().ClassMethodContextPointer, contextSize);
		Memory.storePointer(Well.known().SenderIndex, newContext, activeContext);
		storeInstructionPointerValue(initialInstructionPointerOfMethod(newMethod), newContext);
		storeStackPointerValue(temporaryCountOf(newMethod), newContext);
		Memory.storePointer(Well.known().MethodIndex, newContext, newMethod);
		transfer(
			argumentCount + 1,
			stackPointer - argumentCount,
			activeContext,
			Well.known().ReceiverIndex,
			newContext);
		pop(argumentCount + 1);
		newActiveContext(newContext);
	}
	
	
	private static Instruction sendLiteralSelector = byteCode -> {
		int selector = literal(extractBits(12, 15, byteCode));
		int argumentCount = extractBits(10, 11, byteCode) - 1;
		sendSelector(selector, argumentCount);
	};
	
	
	private static Instruction singleExtendedSend = byteCode -> {
		int descriptor = fetchByte();
		int selectorIndex = extractBits(11, 15, descriptor);
		sendSelector(literal(selectorIndex), extractBits(8, 10, descriptor));
	};
	
	
	private static Instruction doubleExtendedSend = byteCode -> {
		int count = fetchByte();
		int selector = literal(fetchByte());
		sendSelector(selector, count);
	};
	
	
	private static Instruction singleExtendedSuper = byteCode -> {
		int descriptor = fetchByte();
		argumentCount = extractBits(8, 10, descriptor);
		int selectorIndex = extractBits(11, 15, descriptor);
		messageSelector = literal(selectorIndex);
		int methodClass = methodClassOf(method);
		sendSelectorToClass(superclassOf(methodClass));
	};
	
	
	private static Instruction doubleExtendedSuper = byteCode -> {
		argumentCount = fetchByte();
		messageSelector = literal(fetchByte());
		int methodClass = methodClassOf(method);
		sendSelectorToClass(superclassOf(methodClass));
	};
	
	
	private static Instruction sendSpecialSelector = byteCode -> {
		if (!specialSelectorPrimitiveResponse(byteCode)) {
			int selectorIndex = (byteCode - 176) * 2;
			int selector = Memory.fetchPointer(selectorIndex, Well.known().SpecialSelectorsPointer);
			int count = fetchInteger(selectorIndex + 1, Well.known().SpecialSelectorsPointer);
			sendSelector(selector, count);
		} else {
			messageAsSpecialPrimitiveCount++;
		}
	};
	
	/*
	 * implementation of: Return Bytecodes 
	 */
	
	private static void returnValue(int resultPointer, int contextPointer) {
		if (contextPointer == Well.known().NilPointer) {
			push(activeContext);
			push(resultPointer);
			sendSelector(Well.known().CannotReturnSelector, 1);
			return;
		}
		
		int sendersIP = Memory.fetchPointer(Well.known().InstructionPointerIndex, contextPointer);
		if (sendersIP == Well.known().NilPointer) {
			push(activeContext);
			push(resultPointer);
			sendSelector(Well.known().CannotReturnSelector, 1);
			return;
		}
		
		Memory.increaseReferencesTo(resultPointer);
		returnToActiveContext(contextPointer);
		push(resultPointer);
		Memory.decreaseReferencesTo(resultPointer);
	}
	
	private static void returnToActiveContext(int aContext) {
		Memory.increaseReferencesTo(aContext);
		nilContextFields();
		Memory.decreaseReferencesTo(activeContext);
		activeContext = aContext;
		fetchContextRegisters();
	}
	
	private static void nilContextFields() {
		Memory.storePointer(Well.known().SenderIndex, activeContext, Well.known().NilPointer);
		Memory.storePointer(Well.known().InstructionPointerIndex, activeContext, Well.known().NilPointer);
	}
	
	private static Instruction returnReceiver = byteCode -> returnValue(receiver, sender());
	
	private static Instruction returnTrue = bytecode -> returnValue(Well.known().TruePointer, sender());
	
	private static Instruction returnFalse = bytecode -> returnValue(Well.known().FalsePointer, sender());
	
	private static Instruction returnNil = bytecode -> returnValue(Well.known().NilPointer, sender());
	
	private static Instruction returnStackTopFromMessage = bytecode -> returnValue(popStack(), sender());
	
	private static Instruction returnStackTopFromBlock = bytecode -> returnValue(popStack(), caller());
	
	
	/*
	 * Bluebook pp. 616: support functionality for primitives
	 */
	
	@FunctionalInterface
	public interface Primitive {
		boolean execute();
	}
	
	public static int popInteger() {
		int integerPointer = popStack();
		if (success(Memory.isIntegerObject(integerPointer))) {
			return Memory.integerValueOf(integerPointer);
		}
		return INVALID_INTEGER; // already failed, but we must return something =>last Integer => out-of-bounds when accessing anything in virtual memory
	}
	
	public static void pushInteger(int integerValue) {
		push(Memory.integerObjectOf(integerValue));
	}
	
	public static int positive16BitIntegerFor(int integerValue) {
		if (integerValue < 0) {
			primitiveFail();
			return Well.known().NilPointer;
			
		}
		if (Memory.isIntegerValue(integerValue)) {
			return Memory.integerObjectOf(integerValue);
		}
		int newLargeInteger = Memory.instantiateClassWithBytes(Well.known().ClassLargePositivelntegerPointer, 2);
		Memory.storeByte(0, newLargeInteger, lowByteOf(integerValue));
		Memory.storeByte(1, newLargeInteger, highByteOf(integerValue));
		return newLargeInteger;
	}
	
	public static int positive16BitValueOf(int integerPointer) {
		if (Memory.isIntegerObject(integerPointer)) {
			return Memory.integerValueOf(integerPointer);
		}
		if (Memory.fetchClassOf(integerPointer) != Well.known().ClassLargePositivelntegerPointer
			|| Memory.fetchByteLengthOf(integerPointer) != 2) {
			primitiveFail();
			return INVALID_INTEGER; // we must return something, but what? (last Integer => out-of-bounds when accessing anything in virtual memory!)
		}
		int value = (Memory.fetchByte(1, integerPointer) << 8) | Memory.fetchByte(0, integerPointer);
		return value;
	}
	
	// (additional)
	public static int positive32BitValueOf(int integerPointer) {
		if (Memory.isIntegerObject(integerPointer)) {
			return Memory.integerValueOf(integerPointer);
		}
		int len = Memory.fetchByteLengthOf(integerPointer);
		if (Memory.fetchClassOf(integerPointer) != Well.known().ClassLargePositivelntegerPointer || len > 4) {
			primitiveFail();
			return INVALID_INTEGER; // we must return something, but what? (last Integer => out-of-bounds when accessing anything in virtual memory!)
		}
		int value = 0;
		if (len > 0) { value = Memory.fetchByte(0, integerPointer); }
		if (len > 1) { value |= Memory.fetchByte(1, integerPointer) << 8; }
		if (len > 2) { value |= Memory.fetchByte(2, integerPointer) << 16; }
		if (len > 3) { value |= Memory.fetchByte(3, integerPointer) << 24; }
		return value;
	}
	
	// (additional)
	public static int positive32BitIntegerFor(long integerValue) {
		if (integerValue < 0) {
			primitiveFail();
			return Well.known().NilPointer;
			
		}
		if (integerValue < 0xFFFFL && Memory.isIntegerValue((int)integerValue)) {
			return Memory.integerObjectOf((int)integerValue);
		}
		int bytes = (integerValue > 0x00FFFFFFL) ? 4 :(integerValue > 0x0000FFFFL) ? 3 : 2;
		int newLargeInteger = Memory.instantiateClassWithBytes(Well.known().ClassLargePositivelntegerPointer, bytes);
		Memory.storeByte(0, newLargeInteger, (int)(integerValue & 0xFF));
		Memory.storeByte(1, newLargeInteger, (int)((integerValue >> 8) & 0xFF));
		if (bytes > 2) { Memory.storeByte(2, newLargeInteger, (int)((integerValue >> 16) & 0xFF)); }
		if (bytes > 3) { Memory.storeByte(3, newLargeInteger, (int)((integerValue >> 24) & 0xFF)); }
		return newLargeInteger;
	}
	
	/*
	 * Bluebook pp. 618: special primitive invocation
	 */
	
	private static final Primitive[] specialArithPrimitives = {
		/*176*/ SmallInteger.primitiveAdd,
		/*177*/ SmallInteger.primitiveSubtract,
		/*178*/ SmallInteger.primitiveLessThan,
		/*179*/ SmallInteger.primitiveGreaterThan,
		/*180*/ SmallInteger.primitiveLessOrEqual,
		/*181*/ SmallInteger.primitiveGreaterOrEqual,
		/*182*/ SmallInteger.primitiveEqual,
		/*183*/ SmallInteger.primitiveNotEqual,
		/*184*/ SmallInteger.primitiveMultiply,
		/*185*/ SmallInteger.primitiveDivide,
		/*186*/ SmallInteger.primitiveMod,
		/*187*/ SmallInteger.primitiveMakePoint,
		/*188*/ SmallInteger.primitiveBitShift,
		/*189*/ SmallInteger.primitiveDiv,
		/*190*/ SmallInteger.primitiveBitAnd,
		/*191*/ SmallInteger.primitiveBitOr
	};
	
	private static boolean specialSelectorPrimitiveResponse(int byteCode) {
		initPrimitive();
		if (byteCode >= 176 && byteCode <= 191) {
			if (success(Memory.isIntegerObject(stackValue(1)))) {
				return specialArithPrimitives[byteCode - 176].execute();
			}
			return primitiveFail();
		}
		if (byteCode >= 192 && byteCode <= 207) {
			argumentCount = fetchInteger(((byteCode - 176) * 2) + 1, Well.known().SpecialSelectorsPointer);
			int receiverClass = Memory.fetchClassOf(stackValue(argumentCount));
			if (byteCode == 198) { return primitiveEquivalent.execute(); }
			if (byteCode == 199) { return primitiveClass.execute(); }
			if (byteCode == 200 ) {
				if (success(receiverClass == Well.known().ClassMethodContextPointer || receiverClass == Well.known().ClassBlockContextPointer)) {
					return primitiveBlockCopy.execute();
				}
			} else if (byteCode == 201 || byteCode == 202) {
				if (success(receiverClass == Well.known().ClassBlockContextPointer)) {
					return primitiveValue.execute();
				}
			}
		}
		return primitiveFail();
	}
	
	
	/*
	 * Bluebook pp. 620: (general) primitive invocation
	 */
	
	private static final Primitive[] allPrimitives = new Primitive[256];
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> (Interpreter)." + name); } }
	
	private static boolean primitiveResponse() {
		if (primitiveIndex == 0) {
			int flagValue = flagValueOf(newMethod);
			if (flagValue == 5) {
				return quickReturnSelf();
			}
			if (flagValue == 6) {
				return quickInstanceLoad();
			}
			return false;
		} else {
			initPrimitive();
			return allPrimitives[primitiveIndex].execute();
		}
	}
	
	private static boolean quickReturnSelf() {
		// stackTop already has the receiver == self
		return true;
	}
	
	private static boolean quickInstanceLoad() {
		int thisReceiver = popStack();
		int fieldIndex = fieldIndexOf(newMethod);
		push(Memory.fetchPointer(fieldIndex, thisReceiver));
		return true;
	}
	
	/*
	 * Bluebook pp. 627: Array and Stream Primitives
	 */
	
	private static boolean checkIndexableBoundsOf(int index, int array) {
		int clazz = Memory.fetchClassOf(array);
		return success(
				   index >= 1
				&& (index + fixedFieldsOf(clazz)) <= lengthOf(array) );
	}
	
	public static int lengthOf(int array) {
		if (isWords(Memory.fetchClassOf(array))) {
			return Memory.fetchWordLengthOf(array);
		} else {
			return Memory.fetchByteLengthOf(array);
		}
	}
	
	public static int subscript(int array, int index) {
		int clazz = Memory.fetchClassOf(array);
		if (isWords(clazz)) {
			if (isPointers(clazz)) {
				return Memory.fetchPointer(index - 1, array);
			} else {
				int value = Memory.fetchWord(index - 1, array);
				return positive16BitIntegerFor(value);
			}
		} else {
			int value = Memory.fetchByte(index - 1, array);
			return Memory.integerObjectOf(value);
			
		}
	}
	
	public static boolean subscriptStoring(int array, int index, int value) {
		int clazz = Memory.fetchClassOf(array);
		if (isWords(clazz)) {
			if (isPointers(clazz)) {
				Memory.storePointer(index - 1, array, value);
			} else {
//				int rawValue = positive16BitValueOf(value);
//				if (success()) {
//					Memory.storeWord(index - 1, array, rawValue);
//				}
				if (success(
						Memory.isIntegerObject(value)
						|| Memory.fetchClassOf(value) == Well.known().ClassLargePositivelntegerPointer)) {
					Memory.storeWord(index - 1, array, positive16BitValueOf(value));
				}
			}
		} else {
			if (success(Memory.isIntegerObject(value))) {
				Memory.storeByte(index - 1, array, Memory.integerValueOf(value));
			}
		}
		return success();
	}
	
	private static final Primitive primitiveAt = () -> { w("primitiveAt");
		int index = positive16BitValueOf(popStack());
		int array = popStack();
		int arrayClass = Memory.fetchClassOf(array);
		int result = 0;
		if (success(checkIndexableBoundsOf(index, array))) {
			index = index + fixedFieldsOf(arrayClass);
			result = subscript(array, index);
		}
		if (success()) {
			push(result);
		} else {
			unPop(2);
		}
		return success();
	};
	
	private static final Primitive primitiveAtPut = () -> { w("primitiveAtPut");
		int value = popStack();
		int index = positive16BitValueOf(popStack());
		int array = popStack();
		int arrayClass = Memory.fetchClassOf(array);
		if (success(checkIndexableBoundsOf(index, array))) {
			index = index + fixedFieldsOf(arrayClass);
			subscriptStoring(array, index, value);
		}
		if (success()) {
			push(value);
		} else {
			unPop(3);
		}
		return success();
	};
	
	private static final Primitive primitiveSize = () -> { w("primitiveSize");
		int array = popStack();
		int arrayClass = Memory.fetchClassOf(array);
		int length = positive16BitIntegerFor(lengthOf(array) - fixedFieldsOf(arrayClass));
		if (success()) {
			push(length);
		} else {
			unPop(1);
		}
		return success();
	};
	
	private static final Primitive primitiveStringAt = () -> { w("primitiveStringAt");
		int index = positive16BitValueOf(popStack());
		int array = popStack();
		int character = 0;
		if (checkIndexableBoundsOf(index, array)) {
			int ascii = Memory.integerValueOf(subscript(array, index));
			character =  Memory.fetchPointer(ascii, Well.known().CharacterTablePointer);
		}
		if (success()) {
			push(character);
		} else {
			unPop(2);
		}
		return success();
	};
	
	private static final Primitive primitiveStringAtPut = () -> { w("primitiveStringAtPut");
		int character = popStack();
		int index = positive16BitValueOf(popStack());
		int array = popStack();
		if (checkIndexableBoundsOf(index, array) && success(Memory.fetchClassOf(character) == Well.known().ClassCharacterPointer)) {
			int ascii = Memory.fetchPointer(Well.known().CharacterValueIndex, character);
			subscriptStoring(array, index, ascii);
		}
		if (success()) {
			push(character);
		} else {
			unPop(3); // Bluebook error: we got 3 items from the stack (not 2!)...
		}
		return success();
	};
	
	
	// optional (streams, p. 631):
	// - primitiveNext
	// - primitiveNextPut
	// - primitiveAtEnd
	
	
	
	/*
	 * Bluebook pp. 637: Control Primitives
	 */
	
	private static final Primitive primitiveBlockCopy = () -> { w("primitiveBlockCopy");
		int blockArgumentCount = popStack();
		int context = popStack();
		int methodContext = isBlockContext(context)
				? Memory.fetchPointer(Well.known().HomeIndex, context)
				: context;
		int contextSize = Memory.fetchWordLengthOf(methodContext);
		int newContext = Memory.instantiateClassWithPointers(Well.known().ClassBlockContextPointer, contextSize);
		int initialIP = Memory.integerObjectOf(instructionPointer + 3);
		Memory.storePointer(Well.known().InitialIPIndex, newContext, initialIP);
		Memory.storePointer(Well.known().InstructionPointerIndex, newContext, initialIP);
		storeStackPointerValue(0, newContext);
		Memory.storePointer(Well.known().BlockArgumentCountIndex, newContext, blockArgumentCount);
		Memory.storePointer(Well.known().HomeIndex, newContext, methodContext);
		push(newContext);
		return true;
	};
	
	private static final Primitive primitiveValue = () -> { w("primitiveValue");
		int blockContext = stackValue(argumentCount);
		int blockArgumentCount = argumentCountOfBlock(blockContext);
		if (success(argumentCount == blockArgumentCount)) {
			transfer(argumentCount, stackPointer - argumentCount + 1, activeContext, Well.known().TempFrameStart, blockContext);
			pop(argumentCount + 1);
			int initialIP = Memory.fetchPointer(Well.known().InitialIPIndex, blockContext);
			Memory.storePointer(Well.known().InstructionPointerIndex, blockContext, initialIP);
			storeStackPointerValue(argumentCount, blockContext);
			Memory.storePointer(Well.known().CallerIndex, blockContext, activeContext);
			newActiveContext(blockContext);
		}
		return success();
	};
	
	private static final Primitive primitiveValueWithArgs = () -> { w("primitiveValueWithArgs");
		int argumentArray = popStack();
		int blockContext = popStack();
		int blockArgumentCount = argumentCountOfBlock(blockContext);
		int arrayClass = Memory.fetchClassOf(argumentArray);
		int arrayArgumentCount = Memory.fetchWordLengthOf(argumentArray);
		if (success(arrayClass == Well.known().ClassArrayPointer && arrayArgumentCount == blockArgumentCount)) {
			transfer(arrayArgumentCount, 0, argumentArray, Well.known().TempFrameStart, blockContext);
			int initialIP = Memory.fetchPointer(Well.known().InitialIPIndex, blockContext);
			Memory.storePointer(Well.known().InstructionPointerIndex, blockContext, initialIP);
			storeStackPointerValue(arrayArgumentCount, blockContext);
			Memory.storePointer(Well.known().CallerIndex, blockContext, activeContext);
			newActiveContext(blockContext);
		} else {
			unPop(2);
		}
		return success();
	};
	
	private static final Primitive primitivePerform = () -> { w("primitivePerform");
		int performSelector = messageSelector;
		messageSelector = stackValue(argumentCount - 1);
		int newReceiver = stackValue(argumentCount);
		lookupMethodInClass(Memory.fetchClassOf(newReceiver));
		if (success(argumentCountOf(newMethod) == (argumentCount -1))) {
			int selectorIndex = stackPointer - argumentCount + 1;
			transfer(argumentCount - 1, selectorIndex + 1, activeContext, selectorIndex, activeContext);
			pop(1);
			argumentCount -= 1;
			executeNewMethod();
		} else {
			messageSelector = performSelector;
		}
		return success();
	};
	
	private static final Primitive primitivePerformWithArgs = () -> { w("primitivePerformWithArgs");
		int argumentArray = popStack();
		int arraySize = Memory.fetchWordLengthOf(argumentArray);
		int arrayClass = Memory.fetchClassOf(argumentArray);
		if (success(
				(stackPointer + arraySize) < Memory.fetchWordLengthOf(activeContext)
				&& arrayClass == Well.known().ClassArrayPointer)) {
			int performSelector = messageSelector;
			messageSelector = popStack();
			int thisReceiver = stackTop();
			argumentCount = arraySize;
			int index = 0;
			while(index < argumentCount) {
				push(Memory.fetchPointer(index, argumentArray));
				index += 1;
			}
			lookupMethodInClass(Memory.fetchClassOf(thisReceiver));
			if (success(argumentCountOf(newMethod) == argumentCount)) {
				executeNewMethod();
			} else {
				unPop(argumentCount);
				push(messageSelector);
				push(argumentArray);
				argumentCount = 2;
				messageSelector = performSelector;
			}
		} else {
			unPop(1);
		}
		return success();
	};
	
	
	// Process-related Registers of the Interpreter
	private static boolean newProcessWaiting = false;
	private static int newProcess = -1;
	private static final List<Integer> semaphoreList = new ArrayList<>();
	private static boolean stopInterpreter = false;
	
	public static void asynchronousSignal(int aSemaphore) {
		synchronized(semaphoreList) {
			semaphoreList.add(aSemaphore);
		}
		synchronized(yieldSyncer) {
			yieldSyncer.notifyAll(); // let a possible throttled 'yield' message resume
		}
	}
	
	public static void stopInterpreter() {
		synchronized(semaphoreList) {
			stopInterpreter = true;
		}
	}
	
	private static void checkAsyncs() {
		synchronized(semaphoreList) {
			if (stopInterpreter) {
				throw new QuitSignal("close window");
			}
			for (int semaphore : semaphoreList) {
				synchronousSignal(semaphore);
			}
			semaphoreList.clear();
		}
	}
	
	private static void synchronousSignal(int aSemaphore) {
		if (isEmptyList(aSemaphore)) {
			int excessSignals = Interpreter.fetchInteger(Well.known().ExcessSignalsIndex, aSemaphore);
			Interpreter.storeInteger(Well.known().ExcessSignalsIndex, aSemaphore, excessSignals + 1);
		} else {
			Memory.suspendFreeingObjects();
			resume(removeFirstLinkOfList(aSemaphore));
		}
	}
	
	private static void transferTo(int aProcess) {
		newProcessWaiting = true;
		newProcess = aProcess;
	}
	
	private static final int THROTTLE_INIT = 100; // unthrottled instruction count before next checks (async signals, screen/status refresh) 
	private static final int CHECK_INTERVAL = 2; // milliseconds
	private static int throttleCounter = THROTTLE_INIT;
	private static long nextSyncTs = 0;
	
	private static final int DISPLAY_REFRESH_RATE = 24; // ms between screen refreshs
	private static long nextDisplayRefreshTs = 0;
	
	private static final int STATUS_REFRESH_RATE = 333; // ms between status line updates
	private static long nextStatusRefreshTs = 0;
	private static Consumer<String> statusConsumer = null;
	private static long lastUptimeMs = 0;
	private static long lastInstrCount = 0;
	private static long lastTotalMsg = 0;
	
	public static void setStatusConsumer(Consumer<String> consumer) {
		statusConsumer = consumer;
	}
	
	private static void checkProcessSwitch() {
		// throttle using the critical section when taking over async semaphore signals, do slow regular actions (screen refresh, status refresh)
		throttleCounter--;
		if (throttleCounter <= 0) {
			throttleCounter = THROTTLE_INIT;
			long now = System.currentTimeMillis();
			
			// let asynchronous events flow in
			if (nextSyncTs < now) {
				nextSyncTs = now + CHECK_INTERVAL;
				checkAsyncs();
			}
			
			// refresh changed portions of the "screen" all ~25 ms (~ 40 refreshs per second)
			if (nextDisplayRefreshTs < now) {
				BitBlt.refreshDisplay();
				nextDisplayRefreshTs = now + DISPLAY_REFRESH_RATE;
			}
			
			// refresh status shown on the UI
			if (nextStatusRefreshTs < now) {
				long uptimeMs = System.currentTimeMillis() - startTs;
				long intervalMs = Math.max(1, uptimeMs - lastUptimeMs); // prevent divide by zero at startup on fast machines
				long instrPerSecond = (instrCount - lastInstrCount) * 1000 / intervalMs;
				long totalMsgs = messageAsSmalltalkCount + messageAsPrimitiveCount + messageAsSpecialPrimitiveCount;
				long msgPerSecond = (totalMsgs - lastTotalMsg) * 1000 / intervalMs;
				if (statusConsumer != null) {
					String status = String.format(
							" up: %5ds | insns: %5dk/s | msg: %5dk/s | free: ot=%5d / heap=%3dkw [ gc: %4d ]"
							, uptimeMs / 1000
							, instrPerSecond / 1000
							, msgPerSecond / 1000
							, Memory.getFreeObjects()
							, Memory.getFreeHeapWords() / 1024
							, Memory.getGCCount()
							);
					statusConsumer.accept(status);
				}
				nextStatusRefreshTs = now + STATUS_REFRESH_RATE;
				lastUptimeMs = uptimeMs;
				lastInstrCount = instrCount;
				lastTotalMsg = totalMsgs;
				maxInstrPerSsecond = Math.max(instrPerSecond, maxInstrPerSsecond);
				maxMsgPerSecond = Math.max(msgPerSecond, maxMsgPerSecond);
			}
		}
		
		// do the process switch if a new process is waiting
		if (newProcessWaiting) {
			newProcessWaiting = false;
			int activeProcess = activeProcess();
			Memory.storePointer(Well.known().ProcessSuspendedContext, activeProcess, activeContext);
			Memory.storePointer(Well.known().ActiveProcessIndex, schedulerPointer(), newProcess);
			newActiveContext(Memory.fetchPointer(Well.known().ProcessSuspendedContext, newProcess));
		}
		
		// re-activate freeing objects possibly suspended during process list manipulations
		Memory.resumeFreeingObjects();
	}
	
	private static int activeProcess() {
		if (newProcessWaiting) {
			return newProcess;
		}
		return Memory.fetchPointer(Well.known().ActiveProcessIndex, schedulerPointer());
	}
	
	private static int schedulerPointer() {
		return Memory.fetchPointer(Well.known().ValueIndex, Well.known().SchedulerAssociationPointer);
	}
	
	public static int firstContext() {
		newProcessWaiting = false;
		return Memory.fetchPointer(Well.known().ProcessSuspendedContext, activeProcess());
	}
	
	public static int activeContext() {
		return activeContext;
	}
	
	public static void prepareSnapshot() {
		// be sure to get the currently running process
		// ... but without disturbing process scheduling (because the machine will continue to run!)
		int activeProcess = Memory.fetchPointer(Well.known().ActiveProcessIndex, schedulerPointer());
		Memory.storePointer(Well.known().ProcessSuspendedContext, activeProcess, activeContext);
		storeContextRegisters();
	}
	
	// it must be ensured that freeing objects is suspended before calling this routine
	private static int removeFirstLinkOfList(int aLinkedList) {
		int firstLink = Memory.fetchPointer(Well.known().FirstLinkIndex, aLinkedList);
		int lastLink = Memory.fetchPointer(Well.known().LastLinkIndex, aLinkedList);
		if (firstLink == lastLink) {
			Memory.storePointer(Well.known().FirstLinkIndex, aLinkedList, Well.known().NilPointer);
			Memory.storePointer(Well.known().LastLinkIndex, aLinkedList, Well.known().NilPointer);
		} else {
			int nextLink = Memory.fetchPointer(Well.known().NextLinkIndex, firstLink);
			Memory.storePointer(Well.known().FirstLinkIndex, aLinkedList, nextLink);
		}
		Memory.storePointer(Well.known().NextLinkIndex, firstLink, Well.known().NilPointer);
		return firstLink;
	}
	
	// it must be ensured that freeing objects is suspended before calling this routine
	private static void addLastLink(int aLink, int aLinkedList) {
		if (isEmptyList(aLinkedList)) {
			Memory.storePointer(Well.known().FirstLinkIndex, aLinkedList, aLink);
		} else {
			int lastLink = Memory.fetchPointer(Well.known().LastLinkIndex, aLinkedList);
			Memory.storePointer(Well.known().NextLinkIndex, lastLink, aLink);
		}
		Memory.storePointer(Well.known().LastLinkIndex, aLinkedList, aLink);
		Memory.storePointer(Well.known().ProcessMyList, aLink, aLinkedList);
	}
	
	private static boolean isEmptyList(int aLinkedList) {
		return (Memory.fetchPointer(Well.known().FirstLinkIndex, aLinkedList) == Well.known().NilPointer);
	}
	
	// it must be ensured that freeing objects is suspended before calling this routine
	private static int wakeHighestPriority() {
		int processLists = Memory.fetchPointer(Well.known().QuiescentProcessListsIndex, schedulerPointer());
		int priority = Memory.fetchWordLengthOf(processLists);
		int processList = -1;
		while(priority > 0) {
			processList = Memory.fetchPointer(priority - 1, processLists);
			if (!isEmptyList(processList)) {
				break;
			}
			priority -= 1;
		}
		return removeFirstLinkOfList(processList);
	}
	
	// it must be ensured that freeing objects is suspended before calling this routine
	private static void sleep(int aProcess) {
		int priority = fetchInteger(Well.known().ProcessPriority, aProcess);
		int processLists = Memory.fetchPointer(Well.known().QuiescentProcessListsIndex, schedulerPointer());
		int processList = Memory.fetchPointer(priority - 1, processLists);
		addLastLink(aProcess, processList);
	}
	
	// it must be ensured that freeing objects is suspended before calling this routine
	private static void suspendActive() {
		transferTo(wakeHighestPriority());
	}
	
	// it must be ensured that freeing objects is suspended before calling this routine
	private static void resume(int aProcess) {
		int activeProcess = activeProcess();
		int activePriority = fetchInteger(Well.known().ProcessPriority, activeProcess);
		int newPriority = fetchInteger(Well.known().ProcessPriority, aProcess);
		if (newPriority > activePriority) {
			sleep(activeProcess);
			transferTo(aProcess);
		} else {
			sleep(aProcess);
		}
	}
	
	private static final Primitive primitiveSignal = () -> { w("primitiveSignal");
		synchronousSignal(stackTop());
		return true;
	};
	
	private static final Primitive primitiveWait = () -> { w("primitiveWait");
		Memory.suspendFreeingObjects();
		
		int thisReceiver = stackTop();
		int excessSignals = fetchInteger(Well.known().ExcessSignalsIndex, thisReceiver);
		if (excessSignals > 0) {
			storeInteger(Well.known().ExcessSignalsIndex, thisReceiver, excessSignals - 1);
		} else {
			addLastLink(activeProcess(), thisReceiver);
			suspendActive();
		}
		
		return true;
	};
	
	private static final Primitive primitiveResume = () -> { w("primitiveResume");
		Memory.suspendFreeingObjects();
		
		resume(stackTop());
		
		return true;
	};
	
	private static final Primitive primitiveSuspend = () -> { w("primitiveSuspend");
		Memory.suspendFreeingObjects();
		
		if (success(stackTop() == activeProcess())) {
			popStack();
			push(Well.known().NilPointer);
			suspendActive();
		}
		
		return success();
	};
	
	private static final Primitive primitiveFlushCache = () -> { w("primitiveFlushCache");
		for (int i = 0; i < methodCache.length; i++) {
			methodCache[i] = 0;
		}
		methodCacheResets++;
		return true;
	};
	
	
	/*
	 * Bluebook pp. 652: System Primitives
	 */
	
	private static final Primitive primitiveEquivalent = () -> { w("primitiveEquivalent");
		int otherObject = popStack();
		int thisObject = popStack();
		push((thisObject == otherObject) ? Well.known().TruePointer : Well.known().FalsePointer);
		return true;
	};
	
	private static final Primitive primitiveClass = () -> { w("primitiveClass");
		int instance = popStack();
		push(Memory.fetchClassOf(instance));
		return true;
	};
	
	private static final Primitive primitiveCoreLeft = () -> { w("primitiveCoreLeft");
		popStack(); // remove receiver
		
		int freeWords = Memory.getFreeHeapWords();
		if (freeWords <= 0xFFFF) {
			push(positive16BitIntegerFor(freeWords));
		} else {
			// there is max. 1 MWords, so 3 bytes is sufficient
			int largeInteger = Memory.instantiateClassWithBytes(Well.known().ClassLargePositivelntegerPointer, 3);
			Memory.storeByte(0, largeInteger, lowByteOf(freeWords));
			Memory.storeByte(1, largeInteger, highByteOf(freeWords));
			Memory.storeByte(2, largeInteger, (freeWords >> 16) & 0x00FF);
			push(largeInteger);
		}
		
		return true;
	};
	
	private static final Primitive primitiveQuit = () -> { w("primitiveQuit");
		popStack(); // remove receiver
		throw new QuitSignal("primitiveQuit invoked");
	};
	
	private static final Primitive primitiveExitToDebugger = () -> { w("primitiveExitToDebugger");
		popStack(); // remove receiver
		throw new QuitSignal("primitiveExitToDebugger invoked");
	};
	
	private static final Primitive primitiveOopsLeft = () -> { w("primitiveOopsLeft");
		popStack(); // remove receiver
		push(positive16BitIntegerFor(Memory.getFreeObjects()));
		return true;
	};
	
	private static final Primitive primitiveSignalAtOopsLeftWordsLeft = () -> { w("primitiveSignalAtOopsLeftWordsLeft");
		int numWords = Interpreter.positive32BitValueOf(Interpreter.popStack());; // popInteger();
		int numOops = Interpreter.positive16BitValueOf(Interpreter.popStack());; // popInteger();
		int semaphorePointer = popStack();
		// leave receiver on the stack as return value
		
		if (Memory.ot(semaphorePointer).getClassOOP() == Well.known().ClassSemaphorePointer) {
			Memory.setGarbageCollectNotificationSink( count -> {
				if (Memory.getFreeObjects() <= numOops || Memory.getFreeHeapWords() <= numWords) {
					asynchronousSignal(semaphorePointer);
				}
			});
		} else {
			Memory.setGarbageCollectNotificationSink(null);
		}
		
		return true;
		
	};
	
	/*
	 * installation of primitives
	 */
	
	private static final void installPrimitives() {
		
		// small integer primitives (1 .. 18)
		allPrimitives[1] = SmallInteger.primitiveAdd;
		allPrimitives[2] = SmallInteger.primitiveSubtract;
		allPrimitives[3] = SmallInteger.primitiveLessThan;
		allPrimitives[4] = SmallInteger.primitiveGreaterThan;
		allPrimitives[5] = SmallInteger.primitiveLessOrEqual;
		allPrimitives[6] = SmallInteger.primitiveGreaterOrEqual;
		allPrimitives[7] = SmallInteger.primitiveEqual;
		allPrimitives[8] = SmallInteger.primitiveNotEqual;
		allPrimitives[9] = SmallInteger.primitiveMultiply;
		allPrimitives[10] = SmallInteger.primitiveDivide;
		allPrimitives[11] = SmallInteger.primitiveMod;
		allPrimitives[12] = SmallInteger.primitiveDiv;
		allPrimitives[13] = SmallInteger.primitiveQuo;
		allPrimitives[14] = SmallInteger.primitiveBitAnd;
		allPrimitives[15] = SmallInteger.primitiveBitOr;
		allPrimitives[16] = SmallInteger.primitiveBitXor;
		allPrimitives[17] = SmallInteger.primitiveBitShift;
		allPrimitives[18] = SmallInteger.primitiveMakePoint;
		
		// large integer primitives (21 .. 37)
		// (all optional)
		
		// float primitives (40 .. 54)
		allPrimitives[40] = FloatIeee.primitiveAsFloat;
		allPrimitives[41] = FloatIeee.primitiveFloatAdd;
		allPrimitives[42] = FloatIeee.primitiveFloatSubtract;
		allPrimitives[43] = FloatIeee.primitiveFloatLessThan;
		allPrimitives[44] = FloatIeee.primitiveFloatGreaterThan;
		allPrimitives[45] = FloatIeee.primitiveFloatLessOrEqual;
		allPrimitives[46] = FloatIeee.primitiveFloatGreaterOrEqual;
		allPrimitives[47] = FloatIeee.primitiveFloatEqual;
		allPrimitives[48] = FloatIeee.primitiveFloatNotEqual;
		allPrimitives[49] = FloatIeee.primitiveFloatMultiply;
		allPrimitives[50] = FloatIeee.primitiveFloatDivide;
		allPrimitives[51] = FloatIeee.primitiveFloatTruncated;
		allPrimitives[52] = FloatIeee.primitiveFloatFractionalPart;
		// allPrimitives[53] = FloatIeee.primitiveFloatExponent;  // optional ... not implemented
		allPrimitives[54] = FloatIeee.primitiveFloatTimesTwoPower;
		allPrimitives[55] = FloatIeee.primitiveFloatSinus; // DV6
		allPrimitives[56] = FloatIeee.primitiveFloatCosinus; // DV6
		
		// array and stream primitives (60 .. 67)
		allPrimitives[60] = primitiveAt;
		allPrimitives[61] = primitiveAtPut;
		allPrimitives[62] = primitiveSize;
		allPrimitives[63] = primitiveStringAt;
		allPrimitives[64] = primitiveStringAtPut;
//		allPrimitives[65] = primitiveNext;    // optional
//		allPrimitives[66] = primitiveNextPut; // optional
//		allPrimitives[67] = primitiveAtEnd;   // optional
		
		// storage management primitives (68 .. 79)
		allPrimitives[68] = Storage.primitiveObjectAt;
		allPrimitives[69] = Storage.primitiveObjectAtPut;
		allPrimitives[70] = Storage.primitiveNew;
		allPrimitives[71] = Storage.primitiveNewWithArg;
		allPrimitives[72] = Storage.primitiveBecome;
		allPrimitives[73] = Storage.primitiveInstVarAt;
		allPrimitives[74] = Storage.primitiveInstVarAtPut;
		allPrimitives[75] = Storage.primitiveAsOop;
		allPrimitives[76] = Storage.primitiveAsObject;
		allPrimitives[77] = Storage.primitiveSomeInstance;
		allPrimitives[78] = Storage.primitiveNextInstance;
		allPrimitives[79] = Storage.primitiveNewMethod;
		
		
		// control primitives (80 .. 89)
		allPrimitives[80] = primitiveBlockCopy;
		allPrimitives[81] = primitiveValue;
		allPrimitives[82] = primitiveValueWithArgs;
		allPrimitives[83] = primitivePerform;
		allPrimitives[84] = primitivePerformWithArgs;
		allPrimitives[85] = primitiveSignal;
		allPrimitives[86] = primitiveWait;
		allPrimitives[87] = primitiveResume;
		allPrimitives[88] = primitiveSuspend;
		allPrimitives[89] = primitiveFlushCache;
		
		// input/output primitives (90 .. 105)
		allPrimitives[90] = InputOutput.primitiveMousePoint;
		allPrimitives[91] = InputOutput.primitiveCursorLocPut;
		allPrimitives[92] = InputOutput.primitiveCursorLink;
		allPrimitives[93] = InputOutput.primitiveInputSemaphore;
		allPrimitives[94] = InputOutput.primitiveSampleInterval;
		allPrimitives[95] = InputOutput.primitiveInputWord;
		allPrimitives[96] = BitBlt.primitiveCopyBits;
		allPrimitives[97] = InputOutput.primitiveSnapshot;
		allPrimitives[98] = InputOutput.primitiveTimeWordsInto;
		allPrimitives[99] = InputOutput.primitiveTickWordsInto;
		allPrimitives[100] = InputOutput.primitiveSignalAtTick;
		allPrimitives[101] = InputOutput.primitiveBeCursor;
		allPrimitives[102] = InputOutput.primitiveBeDisplay;
		allPrimitives[103] = BitBlt.primitiveScanCharacters; // optional
		allPrimitives[104] = BitBlt.primitiveDrawLoopX; // optional
		allPrimitives[105] = InputOutput.primitiveStringReplace; // optional
		
		// system primitives (110 .. 116)
		allPrimitives[110] = primitiveEquivalent;
		allPrimitives[111] = primitiveClass;
		allPrimitives[112] = primitiveCoreLeft;
		allPrimitives[113] = primitiveQuit;
		allPrimitives[114] = primitiveExitToDebugger;
		allPrimitives[115] = primitiveOopsLeft;
		allPrimitives[116] = primitiveSignalAtOopsLeftWordsLeft;
		
		
		// vendor specific primitives (128..255)
		// V2 ~ Bluebook on Alto
		allPrimitives[128] = AltoDisk.primitiveDskPrim;
		allPrimitives[135] = AltoDisk.primitiveBeSnapshotSerialNumber;
		
		// DV6 ~ Stretch on 1108/1186
		allPrimitives[138] = Dv6Specifics.primitiveGarbageCollect;
		allPrimitives[143] = Dv6Specifics.primitiveCountBits;
		allPrimitives[176] = Dv6Specifics.primitiveSpecialReplaceInBytes;
		allPrimitives[177] = Dv6Specifics.primitiveSpecialReplaceInWords;
		allPrimitives[178] = Dv6Specifics.primitiveSendPrintFile;
		allPrimitives[179] = Dv6Specifics.primitivePrintResult;
		allPrimitives[180] = Dv6Specifics.primitiveXdeSendCommandLine;
		allPrimitives[181] = Dv6Specifics.primitiveXdeStatus;
		allPrimitives[197] = Dv6Specifics.primitiveFirstOwner;
		allPrimitives[198] = Dv6Specifics.primitiveNextOwner;
		allPrimitives[199] = BitBlt.primitiveDrawCircle;
		allPrimitives[200] = Dv6Specifics.primitiveSerialPortMaxPortNumber;
		allPrimitives[201] = Dv6Specifics.primitiveSerialPortCreate;
		allPrimitives[202] = Dv6Specifics.primitiveSerialPortDelete;
		allPrimitives[203] = Dv6Specifics.primitiveSerialPortReset;
		allPrimitives[204] = Dv6Specifics.primitiveSerialPortReadStatus;
		allPrimitives[205] = Dv6Specifics.primitiveSerialPortWriteStatus;
		allPrimitives[206] = Dv6Specifics.primitiveSerialPortReceive;
		allPrimitives[207] = Dv6Specifics.primitiveSerialPortSend;
		allPrimitives[222] = Dv6Specifics.primitiveLocalTimeParametersInto;
		allPrimitives[240] = TajoDisk.primitivePilotCall;
		allPrimitives[253] = Dv6Specifics.primitiveDefer;
		
		
		// initialize unimplemented primitives
		for (int i = 0; i < allPrimitives.length; i++) {
			if (allPrimitives[i] == null) {
				int primNo = i;
				allPrimitives[i] = () -> {
					if (Config.LOG_PRIMITIVES) {
						System.out.printf("\n** unimplemented primitive #" + primNo);
					}
					return primitiveFail();
				};
			}
		}
		
	}
	
}
