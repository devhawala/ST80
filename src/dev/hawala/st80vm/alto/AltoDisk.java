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

package dev.hawala.st80vm.alto;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.interpreter.Interpreter;
import dev.hawala.st80vm.interpreter.Interpreter.Primitive;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.OTEntry;
import dev.hawala.st80vm.primitives.iVmFilesHandler;

/**
 * Implementation of the vendor specific primitives (here: Xerox)
 * for the Alto as infrastructure for Smalltalk-80. These are the 
 *  primitives 128 (dskPrim) and 135 (beSnapshotSerialNumber).
 *  
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class AltoDisk {
	
	private static void logf(String format, Object... args) {
		if (Config.TRACE_ALTODISK_IO) { System.out.printf(format, args); }
	}
	
	/*
	 * implementation of Alto-specific primitives for the Smalltalk-80 interpreter
	 */
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> AltoFile." + name); } }
	
	private static final int CRR = 18496;
	private static final int CCR = 18512;
	private static final int CCW = 18520;
	private static final int CWW = 18536;
	private static String cmdName(int code) {
		switch(code) {
		case CRR: return "CRR";
		case CCR: return "CCR";
		case CCW: return "CCW";
		case CWW: return "CWW";
		default: return "???";
		}
	}
	
	private static final int STATUS_OK = 0x0000;
	private static final int STATUS_INV_CYLINDER = 0x0080;
	private static final int STATUS_INV_SECTOR = 0x0003;
	private static final int STATUS_CHECK_ERROR = 0x0002;
	
	// Primitive 128 :: dskprim: diskNumber address: diskAddress command: diskCommand page: buffer semaphore: aSemaphore
	public static final Primitive primitiveDskPrim = () -> { w("primitiveDskPrim");
		logf("\n**\n** AltoFile.primitiveDskPrim\n**\n");
		
		// get arguments
		int semaphorePointer = Interpreter.popStack();
		int bufferPointer = Interpreter.popStack();
		int diskCommand = Interpreter.positive16BitValueOf(Interpreter.popStack());
		int diskAddress = Interpreter.positive16BitValueOf(Interpreter.popStack());
		int diskNumber = Interpreter.positive16BitValueOf(Interpreter.popStack());
		int receiverPointer = Interpreter.popStack();
		if (!Interpreter.success()) {
			logf("** (failed to get arguments form stack)\n**\n\n");
			Interpreter.unPop(6);
			return Interpreter.primitiveFail();
		}
		Interpreter.unPop(1); // leave receiver on the stack as return value 
		
		OTEntry receiver = Memory.ot(receiverPointer); // word at index 7 :: error
		OTEntry buffer = Memory.ot(bufferPointer);
		
		logf("** diskNumber: %d , diskAddress: 0x%04X , diskCommand: 0x%04X (%s) , bufferPointer: 0x%04X , semaphorePointer: 0x%04X\n",
				diskNumber, diskAddress, diskCommand, cmdName(diskCommand), bufferPointer, semaphorePointer);
		logf("** buffer: %s\n", buffer);
		logf("** semaphore: %s\n", Memory.ot(semaphorePointer));
		
		logf("** label words: 0x %04X %04X %04X %04X %04X %04X %04X %04X\n",
				buffer.fetchWordAt(0), buffer.fetchWordAt(1), buffer.fetchWordAt(2), buffer.fetchWordAt(3),
				buffer.fetchWordAt(4), buffer.fetchWordAt(5), buffer.fetchWordAt(6), buffer.fetchWordAt(7));
		
		if (!Disk.isPresent() || "???".equals(cmdName(diskCommand))) {
			logf("** (no disk or unknown diskCommand)\\n**\n\n");
			Interpreter.unPop(5);
			return Interpreter.primitiveFail();
		}
		
		// get the page
		DiskPage page;
		try {
			page = DiskPage.forRDA(diskAddress);
		} catch (Exception e) {
			logf("** error while getting page: %s\n", e.getMessage());
			page = null;
		}
		if (page == null) {
			receiver.storePointerAt(7, Interpreter.positive16BitIntegerFor(STATUS_INV_SECTOR));
			Interpreter.asynchronousSignal(semaphorePointer);
			logf("**\n\n");
			return Interpreter.success();
		}
		
		// handle sector label
		int[] labelWords = new int[DiskPage.WORDLEN_LABEL];
		switch(diskCommand) {
		case CRR:
			page.getLabelDataToWords(labelWords);
			for (int i = 0; i < labelWords.length; i++) {
				buffer.storeWordAt(i, labelWords[i]);
			}
			break;
		case CCR:
		case CCW:
			page.getLabelDataToWords(labelWords);
			for (int i = 0; i < labelWords.length; i++) {
				buffer.storeWordAt(i, labelWords[i]);
				
				// initial code as specified by Alto disk interface, but Smalltalk does not like it...
//				int w = buffer.fetchWordAt(i);
//				if (w == 0) {
//					buffer.storeWordAt(i, labelWords[i]);
//				} else if (w != labelWords[i]) {
//					logf("** check error on label word[%d]: expected 0x%04X , disk 0x%04X\n", i, w, labelWords[i]);
//					receiver.storePointerAt(7, Interpreter.positive16BitIntegerFor(STATUS_CHECK_ERROR));
//					Interpreter.asynchronousSignal(semaphorePointer);
//					logf("**\n\n");
//					return Interpreter.success();
//				}
			}
			break;
		case CWW:
			for (int i = 0; i < labelWords.length; i++) {
				labelWords[i] = buffer.fetchWordAt(i);
			}
			page.setLabelDataFromWords(labelWords);
			break;
		default:
			logf("** unknown operation on sector label ??\n");
		}
		
		// handle sector data
		switch(diskCommand) {
		case CRR:
		case CCR:
			for (int i = 0; i < Math.min(DiskPage.WORDLEN_DATA, buffer.getWordLength() - DiskPage.WORDLEN_LABEL); i++) {
				buffer.storeWordAt(DiskPage.WORDLEN_LABEL + i, page.getWord(i));
			}
			break;
		case CCW:
		case CWW:
			for (int i = 0; i < Math.min(DiskPage.WORDLEN_DATA, buffer.getWordLength() - DiskPage.WORDLEN_LABEL); i++) {
				page.putWordRaw(i, buffer.fetchWordAt(DiskPage.WORDLEN_LABEL + i));
			}
			break;
		default:
			logf("** unknown operation on sector data ??\n");
		}
		
		// done
		receiver.storePointerAt(7, Interpreter.positive16BitIntegerFor(STATUS_OK));
		Interpreter.asynchronousSignal(semaphorePointer);
		logf("**\n\n");
		return Interpreter.success();
	};
	
	private static iVmFilesHandler vmFilesHandler = null;
	
	public static void setVmFilesHandler(iVmFilesHandler handler) {
		vmFilesHandler = handler;
	}
	
	// Primitive 135 :: beSnapshotSerialNumber: aByteArray leaderVirtualDiskAddr: anInteger
	public static final Primitive primitiveBeSnapshotSerialNumber = () -> { w("primitiveBeSnapshotSerialNumber");
		logf("\n**\n** AltoFile.primitiveBeSnapshotSerialNumber\n");
		
		int leaderVirtualDiskAddr = Interpreter.positive16BitValueOf(Interpreter.popStack());
		int serialNumberPointer = Interpreter.popStack();
		// leave receiver on the stack, will be returned
		
		DiskPage leaderDiskPage = DiskPage.forVDA(leaderVirtualDiskAddr);
		LeaderPage leaderPage = new LeaderPage(leaderDiskPage);
		String filename = leaderPage.getFilename();
		
		logf("** serialNumberPointer: 0x%04X , leaderVirtualDiskAddr: %d\n", serialNumberPointer, leaderVirtualDiskAddr);
		logf("** serialNumber: %s\n", Memory.ot(serialNumberPointer));
		logf("** snapshot-filename: %s\n", filename);
		
		if (vmFilesHandler != null) {
			vmFilesHandler.setSnapshotFilename(filename);
		}
	
		logf("**\n\n");
		Interpreter.unPop(2);
		return true;
	};

}
