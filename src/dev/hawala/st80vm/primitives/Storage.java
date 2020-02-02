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

import static dev.hawala.st80vm.interpreter.Interpreter.popInteger;
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
 * Implementation of the storage management primitives (see Bluebook pp. 633..637). 
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Storage {
	
	private Storage() { }
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> Storage." + name); } }
	
	private static void logf(String pattern, Object... args) {
		if (Config.LOG_PRIMITIVES) { System.out.printf(pattern, args); }
	}
	
	public static final Primitive primitiveObjectAt = () ->  { w("primitiveObjectAt");
		int index = popInteger();
		int thisReceiver = popStack();
		if (success(index > 0 && index <= Interpreter.objectPointerCountOf(thisReceiver))) {
			push(Memory.fetchPointer(index - 1, thisReceiver));
		} else {
			unPop(2);
		}
		return success();
	};
	
	public static final Primitive primitiveObjectAtPut = () -> { w("primitiveObjectAtPut");
		int newValue = popStack();
		int index = popInteger();
		int thisReceiver = popStack();
		if (success(index > 0 && index <= Interpreter.objectPointerCountOf(thisReceiver))) {
			Memory.storePointer(index - 1, thisReceiver, newValue);
			push(newValue);
		} else {
			unPop(3);
		}
		return success();
	};
	
	public static final Primitive primitiveNew = () -> { w("primitiveNew");
		int clazz = popStack();
		logf("\n   => class = %s", Memory.getClassName(clazz));
		int size = Interpreter.fixedFieldsOf(clazz);
		if (success(!Interpreter.isIndexable(clazz))) {
			int instance = Interpreter.isPointers(clazz)
					? Memory.instantiateClassWithPointers(clazz, size)
					: Memory.instantiateClassWithWords(clazz, size);
			push(instance);
		} else {
			unPop(1);
		}
		return success();
	};
	
	public static final Primitive primitiveNewWithArg = () -> { w("primitiveNewWithArg");
		int size = Interpreter.positive16BitValueOf(popStack());
		int clazz = popStack();
		logf("\n   => class = %s , arg = %d", Memory.getClassName(clazz), size);
		if (success(Interpreter.isIndexable(clazz))) {
			size += Interpreter.fixedFieldsOf(clazz);
			int instance;
			if (Interpreter.isPointers(clazz)) {
				instance = Memory.instantiateClassWithPointers(clazz, size);
			} else if (Interpreter.isWords(clazz)) {
				instance = Memory.instantiateClassWithWords(clazz, size);
			} else {
				instance = Memory.instantiateClassWithBytes(clazz, size);
			}
			push(instance);
		} else {
			unPop(2);
		}
		return success();
	};
	
	public static final Primitive primitiveBecome = () -> { w("primitiveBecome");
		int otherPointer = popStack();
		int thisReceiver = popStack();
		if (success(!Memory.isIntegerObject(otherPointer) && !Memory.isIntegerObject(thisReceiver))) {
			Memory.swapPointersOf(thisReceiver, otherPointer);
			push(thisReceiver);
		} else {
			unPop(2);
		}
		return success();
	};
	
	private static boolean checkInstanceVariableBoundsOf(int index, int object) {
		return success(index >= 1 && index <= Interpreter.lengthOf(object));
	}
	
	public static final Primitive primitiveInstVarAt = () -> { w("primitiveInstVarAt");
		int index = popInteger();
		int thisReceiver = popStack();
		int value = 0;
		if (checkInstanceVariableBoundsOf(index, thisReceiver)) {
			value = Interpreter.subscript(thisReceiver, index);
		}
		if (success()) {
			push(value);
		} else {
			unPop(2);
		}
		return success();
	};
	
	public static final Primitive primitiveInstVarAtPut = () -> { w("primitiveInstVarAtPut");
		int newValue = popStack();
		int index = popInteger();
		int thisReceiver = popStack();
		if (checkInstanceVariableBoundsOf(index, thisReceiver)) {
			Interpreter.subscriptStoring(thisReceiver, index, newValue);
		}
		if (success()) {
			push(newValue);
		} else {
			unPop(3);
		}
		return success();
	};
	
	public static final Primitive primitiveAsOop = () -> { w("primitiveAsOop");
		int thisReceiver = popStack();
		if (success(!Memory.isIntegerObject(thisReceiver))) {
			int newOop = Memory.objectPointerAsOop(thisReceiver);
			push(newOop);
		} else {
			unPop(1);
		}
		return success();
	};
	
	public static final Primitive primitiveAsObject = () -> { w("primitiveAsObject");
		int thisReceiver = popStack();
		int newPointer = Memory.oopAsObjectPointer(thisReceiver);
		if (success(Memory.hasObject(newPointer))) {
			push(newPointer);
		} else {
			unPop(1);
		}
		return success();
	};
	
	public static final Primitive primitiveSomeInstance = () -> { w("primitiveSomeInstance");
		int classPointer = popStack();
		int objectPointer = Memory.initialInstanceOf(classPointer);
		if (objectPointer != Well.known().NilPointer) {
			push(objectPointer);
			return true;
		}
		return Interpreter.primitiveFail();
	};
	
	public static final Primitive primitiveNextInstance = () -> { w("primitiveNextInstance");
		int lastObjectPointer = popStack();
		int objectPointer = Memory.instanceAfter(lastObjectPointer);
		if (objectPointer != Well.known().NilPointer) {
			push(objectPointer);
			return true;
		}
		return Interpreter.primitiveFail();
	};
	
	public static final Primitive primitiveNewMethod = () -> { w("primitiveNewMethod");
		// get and check parameters
		int header = popStack();
		int bytecodeCount = popInteger();
		if (!success(Memory.isIntegerObject(header) && bytecodeCount >= 0)) { // pre-conditions according to method-comment for newMethod:header:
			unPop(2);
			return false;
		}
		int clazz = popStack(); // this should be: CompiledMethod
		
		// allocate the compiled method
		int literalCount = Interpreter.literalCountOfHeader(header);
		int size = ((literalCount + 1) * 2) + bytecodeCount;
		int newMethodPointer = Memory.instantiateClassWithBytes(clazz, size);
		
		// error in Bluebook p. 637, missing: initialize the compiled-method
		OTEntry newMethod = Memory.ot(newMethodPointer);
		newMethod.storeWordAt(Well.known().HeaderIndex, header); // it's a SmallInteger, so no reference-counting needed
		for (int i = 0; i < literalCount; i++) {
			newMethod.storeWordAt(Well.known().LiteralStartIndex + i, Well.known().NilPointer); // old values are 0 (zero) so this would break for reference-counting, moreover nil does not need it
		}
		
		// return the new compiled method
		push(newMethodPointer);
		return true;
	};
	
}
