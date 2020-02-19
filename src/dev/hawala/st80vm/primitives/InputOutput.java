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
import static dev.hawala.st80vm.interpreter.Interpreter.positive16BitValueOf;
import static dev.hawala.st80vm.interpreter.InterpreterBase.pop;
import static dev.hawala.st80vm.interpreter.InterpreterBase.push;
import static dev.hawala.st80vm.interpreter.InterpreterBase.success;
import static dev.hawala.st80vm.interpreter.InterpreterBase.unPop;

import java.util.ArrayList;
import java.util.List;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.interpreter.Interpreter;
import dev.hawala.st80vm.interpreter.Interpreter.Primitive;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.OTEntry;
import dev.hawala.st80vm.memory.Well;
import dev.hawala.st80vm.ui.iDisplayPane;

/**
 * Implementation of input/output primitives (see Bluebook pp.647..652).
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class InputOutput {

	private InputOutput() { }
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> InputOutput." + name); } }
	
	private static void logf(String pattern, Object... args) {
		if (Config.TRACE_IO) { System.out.printf(pattern, args); }
	}
	
	/*
	 * the display
	 */
	private static iDisplayPane displayPane = null;
	
	public static void registerDisplayPane(iDisplayPane pane) {
		displayPane = pane;
		BitBlt.registerDisplayPane(pane);
	}
	
	/*
	 * current mouse position
	 */
	private static int cursorDisplacementX = 0;
	private static int cursorDisplacementY = 0;
	private static int mouseX = 0;
	private static int mouseY = 0;
	
	private static synchronized void setCurrMousePos(int x, int y) {
		mouseX = x;
		mouseY = y;
	}
	
	private static synchronized int getMouseX() { return mouseX; }
	private static synchronized int getMouseY() { return mouseY; }
	
	/*
	 * Smalltalk-time
	 */
	
	// 01.01.1901 -> 01.01.1970: 69 years having 17 leap years
	private static final long SMALLTALK_TO_UNIX_TIME_DELTA_SECONDS = ((69L * 365L) + 17L) * 86400L;
	
	private static long timeAdjustSeconds = 0;
	
	public static void setTimeAdjustmentMinutes(int minutes) {
		timeAdjustSeconds = Math.max(-12 * 60, Math.min(24 * 60, minutes)) * 60;
	}
	
	// seconds since 01.01.1901 00:00:00 (assuming UTC, as the Bluebook does not specify the time zone)
	private static long getSmalltalkTime() {
		long unixTimeSecs = System.currentTimeMillis() / 1000;
		long smalltalkTime = unixTimeSecs + SMALLTALK_TO_UNIX_TIME_DELTA_SECONDS + timeAdjustSeconds;
		return smalltalkTime;
	}
	
	/*
	 * Smalltalk-ticks
	 */
	
	private static final long BASE_TS = System.currentTimeMillis() - 1;
	
	private static long getTicks() {
		return System.currentTimeMillis() - BASE_TS; // this gives us about 49 days for an unsigned 32bit integer range
	}
	
	private static int signalAtSemaphore = -1;
	private static long signalAtTick = -1;
	
	private static final Object timerLock = new Object();
	
	private static Thread timerThread = null;
	
	private static final int NO_AT_TICK_TIMER = -1;
	private static final long DEFAULT_TIMEOUT = 2000; // milliseconds
	
	private static void atTickTimer() {
		int currSemaphore = NO_AT_TICK_TIMER;
		long waitTime = DEFAULT_TIMEOUT;
		synchronized(timerLock) {
			try {
				while(true) {
					long currTicks = getTicks();
					if (currSemaphore != NO_AT_TICK_TIMER && currSemaphore == signalAtSemaphore) {
						// we were waiting for this semaphore: check if time arrived
						if (signalAtTick <= currTicks) {
							// yes: signal semaphore and reset timer
							Interpreter.asynchronousSignal(signalAtSemaphore);
							signalAtSemaphore = NO_AT_TICK_TIMER;
							currSemaphore = NO_AT_TICK_TIMER;
							waitTime = DEFAULT_TIMEOUT;
						} else {
							// no: wait again for the difference until time arrived
							waitTime = signalAtTick - currTicks;
						}
					} else {
						// semaphore changed: re-arm timer for new semaphore/interval if signalAtSemaphore != -1, else reset
						currSemaphore = signalAtSemaphore;
						waitTime = (currSemaphore != NO_AT_TICK_TIMER) ? signalAtTick - currTicks : DEFAULT_TIMEOUT;
					}
					
					if (waitTime > 0) {
						timerLock.wait(waitTime);
					}
				}
			} catch(InterruptedException ie) {
				timerThread = null;
			}
		}
	}
	
	private static void armSignalAtTimer(int semaphore, long atTick) {
		long now = getTicks();
		synchronized(timerLock) {
			if (timerThread == null) {
				timerThread = new Thread(InputOutput::atTickTimer);
				timerThread.setDaemon(true);
				timerThread.start();
			}
			if (semaphore != NO_AT_TICK_TIMER && atTick <= now) {
				// the time for signaling has already passed, so signal it
				Interpreter.asynchronousSignal(semaphore);
				// and cancel current timer
				signalAtSemaphore = NO_AT_TICK_TIMER;
				signalAtTick = -1;
			} else {
				// tell the timer thread about the new signal:atTick:
				signalAtSemaphore = semaphore;
				signalAtTick = atTick;
			}
			timerLock.notify();
		}
	}
	
	/*
	 * event queue management
	 */
	
	private static int eventSemaphore = -1;
	private static List<Integer> eventQueue = new ArrayList<Integer>();
	private static long lastEventTs = 0;
	
	private static synchronized void setEventSemaphore(int es) {
		logf("\n** InputOutput.setEventSemaphore( semaphore = 0x%04X )", es);
		eventSemaphore = es;
		
//		// signal all events not transmitted (?)
//		for (int i = 0; i < eventQueue.size(); i++) {
//			Interpreter.asynchronousSignal(eventSemaphore);
//		}
		
		eventQueue.clear();
	}
	
	private static synchronized int dequeueWord() {
		if (eventQueue.isEmpty()) {
			Interpreter.primitiveFail();
			return 0;
		}
		int dequeuedWord = eventQueue.remove(0) & 0xFFFF;
		int dequeuedObject = Interpreter.positive16BitIntegerFor(dequeuedWord);
		logf("###### dequeueWord() => 0x%04X (%d) -> objectPointer = 0x%04X\n", dequeuedWord, dequeuedWord, dequeuedObject);
		return dequeuedObject;
	}
	
	private static synchronized void enqueueWord(int word) {
		logf("#### enqueueWord( 0x%04X )\n", word);
		eventQueue.add(word);
		if (eventSemaphore != -1) {
			Interpreter.asynchronousSignal(eventSemaphore);
		}
	}
	
	private static void enqueueTimestamp() {
		long now = System.currentTimeMillis();
		long delta = now - lastEventTs;
		if (delta < 4096) {
			// send one type code 1 : delta time
			int deltaWord = (int)delta;
			enqueueWord(deltaWord);
		} else {
			// send three type code 5 words: absolute time
			int currTime = (int)(getSmalltalkTime() & 0x00000000FFFFFFFFL);
			int timeWord0 = (currTime >> 16) & 0xFFFF;
			int timeWord1 = currTime & 0xFFFF;
			enqueueWord(0x5000);
			enqueueWord(timeWord0);
			enqueueWord(timeWord1);
		}
		lastEventTs = now;
	}
	
	/*
	 * in-flow of hardware events
	 */
	
	// define the special keys
	public static final int K_LEFT_SHIFT = 136;
	public static final int K_RIGHT_SHIFT = 137;
	public static final int K_CONTROL = 138;
	public static final int K_ALPHA_LOCK = 139;

	public static final int K_MOUSE_LEFT   = 130; // 128; left and right buttons inverted?
	public static final int K_MOUSE_MIDDLE = 129;
	public static final int K_MOUSE_RIGHT  = 128; // 130; left and right buttons inverted?

	public static final int K_KEYSET0 = 131; // right paddle
	public static final int K_KEYSET1 = 132;
	public static final int K_KEYSET2 = 133;
	public static final int K_KEYSET3 = 134;
	public static final int K_KEYSET4 = 135; // left paddle
	
	public static void mouseMoved(int newX, int newY) {
		int x = newX - cursorDisplacementX;
		int y = newY - cursorDisplacementY;
//		System.out.printf("#### mouseMoved( x: %d , y: %d )\n", x, y);
		enqueueTimestamp();
		enqueueWord(0x1000 | (x & 0x0FFF));
		enqueueWord(0x2000 | (y & 0x0FFF));
		setCurrMousePos(x, y);
	}
	
	public static void decodedKeyPressed(int ascii) {
//		System.out.printf("#### decodedKeyPressed( ascii: 0x%02X ~ %d )\n", ascii, ascii);
		enqueueTimestamp();
		enqueueWord(0x3000 | (ascii & 0x0FFF)); // key down
		enqueueWord(0x4000 | (ascii & 0x0FFF)); // key up
	}
	
	public static void undecodedKeyDown(int key) {
//		System.out.printf("#### undecodedKeyDown( key: %d )\n", key);
		enqueueTimestamp();
		enqueueWord(0x3000 | (key & 0x0FFF)); // key down
	}
	
	public static void undecodedKeyUp(int key) {
//		System.out.printf("#### undecodedKeyUp( key: %d )\n", key);
		enqueueTimestamp();
		enqueueWord(0x4000 | (key & 0x0FFF)); // key up
	}
	
	
	/*
	 * Input/Output primitives
	 */
	
	public static final Primitive primitiveInputWord = () -> { w("primitiveInputWord");
		popStack(); // remove self from stack
		push(dequeueWord());
		return Interpreter.success();
	};
	
	public static final Primitive primitiveSampleInterval = () -> { w("primitiveSampleInterval");
		popStack(); // remove the argument: ignored, since sample interval is currently not supported
		// leave self on the stack
		return true;
	};
	
	public static final Primitive primitiveMousePoint = () -> { w("primitiveMousePoint");
		popStack(); // drop receiver
		int newPoint = Memory.instantiateClassWithPointers(Well.known().ClassPointPointer, Well.known().ClassPointSize);
		Interpreter.storeInteger(Well.known().XIndex, newPoint, getMouseX());
		Interpreter.storeInteger(Well.known().YIndex, newPoint, getMouseY());
		push(newPoint);
		return true;
	};
	
	public static final Primitive primitiveBeDisplay = () -> { w("primitiveBeDisplay");
		int displayScreenPointer = popStack();
		BitBlt.registerDisplayScreen(displayScreenPointer);
		unPop(1);
		return true;
	};
	
	private static final short[] cursorWords = new short[16];
	
	public static final Primitive primitiveBeCursor = () -> { w("primitiveBeCursor");
		int cursorPointer = popStack();
		
		OTEntry cursor = Memory.ot(cursorPointer);
		OTEntry cursorBits = Memory.ot(cursor.fetchPointerAt(Well.known().FormBitsIndex));
		int width = Memory.integerValueOf(cursor.fetchPointerAt(Well.known().FormWidthIndex));
		int height = Memory.integerValueOf(cursor.fetchPointerAt(Well.known().FormHeightIndex));
		// ?? handle case where width != 16 or height != 16
		
		int hotspotPointer = cursor.fetchPointerAt(Well.known().FormOffsetIndex);
		OTEntry hotspot = (hotspotPointer != Well.known().NilPointer) ? Memory.ot(hotspotPointer) : null;
		int hotspotX = (hotspot != null) ? - Memory.integerValueOf(hotspot.fetchPointerAt(Well.known().XIndex)) : 0;
		int hotspotY = (hotspot != null) ? - Memory.integerValueOf(hotspot.fetchPointerAt(Well.known().YIndex)) : 0;
		hotspotX = Math.max(0, Math.min(15, hotspotX));
		hotspotY = Math.max(0, Math.min(15, hotspotY));
		
		// bring the above cursor data to the UI
		if (displayPane != null) {
			logf("\n    +---------------------------------+\n");
			for (int i = 0; i < 16; i++) {
				short line = (i < cursorBits.getWordLength()) ? (short)cursorBits.fetchWordAt(i) : 0;
				cursorWords[i] = line;
				logf("    | %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s |\n",
						((line & 0x8000) != 0) ? "x" : " ",
						((line & 0x4000) != 0) ? "x" : " ",
						((line & 0x2000) != 0) ? "x" : " ",
						((line & 0x1000) != 0) ? "x" : " ",
						((line & 0x0800) != 0) ? "x" : " ",
						((line & 0x0400) != 0) ? "x" : " ",
						((line & 0x0200) != 0) ? "x" : " ",
						((line & 0x0100) != 0) ? "x" : " ",
						((line & 0x0080) != 0) ? "x" : " ",
						((line & 0x0040) != 0) ? "x" : " ",
						((line & 0x0020) != 0) ? "x" : " ",
						((line & 0x0010) != 0) ? "x" : " ",
						((line & 0x0008) != 0) ? "x" : " ",
						((line & 0x0004) != 0) ? "x" : " ",
						((line & 0x0002) != 0) ? "x" : " ",
						((line & 0x0001) != 0) ? "x" : " "
						);
			}
			logf("    +---------------------------------+\n");
			displayPane.setCursor(cursorWords, hotspotX, hotspotY);
			cursorDisplacementX = hotspotX;
			cursorDisplacementY = hotspotY;
			logf("--- new cursor, hotspot: ( %4d , %4d )\n", hotspotX, hotspotY);
		}
		
		unPop(1);
		return true;
	};
	
	public static final Primitive primitiveCursorLink = () -> { w("primitiveCursorLink");
		int linked = popStack(); // TruePointer or FalsePointer
		int self = popStack();
		
		// currently ignored, as the cursor is controlled externally and so cannot be "linked" to an arbitrary Smalltalk-Point
		
		unPop(1);
		return true;
	};
	
	public static final Primitive primitiveCursorLocPut = () -> { w("primitiveCursorLocPut");
		int positionPointer = popStack(); // Point
		int self = popStack();
		
		// currently ignored, as the cursor is controlled externally and so cannot be "moved" to an arbitrary Smalltalk-Point
		
		unPop(1);
		return true;
	};
	
	public static final Primitive primitiveInputSemaphore = () -> { w("primitiveInputSemaphore");
		int semaphorePointer = popStack();
		// leave self on the stack: we don't need it and it must be returned anyway
		// check that it is a semaphore, fail if not
		setEventSemaphore(semaphorePointer);
		return true;
	};
	
	// if null, the snapshot will be written and backup'd alone,
	// if non-null, the vmFileHandler will take care of saving/archiving the snapshot and the file system in synch
	private static iVmFilesHandler vmFilesHandler = null;
	
	public static void setVmFilesHandler(iVmFilesHandler handler) {
		vmFilesHandler = handler;
	}
	
	public static final Primitive primitiveSnapshot = () -> { w("primitiveSnapshot");
		// special handling as this primitive has in fact 2 different exits:
		// - the obvious exit is return when the smalltalk machine continues to run in the same session
		// - the invisible exit is when the snapshot is later resumed in a new session, where no (real)
	    //   return happens here, but execution continues with the instruction following the snapshot-message
		// the smalltalk code discerns both cases by checking the returned value (the thing on top of the stack)
		// to compute the variable 'justSnapped', therefore:
		// we leave the receiver on the stack *before* writing the snapshot 
		//   -> so it is still on the stack when the snapshot is resumed
		//   -> justSnapped will be false => the current method will continue to run and ignore quit
		// after writing the snapshot, we put NIL on the stack
	    //  -> justSnapped will be true and the current method will check for quit
	
		// drop arguments (V2: none, DV6: 0..1 arguments)
		for (int i = 0; i < Interpreter.argumentCount(); i++) {
			popStack();
		}
		
		// write snapshot
		Interpreter.prepareSnapshot();
		if (vmFilesHandler != null) {
			vmFilesHandler.saveSnapshot(System.out);
		} else {
			Memory.saveVirtualImage(null); // write the new snapshot with same name as the loaded one
		}
		
		// set return value for the continuing session
		pop(1); // remove self
		push(Well.known().NilPointer); // tell invoker we are continuing in the same session by which the snapshot was just taken
		return true;
	};
	
	public static final Primitive primitiveTimeWordsInto = () -> { w("primitiveTimeWordsInto");
		int target = popStack();
		// leave self on the stack: we don't need it and it must be returned anyway
		
		int time = (int)getSmalltalkTime();
		Memory.storeByte(0, target, time & 0xFF);
		Memory.storeByte(1, target, (time >> 8) & 0xFF);
		Memory.storeByte(2, target, (time >> 16) & 0xFF);
		Memory.storeByte(3, target, (time >> 24) & 0xFF); 
		
		return true;
	};
	
	public static final Primitive primitiveTickWordsInto = () -> { w("primitiveTickWordsInto");
		int target = popStack();
		// leave self on the stack: we don't need it and it must be returned anyway
		
		int ticks = (int)getTicks();
		Memory.storeByte(0, target, ticks & 0xFF);
		Memory.storeByte(1, target, (ticks >> 8) & 0xFF);
		Memory.storeByte(2, target, (ticks >> 16) & 0xFF);
		Memory.storeByte(3, target, (ticks >> 24) & 0xFF); 
		
		return true;
	};
	
	public static final Primitive primitiveSignalAtTick = () -> { w("primitiveSignalAtTick");
		int atTick = popStack();
		int semaphore = popStack();
		// leave self on the stack: we don't need it and it must be returned anyway
		
		if (Memory.fetchClassOf(semaphore) == Well.known().ClassSemaphorePointer) {
			int atTickValue 
					= Memory.fetchByte(0, atTick)
					| (Memory.fetchByte(1, atTick) << 8)
					| (Memory.fetchByte(2, atTick) << 16)
					| (Memory.fetchByte(3, atTick) << 24);
			armSignalAtTimer(semaphore, atTickValue);
		} else {
			armSignalAtTimer(NO_AT_TICK_TIMER, -1);
		}
		
		return true;
	};

	// based on Smalltalk default code for ByteArray :: replaceFrom: start to: stop withString: aString startingAt: repStart 
	public static final Primitive primitiveStringReplace = () -> { w("primitiveStringReplace");
		//System.out.printf("\n+++++++++ primitive primitiveStringReplace()");
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
		
		OTEntry self = Memory.ot(selfPointer);
		int maxSelfIndex = self.getByteLength();
		OTEntry replacement = Memory.ot(replacementPointer);
		int maxReplIndex = replacement.getByteLength();
		
		// Smalltalk indexes start at 1
		int index = start - 1;
		int replOff = repStart - start;
		while(index < stop) {
			int replIndex = replOff + index;
			if (index >= maxSelfIndex) { break; }
			if (replIndex >= maxReplIndex) { break; }
			int b = replacement.fetchByteAt(replIndex);
			self.storeByteAt(index++, b);
		}
		
		return true;
	};
	
}
