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
import dev.hawala.st80vm.interpreter.Interpreter.Primitive;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.Well;

/**
 * Implementation of the arithmetic primitives for SmallInteger (see Bluebook pp. 621..625).
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class SmallInteger {
	
	private SmallInteger() { }
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> SmallInteger." + name); } }

	public static final Primitive primitiveAdd = () -> { w("primitiveAdd");
		int argument = popInteger();
		int receiver = popInteger();
		int result = success() ? receiver + argument : 0;
		if (success(Memory.isIntegerValue(result))) {
			pushInteger(result);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveSubtract = () -> { w("primitiveSubtract");
		int argument = popInteger();
		int receiver = popInteger();
		int result = success() ? receiver - argument : 0;
		if (success(Memory.isIntegerValue(result))) {
			pushInteger(result);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveLessThan = () -> { w("primitiveLessThan");
		int argument = popInteger();
		int receiver = popInteger();
		if (success()) {
			if (receiver < argument) {
				push(Well.known().TruePointer);
			} else {
				push(Well.known().FalsePointer);
			}
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveGreaterThan = () -> { w("primitiveGreaterThan");
		int argument = popInteger();
		int receiver = popInteger();
		if (success()) {
			if (receiver > argument) {
				push(Well.known().TruePointer);
			} else {
				push(Well.known().FalsePointer);
			}
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveLessOrEqual = () -> { w("primitiveLessOrEqual");
		int argument = popInteger();
		int receiver = popInteger();
		if (success()) {
			if (receiver <= argument) {
				push(Well.known().TruePointer);
			} else {
				push(Well.known().FalsePointer);
			}
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveGreaterOrEqual = () -> { w("primitiveGreaterOrEqual");
		int argument = popInteger();
		int receiver = popInteger();
		if (success()) {
			if (receiver >= argument) {
				push(Well.known().TruePointer);
			} else {
				push(Well.known().FalsePointer);
			}
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveEqual = () -> { w("primitiveEqual");
		int argument = popInteger();
		int receiver = popInteger();
		if (success()) {
			if (receiver == argument) {
				push(Well.known().TruePointer);
			} else {
				push(Well.known().FalsePointer);
			}
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveNotEqual = () -> { w("primitiveNotEqual");
		int argument = popInteger();
		int receiver = popInteger();
		if (success()) {
			if (receiver != argument) {
				push(Well.known().TruePointer);
			} else {
				push(Well.known().FalsePointer);
			}
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveMultiply = () -> { w("primitiveMultiply");
		int argument = popInteger();
		int receiver = popInteger();
		int result = success() ? receiver * argument : 0;
		if (success(Memory.isIntegerValue(result))) {
			pushInteger(result);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveDivide = () -> { w("primitiveDivide");
		int argument = popInteger();
		int receiver = popInteger();
		success(argument != 0 && (receiver % argument) == 0);
		int result = success() ? receiver / argument : 0;
		if (success(Memory.isIntegerValue(result))) {
			pushInteger(result);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveMod = () -> { w("primitiveMod"); // attention: result must go towards negative infinity, not towards 0
		int argument = popInteger();
		int receiver = popInteger();
		if (success(argument != 0)) {
			int result = receiver % argument;
			if (result == 0) {
				pushInteger(0);
			} else {
				int adjust = ( (receiver < 0 && argument > 0) || (receiver > 0 && argument < 0) ) ? argument : 0;
				pushInteger(result + adjust);
			}
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveDiv = () -> { w("primitiveDiv"); // attention: result must go towards negative infinity, not towards 0
		int argument = popInteger();
		int receiver = popInteger();
		if (success(argument != 0)) {
			int result = receiver / argument;
			if ((receiver % argument) == 0) {
				pushInteger(result);
			} else {
				int adjust = ( (receiver < 0 && argument > 0) || (receiver > 0 && argument < 0) ) ? 1 : 0;
				pushInteger(result - adjust);
			}
		} else {
			unPop(2);
		}
		return success();
	};
	
	public static final Primitive primitiveQuo = () -> { w("primitiveQuo"); // attention: result must go towards 0, not towards negative infinity
		int argument = popInteger();
		int receiver = popInteger();
		if (success(argument != 0)) {
			pushInteger(receiver / argument);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveBitAnd = () -> { w("primitiveBitAnd");
		int argument = popInteger();
		int receiver = popInteger();
		int result = success() ? receiver & argument : 0;
		if (success(Memory.isIntegerValue(result))) {
			pushInteger(result);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveBitOr = () -> { w("primitiveBitOr");
		int argument = popInteger();
		int receiver = popInteger();
		int result = success() ? receiver | argument : 0;
		if (success(Memory.isIntegerValue(result))) {
			pushInteger(result);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveBitXor = () -> { w("primitiveBitXor");
		int argument = popInteger();
		int receiver = popInteger();
		int result = success() ? receiver ^ argument : 0;
		if (success(Memory.isIntegerValue(result))) {
			pushInteger(result);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveBitShift = () -> { w("primitiveBitShift");
		int argument = popInteger();
		int receiver = popInteger();
		int result = success() ? (argument >= 0) ? receiver << argument : receiver >> (-argument) : 0;
		if (success(Memory.isIntegerValue(result))) {
			pushInteger(result);
		} else {
			unPop(2);
		}
		return success();
	};

	public static final Primitive primitiveMakePoint = () -> { w("primitiveMakePoint");
		int argument = popStack();
		int receiver = popStack();
		if (success(Memory.isIntegerValue(receiver) && success(Memory.isIntegerValue(argument)))) {
			int pointResult = Memory.instantiateClassWithPointers(Well.known().ClassPointPointer, Well.known().ClassPointSize);
			Memory.storePointer(Well.known().XIndex, pointResult, receiver);
			Memory.storePointer(Well.known().YIndex, pointResult, argument);
			push(pointResult);
		} else {
			unPop(2);
		}
		return success();
	};
	
	/*
	 * tests
	 */
	
	private static int st80_mod(int recvVal, int argVal) {
		int result = recvVal % argVal;
		if (result == 0) { return 0; }
		int adjust = ( (recvVal < 0 && argVal > 0) || (recvVal > 0 && argVal < 0) ) ? argVal : 0;
		return result + adjust;
	}
	
	private static int st80_div(int recvVal, int argVal) {
		int result = recvVal / argVal;
		if ((recvVal % argVal) == 0) { return result; }
		int adjust = ( (recvVal < 0 && argVal > 0) || (recvVal > 0 && argVal < 0) ) ? 1 : 0;
		return result - adjust;
	}
	
	private static int st80_quo(int recvVal, int argVal) {
		return recvVal / argVal;
	}

	public static void mainTEST(String[] args) {
		int reprPlusZero = Float.floatToRawIntBits(+0.0f);
		int reprMinususZero = Float.floatToRawIntBits(-0.0f);
		int reprOne = Float.floatToRawIntBits(1.0f);
		int reprTwo = Float.floatToRawIntBits(2.0f);
		int reprMinusHalf = Float.floatToRawIntBits(-0.5f);
		
		System.out.printf("reprPlusZero    = 0x%08X\n", reprPlusZero);
		System.out.printf("reprMinususZero = 0x%08X\n", reprMinususZero);
		System.out.printf("reprOne         = 0x%08X\n", reprOne);
		System.out.printf("reprTwo         = 0x%08X\n", reprTwo);
		System.out.printf("reprMinusHalf   = 0x%08X\n", reprMinusHalf);
		System.out.println();
		System.out.printf("truncate +2.33 : %d\n", (int)2.33f);
		System.out.printf("truncate -2.33 : %d\n", (int)-2.33f); // geht also nach 0, nicht nach minus-infinity
		System.out.println();
		System.out.printf("exponent of 3.4567 : %d\n", Math.getExponent(3.4567));
		System.out.printf("exponent of 34.567 : %d\n", Math.getExponent(3.4567));
		System.out.println();
		System.out.printf("java:  5 %% 3  -> %d\n", 5 % 3);
		System.out.printf("java: -5 %% 3  -> %d\n", -5 % 3);
		System.out.printf("java:  5 %% -3 -> %d\n", 5 % -3);
		System.out.printf("java: -5 %% -3 -> %d\n", -5 % -3);
		System.out.println();
		System.out.printf("java:  4 %% 3  -> %d\n", 4 % 3);
		System.out.printf("java: -4 %% 3  -> %d\n", -4 % 3);
		System.out.printf("java:  4 %% -3 -> %d\n", 4 % -3);
		System.out.printf("java: -4 %% -3 -> %d\n", -4 % -3);
		System.out.println();
		System.out.printf("java:  6 %% 3  -> %d\n", 6 % 3);
		System.out.printf("java: -6 %% 3  -> %d\n", -6 % 3);
		System.out.printf("java:  6 %% -3 -> %d\n", 6 % -3);
		System.out.printf("java: -6 %% -3 -> %d\n", -6 % -3);
		System.out.println();
		System.out.printf("java:  5 / 3  -> %d\n", 5 / 3);
		System.out.printf("java: -5 / 3  -> %d\n", -5 / 3);
		System.out.printf("java:  5 / -3 -> %d\n", 5 / -3);
		System.out.printf("java: -5 / -3 -> %d\n", -5 / -3);
//		System.out.println();
//		System.out.printf("  2.0f ^ 3  -> %f\n", (float)Math.pow(2.0f,3));
		
		System.out.println();
		System.out.println();
		System.out.printf("st80:  5 quo: 3  -> %d\n", st80_quo( 5 ,  3));
		System.out.printf("st80: -5 quo: 3  -> %d\n", st80_quo(-5 ,  3));
		System.out.printf("st80:  5 quo: -3 -> %d\n", st80_quo( 5 , -3));
		System.out.printf("st80: -5 quo: -3 -> %d\n", st80_quo(-5 , -3));

		System.out.println();
		System.out.printf("st80:  5 div: 3  -> %d\n", st80_div( 5 ,  3));
		System.out.printf("st80: -5 div: 3  -> %d\n", st80_div(-5 ,  3));
		System.out.printf("st80:  5 div: -3 -> %d\n", st80_div( 5 , -3));
		System.out.printf("st80: -5 div: -3 -> %d\n", st80_div(-5 , -3));

		System.out.println();
		System.out.printf("st80:  6 div: 3  -> %d\n", st80_div( 6 ,  3));
		System.out.printf("st80: -6 div: 3  -> %d\n", st80_div(-6 ,  3));
		System.out.printf("st80:  6 div: -3 -> %d\n", st80_div( 6 , -3));
		System.out.printf("st80: -6 div: -3 -> %d\n", st80_div(-6 , -3));

		System.out.println();
		System.out.printf("st80:  5 mod: 3  -> %d\n", st80_mod( 5 ,  3));
		System.out.printf("st80: -5 mod: 3  -> %d\n", st80_mod(-5 ,  3));
		System.out.printf("st80:  5 mod: -3 -> %d\n", st80_mod( 5 , -3));
		System.out.printf("st80: -5 mod: -3 -> %d\n", st80_mod(-5 , -3));

		System.out.println();
		System.out.printf("st80:  4 mod: 3  -> %d\n", st80_mod( 4 ,  3));
		System.out.printf("st80: -4 mod: 3  -> %d\n", st80_mod(-4 ,  3));
		System.out.printf("st80:  4 mod: -3 -> %d\n", st80_mod( 4 , -3));
		System.out.printf("st80: -4 mod: -3 -> %d\n", st80_mod(-4 , -3));

		System.out.println();
		System.out.printf("st80:  6 mod: 3  -> %d\n", st80_mod( 6 ,  3));
		System.out.printf("st80: -6 mod: 3  -> %d\n", st80_mod(-6 ,  3));
		System.out.printf("st80:  6 mod: -3 -> %d\n", st80_mod( 6 , -3));
		System.out.printf("st80: -6 mod: -3 -> %d\n", st80_mod(-6 , -3));
	}
	
}
