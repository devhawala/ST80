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
import static dev.hawala.st80vm.interpreter.Interpreter.pushInteger;
import static dev.hawala.st80vm.interpreter.InterpreterBase.popStack;
import static dev.hawala.st80vm.interpreter.InterpreterBase.push;
import static dev.hawala.st80vm.interpreter.InterpreterBase.success;
import static dev.hawala.st80vm.interpreter.InterpreterBase.unPop;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.interpreter.Interpreter;
import dev.hawala.st80vm.interpreter.Interpreter.Primitive;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.Well;

/**
 * Implementation of the arithmetic primitives for Float (see Bluebook pp. 625..627).
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class FloatIeee { // Bluebook claims that IEEE single-precision (32-bit) is used for Float, so map it directly to Java float...
	
	private FloatIeee() { }
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> FloatIeee." + name); } }
	
	private static void logf(String pattern, Object... args) {
		if (Config.TRACE_FLOAT) { System.out.printf("\n+++++++++++++++++++++++++++++++++ Float-Op: " + pattern, args); }
	}

	private static float popFloat(boolean requireFloat) {
		int oop = popStack();
		int oopClass = Memory.fetchClassOf(oop);
		if (oopClass == Well.known().ClassFloatPointer) {
			int floatRepr = (Memory.fetchWord(0, oop) << 16) | Memory.fetchWord(1, oop);
			float value = Float.intBitsToFloat(floatRepr);
			return value;
		} else if (requireFloat) {
			Interpreter.primitiveFail();
			return Float.NaN;
		} else if (oopClass == Well.known().ClassSmallIntegerPointer) {
			// speed up things by coercing ourself
			int intValue = Memory.integerValueOf(oop);
			return (float)intValue;
		} else {
			Interpreter.primitiveFail();
			return Float.NaN;
		}
	}
	
	private static void pushNewFloat(float value) {
		int floatRepr = Float.floatToRawIntBits(value);
		int upper = (floatRepr >> 16) & 0x0000FFFF;
		int lower = floatRepr & 0x0000FFFF;
		int floatOop = Memory.instantiateClassWithWords(Well.known().ClassFloatPointer, 2);
		Memory.storeWord(0, floatOop, upper);
		Memory.storeWord(1, floatOop, lower);
		push(floatOop);
	}

	public static final Primitive primitiveAsFloat = () -> { w("primitiveAsFloat");
		int receiver = popInteger();
		if (success()) {
			float value = (float)receiver;
			logf("\n%d asFloat => %f", receiver, value);
			pushNewFloat(value);
		} else {
			unPop(1);
		}
		return success();
	};

	public static final Primitive primitiveFloatAdd = () -> { w("primitiveFloatAdd");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		float value = receiver + argument;
		logf("\n%f + %f => %f", receiver, argument, value);
		pushNewFloat(value);
		return true;
	};

	public static final Primitive primitiveFloatSubtract = () -> { w("primitiveFloatSubtract");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		float value = receiver - argument;
		logf("\n%f - %f => %f", receiver, argument, value);
		pushNewFloat(value);
		return true;
	};

	public static final Primitive primitiveFloatLessThan = () -> { w("primitiveFloatLessThan");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		if (receiver < argument) {
			push(Well.known().TruePointer);
		} else {
			push(Well.known().FalsePointer);
		}
		return true;
	};

	public static final Primitive primitiveFloatGreaterThan = () -> { w("primitiveFloatGreaterThan");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		if (receiver > argument) {
			push(Well.known().TruePointer);
		} else {
			push(Well.known().FalsePointer);
		}
		return true;
	};

	public static final Primitive primitiveFloatLessOrEqual = () -> { w("primitiveFloatLessOrEqual");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		if (receiver <= argument) {
			push(Well.known().TruePointer);
		} else {
			push(Well.known().FalsePointer);
		}
		return true;
	};

	public static final Primitive primitiveFloatGreaterOrEqual = () -> { w("primitiveFloatGreaterOrEqual");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		if (receiver >= argument) {
			push(Well.known().TruePointer);
		} else {
			push(Well.known().FalsePointer);
		}
		return true;
	};

	public static final Primitive primitiveFloatEqual = () -> { w("primitiveFloatEqual");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (receiver == argument) {
			push(Well.known().TruePointer);
		} else {
			push(Well.known().FalsePointer);
		}
		return true;
	};

	public static final Primitive primitiveFloatNotEqual = () -> { w("primitiveFloatNotEqual");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		if (receiver != argument) {
			push(Well.known().TruePointer);
		} else {
			push(Well.known().FalsePointer);
		}
		return true;
	};

	public static final Primitive primitiveFloatMultiply = () -> { w("primitiveFloatMultiply");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		float value = receiver * argument;
		logf("\n%f * %f => %f", receiver, argument, value);
		pushNewFloat(value);
		return true;
	};

	public static final Primitive primitiveFloatDivide = () -> { w("primitiveFloatDivide");
		float argument = popFloat(false);
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(2);
			return false;
		}
		if (success(argument != 0.0f)) {
			float value = receiver / argument;
			logf("\n%f / %f => %f", receiver, argument, value);
			pushNewFloat(value);
			return true;
		} else {
			unPop(2);
			return false;
		}
	};

	public static final Primitive primitiveFloatTruncated = () -> { w("primitiveFloatTruncated");
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(1);
			return false;
		}
		int truncated = (int)receiver; // this "rounds" towards 0
		if (Memory.isIntegerValue(truncated)) {
			pushInteger(truncated);
			return true;
		} else {
			unPop(1);
			return false;
		}
	};

	public static final Primitive primitiveFloatFractionalPart = () -> { w("primitiveFloatFractionalPart");
		float receiver = popFloat(true);
		if (!Interpreter.success()) {
			unPop(1);
			return false;
		}
		int truncated = (int)receiver;
		pushNewFloat(receiver - truncated);
		return true;
	};
	
	// not implemented
	// public static final Primitive primitiveFloatExponent = ...
	
	public static final Primitive primitiveFloatTimesTwoPower = () -> { w("primitiveFloatTimesTwoPower");
		int power = popInteger();
		if (success()) {
			float receiver = popFloat(true);
			if (!Interpreter.success()) {
				unPop(2);
				return false;
			}
			float result = receiver * (float)Math.pow(2.0, power);
			pushNewFloat(result);
			return true;
		} else {
			unPop(1);
			return false;
		}
	};
	
}
