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
 * and bitblt related) as defined/referenced in ST80-DV6.sources and Analyst(-1.2).sources.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Dv6Specifics {
	
private Dv6Specifics() { }
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> Dv6Specifics." + name); } }
	
	private static void gclogf(String pattern, Object... args) {
		if (Config.LOG_MEM_GC) { System.out.printf(pattern, args); }
	}
	
	private static void logf(String pattern, Object... args) {
		System.out.printf(pattern, args);
	}
	
	/*
	 * primitives defined/used in ST80-DV6.sources
	 */

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
	//
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
	//
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
	//
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
	//
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
	//
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
	//
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
	//
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
	
	/*
	 * primitives defined/used in Analyst(-1.2).sources
	 * 
	 * Remarks:
	 * - these primitives are implemented as dummies (with logging to stdout) for now,
	 *   allowing Analyst-1.2 to work with a printer and the XDE command line without
	 *   asking if debugging should be started.
	 * - status primitives for SerialPort try to tell the system "no working RS232 port present".
	 * - however some SerialPort primitives (send/receive) will intentionally fail after
	 *   logging.
	 */
	
	// SerialPort
	// ==========
	//
	// This class provides the asynchronous communication with the external device by
	// using RS232C port. The client can send or receive the data frame(block) of the
	// arbitrary size.
	//
	// The usual sequence of using this interface is as follow.
	// 1. Create a instance of this class.
	// 2. Open the port.
	// 3. Set the parameters.
	// 4. Reset the port.
	// 5. Start communicating by setting the appropriate control line on.
	// 6. Send or receive data
	// 7. End communicating by setting the appropriate control line off.
	// 8. Close the port.
	//
	// The default parameter values are as follows.
	// line speed => 1200 bps
	// character length => 7 bits
	// the number of stop bits => 1
	// parity => odd
	// frame timeout => 0 milliseconds
	// the internal buffer size => 512 bytes
	// the number of input buffers => 12
	// the number of output buffers => 4
	//
	// Note that, on input,  the frame timeout occur when the next data byte does not
	// arrive within the specified milliseconds since the last data byte arrives. However,
	// the statsu of the input frame(block) is set the success. The value zero of the frame
	// timeout means the infinite timeout.
	//
	// The client must take care about the following things.
	// 1. The client must close the port when you finish using it. Otherwise, the port can
	//    not be open any more.
	// 2. Keep the inernal buffer space smaller. The client should set the parameters, the
	//    buffer size, the number of input/output buffer, with the amount just necessary
	//    for your communication. Using the large amount of the internal buffer space may
	//    cause the system down.'!
	
	// (primitive 200)
	// maxPortNumber
	//
	// Answer the number of ports available on the machine.
	//
	public static final Primitive primitiveSerialPortMaxPortNumber = () -> { w("primitiveSerialPortMaxPortNumber");
		// pop self
		popStack();
		
		// return 0 = no ports on this machine
		// (hoping that this will prevent further calls to SerialPort primitives) 
		logf("\n## primitiveSerialPortMaxPortNumber() -> returning 0 (none)");
		push(Well.known().ZeroPointer);
		
		return true;
	};
	
	// (primitive 201)
	// create: number
	//
	// Create the channel on the given port number. the port number starts at 1.
	//
	public static final Primitive primitiveSerialPortCreate = () -> { w("primitiveSerialPortCreate");
		int portNumber = positive16BitValueOf(popStack());
		// leave self on the stack
	
		logf("\n## primitiveSerialPortCreate( %d ) ... ignored", portNumber);
		
		// done (ignored)
		return true;
	};
	
	// (primitive 202)
	// delete
	//
	// Delete the channel.
	//
	public static final Primitive primitiveSerialPortDelete = () -> { w("primitiveSerialPortDelete");
		// leave self on the stack
	
		logf("\n## primitiveSerialPortDelete() ... ignored");
		
		// done (ignored)
		return true;
	};

	// (primitive 203)
	// reset
	//
	// Initialize the channel status and the internal buffers and validate parameters.
	// DTR(Data Terminal Ready) and RTS(Request To Send) is reset to off
	//
	public static final Primitive primitiveSerialPortReset = () -> { w("primitiveSerialPortReset");
		// leave self on the stack
		
		logf("\n## primitiveSerialPortReset() ... ignored");
		
		// done (ignored)
		return true;
	};
	
	// (primitive 204)
	// readStatus: index
	//
	// Answer the value of the parameter specified by index.
	//
	public static final Primitive primitiveSerialPortReadStatus = () -> { w("primitiveSerialPortReadStatus");
		int index = positive16BitValueOf(popStack());
		popStack(); // pop self
		
		// return 0, resulting to 'false' or an invalid array index for most index cases
		logf("\n## primitiveSerialPortReadStatus( %d ) -> return 0", index);
		push(Well.known().ZeroPointer);
		
		// done
		return true;
	};
	
	// (primitive 205)
	// writeStatus: index with: value
	//
	// Set value as the value of the parameter specified by index.
	//
	public static final Primitive primitiveSerialPortWriteStatus = () -> { w("primitiveSerialPortWriteStatus");
		int value = positive16BitValueOf(popStack());
		int index = positive16BitValueOf(popStack());
		// leave self on the stack
	
		logf("\n## primitiveSerialPortWriteStatus( index: %d , value: %d ) ... ignored", index, value);
		
		// done (ignored)
		return true;
	};
	
	// (primitive 206)
	// receive: aByteArray startingAt: startIndex signal: semaphore
	//
	// Receive the data frame(block) from serial line into aByteArray.
	// The received data is stored into bytes starting from startIndex + 4.
	// The status of the receiving is stored into a byte at startIndex + 1.
	// The data count is stored into bytes at startIndex + 2 and startIndex + 3.
	// The startIndex must be a even number.
	// The data space in aByteArray must be larger than the intenal buffer size.
	// However, you can not receive the data more than the internal buffer size at one time.
	// The semaphore will be signaled after the receiving is completed
	//
	public static final Primitive primitiveSerialPortReceive = () -> { w("primitiveSerialPortReceive");
		// intentionally fail
		logf("\n## primitiveSerialPortReceive() => fail!");
		return false;
	};
	
	// (primitive 207)
	// send: aByteArray startingAt: startIndex size: dataSize signal: semaphore
	//
	// Send the data frame(block) in aByteArray onto the serial line and return aSmallInteger representing the output status.
	// The dataSize must be less than the internal buffer size.
	// The semaphore will be signaled after sending is completed.
	//
	public static final Primitive primitiveSerialPortSend = () -> { w("primitiveSerialPortSend");
		// intentionally fail
		logf("\n## primitiveSerialPortSend() => fail!");
		return false;
	};
	
	
	// PrintFrom1108
	// =============
	// 
	// This class contains two primitives and supporting messages which are meant as a temporary device for sending press
	// and interpress files directly from Smalltalk on the 1108'
	
	// (primitive 178)
	// sendPrintFile: file printerType: typeInteger printer: printer copies: copies signal: aSemaphore
	//
	// starts a process to send numCopies of the filename to the printer printerName.
	//   typeInteger = 1 is an interpress printer (will use Print tool),
	//   typeInteger = 2 is press (will use OPrint tool).
	// When it finishes (for whatever reason), doneSemaphore will be signaled
	//
	public static final Primitive primitiveSendPrintFile = () -> { w("primitiveSendPrintFile");
		int semaphorePointer = popStack();
		int copiesPointer = popStack();
		int printerPointer = popStack();
		int printerTypePointer = popStack();
		int sendPrintFilePointer = popStack();
		// leave self on the stack
		
		String sendPrintFile = Memory.getStringValue(sendPrintFilePointer);
		String printerType
		           = (printerTypePointer == Well.known().OnePointer)
		           ? "Interpress" : (printerTypePointer == Well.known().TwoPointer)
		           ? "Press"
		           : "invalid";
		String printerName = Memory.getStringValue(printerPointer);
		int copies = positive16BitValueOf(copiesPointer);
				
		logf("\n## primitiveSendPrintFile( file = '%s' , type: %s , printer: '%s' , copies: %d )",
			 sendPrintFile, printerType, printerName, copies);
		logf("\n##   -> signalling semaphore 0x%04X", semaphorePointer);
		Interpreter.asynchronousSignal(semaphorePointer);
		
		// done
		return true;
	};
	
	// (primitive 179)
	// printResult
	//
	// returns an integer indicating the status of the last call on the primitive sendInterpress:printer:copies:signal:
	// the integer correspondence is:
	//   1 = success; 2 = warning; 3 = abort; 4 = error; 5 = notStarted; 5 = pending
	//
	public static final Primitive primitivePrintResult = () -> { w("primitivePrintResult");
		// pop self
		popStack();
		
		// return 1 = success
		logf("\n## primitivePrintResult() -> returning 1 (success)");
		push(Well.known().OnePointer);
		
		return true;
	};
	
	
	// XDEInterfaceModel
	// =================
	
	// (primitive 180)
	// sendCommandLine: theCommand signal: aSemaphore
	//
	// starts a process to send theCommand to the tajo executive window.  When it finishes (for whatever reason),
	// aSemaphore will be signaled
	//
	public static final Primitive primitiveXdeSendCommandLine = () -> { w("primitiveXdeSendCommandLine");
		int semaphorePointer = popStack();
		int commandLinePointer = popStack();
		// leave self on the stack
		
		String commandLine = Memory.getStringValue(commandLinePointer);
		logf("\n## primitiveXdeSendCommandLine( '%s' )", commandLine);
		logf("\n##   -> signalling semaphore 0x%04X", semaphorePointer);
		Interpreter.asynchronousSignal(semaphorePointer);
		
		return true;
	};
	
	// (primitive 181)
	// status
	//
	// returns an integer indicating the status of the last call on the primitive sendCommandLine:signal:
	// the integer correspondence is: 1 = success; 2 = warning; 3 = abort; 4 = error; 5 = notStarted; 6 = pending
	//
	public static final Primitive primitiveXdeStatus = () -> { w("primitiveXdeStatus");
		// pop self
		popStack();
		
		// return 1 = success
		logf("\n## primitiveXdeStatus() -> returning 1 (success)");
		push(Well.known().OnePointer);
		
		return true;
	};

}