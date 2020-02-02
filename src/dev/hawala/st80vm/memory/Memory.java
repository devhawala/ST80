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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.interpreter.Interpreter;

/**
 * Object memory for the Smalltalk engine implementing the Object Memory Interface
 * as defined in the Bluebook pp. 570..573, as well as loading/saving the object
 * memory from/to external snapshot files and automatic memory management.
 * <p>
 * Memory management uses a two-fold strategy:
 * </p>
 * <ul>
 * <li>reference counting is used as basic management method, providing a fast re-use
 * of unused objects of same size, as this happens often in Smalltalk (mostly for
 * method and block contexts)</li>
 * <li>when some usage limits are reached for the object table or the heap, a mark&amp;sweep
 * garbage collection with heap compaction is performed</li>
 * </ul>
 * <p>
 * (a garbage collection is also executed after loading a snapshot and before writing a snapshot)
 * </p>
 * <p>
 * This object memory largely benefits from the fact that memory constraints are much smaller today
 * as they were when Smalltalk-80 was specified and first implemented using 16 bit object pointers 
 * and max. 1 MWord (= 2 MByte) of heap memory, allowing to renounce to implementation tricks described
 * in the Bluebook and use a simpler design:
 * </p>
 * <ul>
 * <li>the object table resides outside the heap, leaving more heap space to Smalltalk</li>
 * <li>the object table holds Java object instances describing the Smalltalk object at their
 * OOP in the table; this allows to have more runtime state about objects than allowed by the
 * 2 words in the original Smalltalk-80 object table</li>
 * <li>the object table is 65536 entries long and holds the representations of all objects, including
 * SmallInteger OOPs, allowing for an uniform addressing and handling scheme of all objects without caring
 * if an OOP is the SmallInteger exception (no heap space, no object table entry) as Smalltalk-80 specified it.</li>
 * <li>heap compaction can simply use a second memory space for copying the instance memory of objects (i.e.
 * using 2x 1 MWords for heap space, in total 4 MByte)
 * </ul>
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Memory {
	
	private Memory() { }
	
	private static void logf(String pattern, Object... args) {
		if (Config.LOG_MEM_OPS) { System.out.printf(pattern, args); }
	}
	
	private static void gclogf(String pattern, Object... args) {
		if (Config.LOG_MEM_GC) { System.out.printf(pattern, args); }
	}
	
	private static void gcvlogf(String pattern, Object... args) {
		if (Config.LOG_MEM_GC_DETAILS) { System.out.printf(pattern, args); }
	}
	
	// the heap memory of the Smalltalk-80 machine
	// capacity: up to 16 segments with 65536 words = 1 MWords = 2 MByte
	// initialized by loadVirtualImage()
	/* package-access */ static short[] heapMemory;
	
	// temp heap-size memory area used for next heap compaction
	// (heapMemory and compactHeap are swapped after each compaction)
	// initialized by loadVirtualImage()
	private static short[] compactHeap;
	
	// current heap usage data
	private static int heapUsed = 0;
	private static int heapLimit = 0;
	private static int heapRemaining = 0;
	private static final int HEAP_LIMIT_TO_USED = 64 * 1024; // heap usage can grow by this before a compaction is forced
	
	private static int heapSizeLoadedLimit = -1;
	
	// the ObjectTable of the Smalltalk-80 machine (16 bit OOPs)
	// capacity: 65536 entries => 32678 SmallIntegers , max. 32768 object-pointer
	// each OOP has 2 words (shorts) in the ObjectTable (from most to least significant, according to BlueBook p.661):
	// - word[0]:
	//   ->  8 bit: Count (for reference-counting, 0 = unreferenced pointer ~> this OOP is in heap free chunk list?)
	//   ->  1 bit: Odd-Length bit
	//   ->  1 bit: Pointer-Fields bit
	//   ->  1 bit: Free-Entry bit (1 => unused object-pointer)
	//   ->  1 bit: (unspecified / unused?)
	//   ->  4 bit: Segment  \
	// - word[1]:             |-> 20 bit address in heapMemory
	//   -> 16 bit: Location /
	//
	// difference to Bluebook in this implementation: the objectTable holds both objects and smallintegers at
	// their respective objectPointer, the entries are instances of either OTIntEntry or OTObjectEntry, which
	// have the same interface but behave differently
	private static OTEntry[] objectTable;
	
	// total number of free objects in the objectTable, no matter if truly free or in one of the free-lists 
	private static int freeObjectCount = 0;
	
	// current usage limit of object-pointers
	// (automatically incremented when all object-pointers below the limit are exhausted)
	private static int otLimit;
	
	// boundary interval for the high-mark of OOPs to be considered for allocation
	// (transgressing the high-mark will cause a compaction and raising the high-mark by this value
	// if no free OOPs are available below the high-mark after compaction)
	private static final int OTLIMIT_LEAPS = 256;
	
	// lowest last known free OOP (hint for starting search the next free OOP)
	private static int lowestFreeOop = 1;
	
	// last bit of true objectPointer (i.e. SmallIntegers have the inverted bit)
	// Bluebook: 0 => object , 1 => smallinteger
	// but DV6!: 1 => object , 0 => smallinteger
	private static int oopMarkerBit = 0;
	
	// last bit for a SmallInteger oop
	private static int smallIntMarkerBit = 1;
	
	// specific object instances required for garbage collection (if mark&sweep GC is ever implemented)
	private static int processorOop;
	private static OTEntry processorObject;
	private static int smalltalkOop;
	private static OTEntry smalltalkObject;
	
	// basic access to the object-table
	public static OTEntry ot(int objectPointer) {
		// paranoia checks
		if (objectPointer < 0 || objectPointer >= objectTable.length) {
			throw new RuntimeException(String.format(
						"Error invoking ot(): invalid objectPointer 0x%04X (%d)",
						objectPointer,
						objectPointer));
		}
		
		// get the OTEntry (can be an allocated object, a free object or a smallinteger)
		OTEntry  e = objectTable[objectPointer];
		return e;
	}
	
	private static void initializeObjectTable() {
		// check which oop marker bit is used by this image
		oopMarkerBit = heapMemory[1] & 0x0001; // this assumes that the first 2 heap words have an object, usually the content of 'NilPointer', this is then Nil's classPointer
		
		// inverse the oop Marker bit for SmallInteger
		smallIntMarkerBit = (oopMarkerBit == 0) ? 1 : 0;
		
		// create the ObjectTable entries
		int nextOOP = 0;
		objectTable = new OTEntry[65536];
		while (nextOOP < objectTable.length) {
			if (oopMarkerBit == 0) {
				objectTable[nextOOP] = new OTObjectEntry(nextOOP);
				objectTable[nextOOP+1] = new OTIntEntry(nextOOP+1);
			} else {
				objectTable[nextOOP] = new OTIntEntry(nextOOP);
				objectTable[nextOOP+1] = new OTObjectEntry(nextOOP+1);
			}
			
			nextOOP += 2;
		}
		otLimit = OTLIMIT_LEAPS; // smallest limit, giving a enough entries before the limit must be raised
	}
	
	/*
	 * loading and writing a VirtualImage
	 */
	
	private static String imageFilename = "Snapshot";
	
	private static short[] buffer = new short[256];
	
	private static int loadPage(InputStream istream, short[] buf, int offset) throws IOException {
		int wordsRead = 0;
		for (int i = 0; i < 256; i++) {
			int b0 = istream.read();
			if (b0 < 0) { return wordsRead; }
			int b1 = istream.read();
			if (b1 < 0) { return wordsRead; }
			buf[offset + i] = (short)(((b0 & 0xFF) << 8) | (b1 & 0xFF)); 
			wordsRead++;
		}
		return wordsRead;
	}
	
	private static boolean isSymbol(int symbolOop, byte[] symbolText) {
		OTEntry e = ot(symbolOop);
		int byteLen = e.getByteLength();
		
		if (byteLen != symbolText.length) {
			return false;
		}
		for (int i = 0; i < byteLen; i++) {
			if (e.fetchByteAt(i) != (symbolText[i] & 0x00FF)) {
				return false;
			}
		}
		return true;
	}
	
	public static void loadVirtualImage(String filename, boolean fixDV6missingSegments) throws IOException {
		try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(filename))) {
			
			imageFilename = filename; // seems to exist, so remember it
			
			/*
			 * virtual-image metadata
			 */
			
			// load metadata page
			int len = loadPage(bis, buffer, 0);
			if (len < buffer.length) { throw new RuntimeException("Invalid VirtualImage (metadata page too short)"); }
			
			// interpret and check relevant metadata
			int lastSegment = buffer[0];
			int limitInLastPage = buffer[1] & 0xFFFF;
			int usedWordsInObjectTable = buffer[3] & 0xFFFF;
			if (lastSegment < 0 || lastSegment > 15) { throw new RuntimeException("Invalid VirtualImage, invalid lastSegment: " + lastSegment); }
			if ((usedWordsInObjectTable & 1) != 0) { throw new RuntimeException("Invalid VirtualImage (odd number of words in ObjectTable"); }
			
			/*
			 * virtual-image heap
			 */
			
			// create heap memory
			heapMemory = new short[16 * 65536];
			compactHeap = new short[heapMemory.length];
			
			// read heap memory
			int pagesToRead = (lastSegment << 8) | (limitInLastPage >>> 8);
			if ((limitInLastPage & 0x00FF) != 0) { pagesToRead += 1; }
			int pageBase = 0;
			for (int i = 0; i < pagesToRead; i++) {
				len = loadPage(bis, heapMemory, pageBase);
				if (len != 256) {
					throw new RuntimeException("Invalid VirtualImage, premature end of heapMemory when reading page: " + i);
				}
				pageBase += 256;
			}
			heapSizeLoadedLimit = (lastSegment << 16) + limitInLastPage;
			
			/*
			 * virtual-image object-table
			 */
			
			// create the empty object table
			initializeObjectTable();
			
			// read object table
			int entryOop = oopMarkerBit;
			int loadedEntries = 0;
			int freeEntries = 0;
			int firstFreeEntry = -1;
			int lastW1 = -1;
			int currSegment = 0;
			int lastLoadedOop = -1;
			boolean done = false;
			while(!done) {
				len = loadPage(bis, buffer, 0);
				if ((len & 1) != 0) { throw new RuntimeException("Invalid VirtualImage (odd number of words when reading ObjectTable page"); }
				int pos = 0;
				while(pos < len) {
					int currW0 = buffer[pos] & 0xFFFF;
					int currW1 = buffer[pos+1] & 0xFFFF;
					if (fixDV6missingSegments) {
						if (currW0 != 0x0020 && lastW1 > currW1) {
							currSegment = (currSegment + 1) & 0x000F;
							// logf("... apparent segment transition to segment %2d at oop 0x%04X\n", currSegment, entryOop);
						}
						if (currW0 != 0x0020 && (currW0 & 0x000F) == 0) {
							currW0 |= currSegment;
						}
						lastW1 = currW1;
					}
					objectTable[entryOop].fromWords((short)currW0, (short)currW1);
					if (objectTable[entryOop].freeEntry()) {
						if (entryOop > oopMarkerBit) { // skip 1st entry (reserved))
							freeEntries++;
							if (firstFreeEntry < 0) {
								firstFreeEntry = entryOop;
							}
						}
					} else {
						lastLoadedOop = entryOop;
					}
					entryOop += 2;
					pos += 2;
					loadedEntries++;
				}
				done = (len != 256) || (loadedEntries >= usedWordsInObjectTable);
			}
			
			// compute the upper limit of objects used so far, rounded up to 128 objects
			int tmpOtLimit = lastLoadedOop & 0xFF00;
			otLimit = Math.min(65536, tmpOtLimit + 0x0100);
			
			// get the number of free objects in the objectTable
			freeObjectCount = freeEntries + ((objectTable.length / 2) - loadedEntries);
			
			/*
			 * derive information (classes, objects) not fixed ("guaranteed") in the Bluebook
			 */
			
			// determine important class OOPs, using the well-known OOP of Nil
			OTEntry nil = ot(oopMarkerBit + 2); // first OOP in objectTable is unused, second is: NilPointer
			OTEntry cls = ot(nil.getClassOOP());
			OTEntry clsMetaclass = ot(cls.getClassOOP());
			int nameSymbolOop = heapMemory[cls.address() + 8] & 0xFFFF; // location of the classname-symbol
			OTEntry nameSymbol = ot(nameSymbolOop);
			int classMetaclassPointer = clsMetaclass.getClassOOP();
			int classSymbolPointer = nameSymbol.getClassOOP();
			
			// determine some more class pointers relevant for interpreting smalltalk code
			byte[] compiledMethodSymbol = "CompiledMethod".getBytes();
			byte[] smallIntegerSymbol = "SmallInteger".getBytes();
			byte[] largeNegativeIntegerSymbol = "LargeNegativeInteger".getBytes();
			byte[] processorSchedulerSymbol = "ProcessorScheduler".getBytes();
			byte[] processSymbol = "Process".getBytes();
			byte[] systemDictionarySymbol = "SystemDictionary".getBytes();
			byte[] floatSymbol = "Float".getBytes();
			byte[] semaphoreSymbol = "Semaphore".getBytes();
			byte[] associationSymbol = "Association".getBytes();
			byte[] yieldSymbol = "yield".getBytes();
			int classCompiledMethodPointer = -1;
			int classSmallIntegerPointer = -1;
			int classLargeNegativeIntegerPointer = -1;
			int classProcessorSchedulerPointer = -1;
			int classProcessPointer = -1;
			int classSystemDictionaryPointer = -1;
			int classFloatPointer = -1;
			int classSemaphorePointer = -1;
			int classAssociationPointer = -1;
			int yieldSelectorPointer = -1;
			int metaclassOop = oopMarkerBit;
			while ( ( classCompiledMethodPointer == -1 
					  || classSmallIntegerPointer == -1
					  || classLargeNegativeIntegerPointer == -1
					  || classProcessorSchedulerPointer == -1
					  || classProcessPointer == -1
					  || classSystemDictionaryPointer == -1
					  || classFloatPointer == -1
					  || classSemaphorePointer == -1
					  || yieldSelectorPointer == -1)
					&& metaclassOop < objectTable.length) {
				OTEntry otMetaclass = ot(metaclassOop);
				if (otMetaclass.getClassOOP() == classMetaclassPointer) {
					int classOop = otMetaclass.fetchPointerAt(6); // field for the class this metaclass is for
					OTEntry otClass = ot(classOop);
					int otClassNameSymbolPointer = otClass.fetchPointerAt(6);
					if (classCompiledMethodPointer == -1 && isSymbol(otClassNameSymbolPointer, compiledMethodSymbol)) {
						classCompiledMethodPointer = classOop;
					}
					if (classSmallIntegerPointer == -1 && isSymbol(otClassNameSymbolPointer, smallIntegerSymbol)) {
						classSmallIntegerPointer = classOop;
					}
					if (classLargeNegativeIntegerPointer == -1 && isSymbol(otClassNameSymbolPointer, largeNegativeIntegerSymbol)) {
						classLargeNegativeIntegerPointer = classOop;
					}
					if (classProcessorSchedulerPointer == -1 && isSymbol(otClassNameSymbolPointer, processorSchedulerSymbol)) {
						classProcessorSchedulerPointer = classOop;
					}
					if (classProcessPointer == -1 && isSymbol(otClassNameSymbolPointer, processSymbol)) {
						classProcessPointer = classOop;
					}
					if (classSystemDictionaryPointer == -1 && isSymbol(otClassNameSymbolPointer, systemDictionarySymbol)) {
						classSystemDictionaryPointer = classOop;
					}
					if (classFloatPointer == -1 && isSymbol(otClassNameSymbolPointer, floatSymbol)) {
						classFloatPointer = classOop;
					}
					if (classSemaphorePointer == -1 && isSymbol(otClassNameSymbolPointer, semaphoreSymbol)) {
						classSemaphorePointer = classOop;
					}
					if (classAssociationPointer == -1 && isSymbol(otClassNameSymbolPointer, associationSymbol)) {
						classAssociationPointer = classOop;
					}
				} else if (yieldSelectorPointer == -1 && isSymbol(metaclassOop, yieldSymbol)) {
						yieldSelectorPointer = metaclassOop;
					}
				metaclassOop += 2;
			}
			
			// initialize well-known object-pointers etc.
			Well.initialize(
					oopMarkerBit,
					classSymbolPointer,
					classMetaclassPointer,
					classCompiledMethodPointer,
					classSmallIntegerPointer,
					classLargeNegativeIntegerPointer,
					classFloatPointer,
					classSemaphorePointer,
					classAssociationPointer,
					yieldSelectorPointer);
			
			// find the Processor object (singleton instance of class ProcessorScheduler, according to Smalltalk sources...)
			// => this will also give us the activeProcess as continuation point for execution after loading the image
			processorOop = initialInstanceOf(classProcessorSchedulerPointer);
			if (processorOop == Well.known().NilPointer) {
				throw new IllegalStateException("Processor singleton object not found in VirtualImage");
			}
			processorObject = ot(processorOop);
			int secondProcessorOop = instanceAfter(processorOop);
			if (secondProcessorOop != Well.known().NilPointer) {
				System.out.println("**** warning: found ssecond instance of ProcessorScheduler class");
			}
			
			// find the 'Smalltalk' object
			smalltalkOop = initialInstanceOf(classSystemDictionaryPointer);
			if (smalltalkOop == Well.known().NilPointer) {
				throw new IllegalStateException("Smalltalk systemdictionary object not found in VirtualImage");
			}
			smalltalkObject = ot(smalltalkOop);
			int secondSystemDict = instanceAfter(smalltalkOop);
			if (secondSystemDict != Well.known().NilPointer) {
				System.out.println("**** warning: found second instance of SystemDictionary class");
			}
			
			// prepare memory management heap- and object-counters/-indicators for usage
			if (firstFreeEntry >= 0) {
				lowestFreeOop = firstFreeEntry;
			} else {
				lowestFreeOop = Well.known().NilPointer;
			}
			gc("initial");
			
			// finally give some infos
			if (Config.LOG_MEM_OPS) {
				System.out.printf("Loaded image:\n");
				System.out.printf(" - heapSizeLoadedLimit ............ = 0x%06X\n", heapSizeLoadedLimit);
				System.out.printf(" - usedWordsInObjectTable ......... = %d\n", usedWordsInObjectTable);
				System.out.printf(" - loadedEntries .................. = %d\n", loadedEntries);
				System.out.printf(" - lastLoadedOop .................. = %d\n", lastLoadedOop);
				System.out.printf(" - otLimit ........................ = %d\n", otLimit);
				System.out.printf(" - freeEntries .................... = %d (below loadedEntries)\n", freeEntries);
				System.out.printf(" - freeEntries .................... = %d (total)\n", freeEntries + (32768 - loadedEntries));
				System.out.printf(" - otLimit ........................ = %d\n", otLimit);
				System.out.printf(" - classSymbolPointer ............. = 0x%04X\n", classSymbolPointer);
				System.out.printf(" - classMetaclassPointer .......... = 0x%04X\n", classMetaclassPointer);
				System.out.printf(" - classCompiledMethodPointer ..... = 0x%04X\n", classCompiledMethodPointer);
				System.out.printf(" - classSmallIntegerPointer ....... = 0x%04X\n", classSmallIntegerPointer);
				System.out.printf(" - classLargeNegativeIntegerPointer = 0x%04X\n", classLargeNegativeIntegerPointer);
				System.out.printf(" - classProcessorSchedulerPointer . = 0x%04X\n", classProcessorSchedulerPointer);
				System.out.printf(" - classProcessPointer ............ = 0x%04X\n", classProcessPointer);
				System.out.printf(" - classSystemDictionaryPointer ... = 0x%04X\n", classSystemDictionaryPointer);
				System.out.printf(" - classFloatPointer .............. = 0x%04X\n", classFloatPointer);
				System.out.printf(" - classSemaphorePointer .......... = 0x%04X\n", classSemaphorePointer);
				System.out.printf(" - classAssociationPointer ........ = 0x%04X\n", classAssociationPointer);
				System.out.printf(" - yieldSelectorPointer ........... = 0x%04X\n", yieldSelectorPointer);
				
				System.out.println("\nElementary objects:");
				System.out.printf(" - Smalltalk ...................... = 0x%04X\n", smalltalkOop);
				System.out.printf(" - Processor ...................... = 0x%04X\n", processorOop);
				OTEntry activeProcess = ot(getImageRestartActiveProcessPointer());
				System.out.printf(" - Processor.activeProcess ........ = 0x%04X (classOOP: 0x%04X)\n", activeProcess.objectPointer(), activeProcess.getClassOOP());
			}
		}
	}
	
	private static void resetBuffer() {
		for (int i = 0; i < buffer.length; i++) {
			buffer[i] = 0;
		}
	}
	
	private static void writeWords(OutputStream os, short[] buf, int wordCount) throws IOException {
		for (int i = 0; i < wordCount; i++) {
			short w = buf[i];
			os.write((w >> 8) & 0x00FF);
			os.write(w & 0x00FF);
		}
	}
	
	public static void writeSnapshotFile(String toFilename) throws IOException {
		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(toFilename))) {
			// reduce memory usage before writing and get the last objectPoointer really in use
			int lastOop = gc("snapshot");
			
			// create & write metadata page
			int otWordCount = (lastOop & 0xFFFE) + 2;
			resetBuffer();
			buffer[0] = (short)((heapUsed >> 16) & 0xFFFF);
			buffer[1] = (short)(heapUsed & 0xFFFF);
			buffer[2] = (short)((otWordCount >> 16) & 0xFFFF);
			buffer[3] = (short)(otWordCount & 0xFFFF);
			writeWords(bos, buffer, buffer.length);
			
			// write heap (used heap words, rounded up to a full page)
			int heapWordsToWrite = (heapUsed & 0xFFFFFF00) + (((heapUsed & 0x000000FF) == 0) ? 0 : 256);
			writeWords(bos, heapMemory, heapUsed);
			if (heapWordsToWrite > heapUsed) {
				resetBuffer();
				writeWords(bos, buffer, heapWordsToWrite - heapUsed);
			}
			
			// write object table (only words effectively in use)
			int wordsInPage = 0;
			for (int oop = oopMarkerBit; oop <= lastOop; oop +=2) {
				OTEntry oe = ot(oop);
				buffer[wordsInPage++] = oe.getWord0();
				buffer[wordsInPage++] = oe.getWord1();
				if (wordsInPage >= buffer.length) {
					writeWords(bos, buffer, wordsInPage);
					wordsInPage = 0;
				}
			}
			if (wordsInPage > 0) {
				writeWords(bos, buffer, wordsInPage);
			}
		}
	}
	
	public static void saveVirtualImage(String filename) {
		String what = "?";
		try {
			String fn = (filename != null) ? filename : imageFilename;
			String tmpFn = fn + ".tmp";
						
			what = "write temp snapshot-file";
			writeSnapshotFile(tmpFn);
			
			what = "rename existing snapshot-file";
			File snapshotFile = new File(fn);
			if (snapshotFile.exists()) {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd_HH.mm.ss.SSS");
				String oldSnapshotFilename = fn + "-" + sdf.format(snapshotFile.lastModified());
				File oldSnapshotFile = new File(oldSnapshotFilename);
				snapshotFile.renameTo(oldSnapshotFile);
				snapshotFile = new File(fn);
			}
			File tmpSnapshotFile = new File(tmpFn);
			tmpSnapshotFile.renameTo(snapshotFile);
			
		} catch (Exception e) {
			System.out.printf("Unable to save virtual image, error while %s: %s\n", what, e.getMessage());
		}
	}
	
	/* **
	 * ******************************* Object Memory Interface (as defined by the Bluebook)
	 * */
	
	/*
	 * "object pointer access"
	 */
	
	public static int fetchPointer(int fieldIndex, int ofObject) {
		return ot(ofObject).fetchPointerAt(fieldIndex);
	}
	
	public static void storePointer(int fieldIndex, int ofObject, int valuePointer) {
		ot(ofObject).storePointerAt(fieldIndex, valuePointer);
	}
	
	/*
	 * "word access"
	 */
	
	public static int fetchWord(int fieldIndex, int ofObject) {
		return ot(ofObject).fetchWordAt(fieldIndex);
	}
	
	public static void storeWord(int fieldIndex, int ofObject, int valueWord) {
		ot(ofObject).storeWordAt(fieldIndex, valueWord);
	}
	
	/*
	 * "byte access"
	 */
	
	public static int fetchByte(int byteIndex, int ofObject) {
		return ot(ofObject).fetchByteAt(byteIndex);
	}
	
	public static void storeByte(int byteIndex, int ofObject, int valueByte) {
		ot(ofObject).storeByteAt(byteIndex, valueByte);
	}
	
	/*
	 * "reference counting"
	 */
	
	public static void increaseReferencesTo(int objectPointer) {
		ot(objectPointer).countUp();
	}
	
	public static void decreaseReferencesTo(int objectPointer) {
		ot(objectPointer).countDown();
	}
	
	/*
	 * "class pointer access"
	 */
	
	public static int fetchClassOf(int objectPointer) {
		return ot(objectPointer).getClassOOP();
	}
	
	/*
	 * "length access"
	 */
	
	public static int fetchWordLengthOf(int objectPointer) {
		return ot(objectPointer).getWordLength();
	}
	
	public static int fetchByteLengthOf(int objectPointer) {
		return ot(objectPointer).getByteLength();
	}
	
	/*
	 * "object creation"
	 */
	
	public static int instantiateClassWithPointers(int classPointer, int instanceSize) {
		return allocateObject(instanceSize, classPointer, true, false);
	}
	
	public static int instantiateClassWithWords(int classPointer, int instanceSize) {
		return allocateObject(instanceSize, classPointer, false, false);
	}
	
	public static int instantiateClassWithBytes(int classPointer, int instanceByteSize) {
		return allocateObject((instanceByteSize + 1) / 2, classPointer, false, (instanceByteSize & 1) == 1);
	}
	
	/*
	 * "instance enumeration"
	 */
	
	public static int initialInstanceOf(int classPointer) {
		int oop = oopMarkerBit;
		while (oop < 65536) {
			OTEntry e = objectTable[oop];
			if (!e.freeEntry() && e.count() != 0 && e.getClassOOP() == classPointer) {
				return oop;
			}
			oop += 2;
		}
		return Well.known().NilPointer;
	}
	
	public static int instanceAfter(int objectPointer) {
		OTEntry obj = objectTable[objectPointer];
		int classPointer = obj.getClassOOP();
		int oop = objectPointer + 2;
		while (oop < 65536) {
			OTEntry e = objectTable[oop];
			if (!e.freeEntry() && e.count() != 0 && e.getClassOOP() == classPointer) {
				return oop;
			}
			oop += 2;
		}
		return Well.known().NilPointer;
	}
	
	/*
	 * "pointer swapping"
	 */
	
	public static void swapPointersOf(int firstPointer, int secondPointer) {
		OTEntry firstObject = ot(firstPointer);
		OTEntry secondObject = ot(secondPointer);
		if (firstObject.isSmallInt() || secondObject.isSmallInt()) {
			return; // raise misuse ERROR ??
		}
		
		// swapping is done by re-initializing the OTEntries as if they were read
		// from disk, to ensure that internal states are correctly set up.
		// BUT: references to both objects do not change, meaning that reference-counts must
		//      be preserved, so these are patched in the 1st word before reloading the OTEntry
		
		short firstW0 = firstObject.getWord0();
		short firstW1 = firstObject.getWord1();
		firstW0 = (short)((firstW0 & 0x000FF) | (secondObject.count() << 8));
		
		short secondW0 = secondObject.getWord0();
		short secondW1 = secondObject.getWord1();
		secondW0 = (short)((secondW0 & 0x000FF) | (firstObject.count() << 8));
		
		firstObject.fromWords(secondW0, secondW1);
		secondObject.fromWords(firstW0, firstW1);
	}
	
	/*
	 * "integer access"
	 */
	
	public static int integerValueOf(int objectPointer) {
		return ((short)objectPointer) >> 1;
	}
	
	public static int integerObjectOf(int value) {
		return ((value & 0x7FFF) << 1) | smallIntMarkerBit;
	}
	
	public static boolean isIntegerObject(int objectPointer) {
		return ((objectPointer & 1) != oopMarkerBit);
	}
	
	public static boolean isIntegerValue(int value) {
		return (value >= -16384 && value <= 16383);
	}
	
	/* **
	 * ********* additional interace methods (not in Bluebook ObjectMemory interface)
	 * **/
	
	public static int getUsedHeapWords() {
		return heapUsed;
	}
	
	public static int getFreeHeapWords() {
		return heapRemaining;
	}
	
	public static int getUsedObjects() {
		return (objectTable.length / 2) - freeObjectCount;
	}
	
	public static int getFreeObjects() {
		return freeObjectCount;
	}
	
	public static int getGCCount() {
		return gcCount;
	}
	
	public static int getImageRestartActiveProcessPointer() {
		return processorObject.fetchPointerAt(Well.known().ActiveProcessIndex);
	}
	
	public static int getSmalltalkPointer() {
		return smalltalkOop;
	}
	
	public static int getProcessorPointer() {
		return processorOop;
	}
	
	public static int objectPointerAsOop(int pointer) {
		int oopValue = pointer & 0xFFFE;
		if (oopMarkerBit == 0) { oopValue |= 1; }
		return oopValue;
	}
	
	public static int oopAsObjectPointer(int oopValue) {
		return (oopValue & 0xFFFE) | oopMarkerBit;
	}
	
	public static boolean hasObject(int oop) {
		OTEntry e = ot(oop);
		return e.isObject() && !e.freeEntry();
	}
	
	/* **
	 * ********* object and heap memory management
	 * **/
	
	// Map for released OOPs: heap-area-size => root-oop
	private static final Map<Integer,Integer> freesize2oop = new HashMap<>(); // objectTable entries in a free-list have: count == 0 && freeEntry == false
	
	private static final int MAX_SIZE_FOR_FREELISTS = 256; // released objects in sizes 2..256 words will be put in a free-list 
	
	public static void suspendFreeingObjects() {
		OTObjectEntry.suspendFreeingObjects();
	}
	
	public static void resumeFreeingObjects() {
		OTObjectEntry.resumeFreeingObjects();
	}
	
	/* package-access */
	static void releaseObject(OTObjectEntry e) {
		if (e.count() != 0) {
			throw new RuntimeException("Attempt to release object in use: " + e.toString());
		}
		 
		// count down referenced objects
		int refsCount = 0;
		if (e.pointerFields()) {
			refsCount = e.getWordLength();
		} else if (e.getClassOOP() == Well.known().ClassCompiledMethodPointer) {
			int headerOop = e.fetchWordAt(Well.known().HeaderIndex);
			int header = integerValueOf(headerOop);
			int literalCount = header & 0x003F; // lower 6 bits
			refsCount = literalCount + Well.known().LiteralStartIndex;
		}
		for (int i = 0; i < refsCount; i++) {
			objectTable[e.fetchPointerAt(i)].countDown();
		}
		 
		// the class is also referenced, but not seen through fetchPointerAt(), so must be counted down separately
		ot(e.getClassOOP()).countDown();
		 
		// release the object itself
		int heapSize = e.getSize();
		if (heapSize < MAX_SIZE_FOR_FREELISTS) {
			// a "small" object, so keep unused object in the corresponding free list for faster re-use
			int lastOopInSize = freesize2oop.containsKey(heapSize) ? freesize2oop.get(heapSize) : -1;
			e.enqueueInFreeList(lastOopInSize);
			freesize2oop.put(heapSize, e.objectPointer());
		} else {
			// too large for a free-list: free the object and leave its heap space for later compaction
			e.free();
			noteFreeOop(e.objectPointer());
		}
		 
		// manage global counter
		freeObjectCount++;
	}
	
	/* package-access */
	static void noteFreeOop(int oop) {
		if (oop < lowestFreeOop) {
			lowestFreeOop = oop;
		}
	}
	
	/* package-access */
	static int allocateObject(int netWordSize, int classOop, boolean pointerFields, boolean oddLength) {
		// effective size in words to allocate
		int wordSize = netWordSize + 2; // +1 for size word and +1 for class word
		 
		// manage global counter
		freeObjectCount--;
		 
		// the class has one more usage
		ot(classOop).countUp();
		 
		// check if we have a matching object in the free-list for the wordSize, if so: dequeue and re-initialize
		int firstOopInSize = freesize2oop.containsKey(wordSize) ? freesize2oop.get(wordSize) : -1;
		if (firstOopInSize > 0) {
			OTObjectEntry e = (OTObjectEntry)objectTable[firstOopInSize];
			int nextOopInSize = e.dequeueFromFreeList(classOop, pointerFields, oddLength);
			freesize2oop.put(wordSize, nextOopInSize);
			initObjectFields(e);
			logf("**** allocated object 0x%04X from freelist ( netWordSize = %d , class = 0x%04X (%s) )\n", e.objectPointer(), netWordSize, classOop, getClassName(classOop));
			return e.objectPointer();
		}
		
		// so we have to allocate a fresh object
		// compact heap if the upper limit is reached
		if ((heapUsed + wordSize) >= heapLimit) {
			gc("(heapUsed + wordSize) >= heapLimit during allocateObject()");
		}
		if (wordSize > heapRemaining) {
			// not enough memory for this object: well that was it, this is the end, meaning end-of-latin-exception...
			throw MisuseHandler.outOfMemory("heap");
		}
		
		// first find a free object-table entry
		OTObjectEntry e = findFreeOop(true);
		
		// allocate heap space
		int objHeapSpace = heapUsed;
		heapUsed += wordSize;
		heapRemaining -= wordSize;
		
		// attach heap space to the object and initialize the object's heap memory
		heapMemory[objHeapSpace] = (short)wordSize;
		heapMemory[objHeapSpace+1] = (short)classOop;
		e.allocate(objHeapSpace, pointerFields, oddLength); // (needs the length field to already have been set)
		initObjectFields(e);
		logf("**** allocated object 0x%04X from heap ( netWordSize = %d , class = 0x%04X (%s) )\n", e.objectPointer(), netWordSize, classOop, getClassName(classOop));
		
		// done
		return e.objectPointer();
	}
	
	private static void initObjectFields(OTObjectEntry e) {
		int wordSize = e.getWordLength();
		int refCount = (e.pointerFields()) ? wordSize : 0;
		short nil = (short)Well.known().NilPointer;
		for (int i = 0; i < refCount; i++) {
			e.storeWordAt(i, nil); // no need to do reference-counting on nil
		}
		for (int i = refCount; i < wordSize; i++) {
			e.storeWordAt(i, 0);
		}
	}
	
	private static OTObjectEntry findFreeOop(boolean allowCompaction) {
		// 1st try: if no free oop is found below otLimit, do a compaction and then retry
		// 2nd try: scan the whole objectTable after compaction
		int oop = lowestFreeOop;
		while (oop < objectTable.length) {
			if (allowCompaction && oop > otLimit) {
				gc("oop > otLimit during findFreeOop()");
				return findFreeOop(false);
			}
			OTObjectEntry e = (OTObjectEntry)objectTable[oop];
			if (e.freeEntry()) {
				lowestFreeOop = oop;
				return e;
			}
			oop += 2;
		}
		
		// last try: attempt a compaction before going into outOfMemory(objectTable)
		if (allowCompaction) {
			gc("no oop found during findFreeOop()");
			return findFreeOop(false);
		}
		throw MisuseHandler.outOfMemory("objectTable");
	}
	
	private static int gcCount = 0;
	private static Consumer<Integer> gcNotificationSink = null;
	
	public static void setGarbageCollectNotificationSink(Consumer<Integer> sink) {
		gcNotificationSink = sink;
	}
	
	// mark & sweep & compacting garbage collector
	// returns the last oop in use
	public static int gc(String reason) {
		gclogf("\n-- gc( run: %d ,  reason: %s )\n", gcCount,  reason);
		gcCount++;
		long startNano = System.nanoTime();
		
		// sanity check: verify that no reachable object has been released with reference-counting (log only!)
		traverseObjectsFromRoots((e,g) -> {
			if (e.count() < 1 || e.freeEntry()) {
				gclogf("-- gc() err: reachable free object: %s\n", e.toString());
			}
			return e.setUsed(g);
		});
		
		// drop all enqueued free objects: this becomes the unused heap space to be compacted away
		gclogf("-- gc(): dropping free-lists\n");
		for (Entry<Integer, Integer> oopList : freesize2oop.entrySet()) {
			int size = oopList.getKey();
			int count = 0;
			int victim = oopList.getValue();
			while (victim >= 0) {
				OTObjectEntry e = (OTObjectEntry)objectTable[victim];
				victim = e.dropFromFreeList();
				count++;
			}
			gclogf("-- gc(): free-list for size = %d => %d entries freed\n", size, count);
		}
		freesize2oop.clear();
		
		// object-GC: mark phase
		gclogf("-- gc(): mark&sweep garbage collection\n");
		int markerGeneration = traverseObjectsFromRoots( (e,g) -> e.setUsed(g) );
		
		// object-GC: sweep phase
		int freedCount = 0;
		for (int ptr = Well.known().NilPointer; ptr < objectTable.length; ptr += 2) {
			OTObjectEntry e = (OTObjectEntry)objectTable[ptr];
			if (e.freeEntry()) { continue; }
			if (!e.isUsed(markerGeneration)) {
				gcvlogf("-- gc()... freed: %s\n", e.toString());
				e.free();
				noteFreeOop(ptr);
				freedCount++;
			}
		}
		gclogf("-- gc(): freedCount: %d\n", freedCount);
		
		// heap compaction: objects to process for heap compaction
		int oop = Well.known().NilPointer;
		int oopLimit = objectTable.length; // ?? otLimit ??
		int usedOops = 1;
		OTObjectEntry lastUsedObject = null;
		
		// move heap words of active objects
		gclogf("-- gc(): compacting heap\n");
		int dest = 0; // next location in compacted heap
		while(oop < oopLimit) {
			// get the object and relevant data
			OTObjectEntry e = (OTObjectEntry)objectTable[oop];
			int currFrom = e.address();
			int currCount = e.getSize();
			e.relocatedInHeap(dest);
			usedOops++;
			lastUsedObject = e;
			
			// copy heap content of the object
			if ((dest + currCount) >= compactHeap.length) {
				throw MisuseHandler.outOfMemory("heap");
			}
			System.arraycopy(heapMemory, currFrom, compactHeap, dest, currCount);
			dest += currCount;
			
			// find next valid object-table entry
			oop += 2;
			while(oop < oopLimit && ((OTObjectEntry)objectTable[oop]).freeEntry()) {
				oop += 2;
			}
		}
		
		// recompute otLimit
		int maxOop = lastUsedObject.objectPointer();
		int nextOtLimitBoundary = Math.min(objectTable.length, (((maxOop + (OTLIMIT_LEAPS * 2) - 1) / OTLIMIT_LEAPS)) * OTLIMIT_LEAPS);
		otLimit = nextOtLimitBoundary;
		
		// set new heap memory markers
		heapUsed = dest;
		heapRemaining = compactHeap.length - heapUsed;
		heapLimit = Math.min(compactHeap.length, heapUsed + HEAP_LIMIT_TO_USED);
		
		// swap heap memories
		short[] tmp = heapMemory;
		heapMemory = compactHeap;
		compactHeap = tmp;
		
		// do some logging
		long stopNano = System.nanoTime();
		gclogf("-- gc(): runtime %d nanoSecs\n", stopNano - startNano);
		gclogf("-- gc(): oopsUsed = %d, heapUsed = %d words, heapRemaining = %d words, heapLimit = 0x%06X\n", usedOops, heapUsed, heapRemaining, heapLimit);
		gclogf("-- gc(): maxOop = 0x%04X , nextOtLimitBoundary = 0x%04X => distance: %d\n", maxOop, nextOtLimitBoundary, nextOtLimitBoundary - maxOop);
		
		// do notification
		if (gcNotificationSink != null) {
			gcNotificationSink.accept(gcCount);
		}
		
		return maxOop;
	}
	
	
	/* **
	 * ********* traversal of all object references from known root locations
	 * **/
	
	private static int lastGeneration = 3;
	private static int getGeneration() {
		lastGeneration = (lastGeneration + 5) & 0x00FFFFFF;
		return lastGeneration;
	}
	
	public static int traverseObjectsFromRoots(BiPredicate<OTEntry,Integer> marker) {
		// args of BiPredicate<OTEntry,Integer> marker:
		// - first  => OTEntry of the current referenced object
		// - second => current generation
		// result of the BiPredicate: traverse deeper?
		//  -> false if the object was already seen in the given generation => no need to traverse further
		//  -> true if the object was seen the *first* time in the given generation => also process objects referenced by the current object
		
		// get us a unique enough generation
		int generation = getGeneration();
		
		// traverse all objects reachable from the Smalltalk object: all classes, all processes (suspended state), all global variables
		traverseObjectTree(smalltalkObject, generation, marker);
		
		// traverse the currently running process
		// remark: the activeContext is invalid directly after loading the image,
		// but then: the restart context will already have been seen in above traversal of Smalltalk => Processor => ...
		int activeContextPointer = Interpreter.activeContext();
		if (activeContextPointer >= 0 && activeContextPointer < objectTable.length) {
			OTEntry activeContext = ot(activeContextPointer);
			traverseObjectTree(activeContext, generation, marker);
		}
		
		// done
		return generation;
	}
	
	private static void traverseObjectTree(OTEntry obj, int generation, BiPredicate<OTEntry,Integer> marker) {
		if (obj.freeEntry()) {
			logf("** error in traverseObjectTree(): object to mark is a freeEntry: %s\n", obj.toString());
			marker.test(obj, generation);
			return;
		}
		if (obj.isObject() && marker.test(obj, generation)) {
			int classOop = obj.getClassOOP();
			int childrenCount = obj.pointerFields() ? obj.getWordLength() : 0;
			if (classOop == Well.known().ClassCompiledMethodPointer) {
				int headerOop = obj.fetchWordAt(Well.known().HeaderIndex);
				int header = integerValueOf(headerOop);
				int literalCount = header & 0x003F;
				childrenCount = literalCount + Well.known().LiteralStartIndex;
			}
			traverseObjectTree(ot(classOop), generation, marker);
			for (int i = 0; i < childrenCount; i++) {
				OTEntry e = ot(obj.fetchPointerAt(i));
				if (e.freeEntry()) {
					logf("# object 0x%04X (class 0x%04X) references free entry 0x%04X at child-idx %d\n", obj.objectPointer(), classOop, e.objectPointer(), i); 
				} else {
					traverseObjectTree(e, generation, marker);
				}
			}
		}
	}
	
	/*
	 * general utilities
	 */
	
	public static short[] getHeapMem() {
		return heapMemory;
	}

	private static Map<Integer,String> symbolCache = new HashMap<>();
	private static StringBuilder tempSymSb = new StringBuilder();
	
	private static String getStringValue(OTEntry str) {
		tempSymSb.setLength(0);
		for (int i = 0; i < str.getByteLength(); i++) {
			tempSymSb.append((char)str.fetchByteAt(i));
		}
		return tempSymSb.toString();
	}
	
	public static String getSymbolText(int symbolOop) {
		if (symbolCache.containsKey(symbolOop)) {
			return symbolCache.get(symbolOop);
		}
		OTEntry symbol = Memory.ot(symbolOop);
		if (symbol.getClassOOP() != Well.known().ClassSymbolPointer) {
			return "";
		}
		String symbolText = getStringValue(symbol);
		symbolCache.put(symbolOop, symbolText);
		return symbolText;
	}
	
	public static String getClassName(int classPointer) {
		int symbolPointer = Memory.fetchPointer(6, classPointer);
		String className = getSymbolText(symbolPointer);
		return className;
	}
	
	public static String getClassNameOf(int objectPointer) {
		return getClassName(Memory.ot(objectPointer).getClassOOP());
	}
}
