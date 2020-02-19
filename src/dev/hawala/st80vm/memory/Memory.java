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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * <li>the object management has 2 object tables</li>
 * <li>these object tables reside outside the heap, leaving more heap space to Smalltalk</li>
 * <li>the Bluebook object table holds Java object instances describing the Smalltalk object at their
 * OOP in the table; this allows to have more runtime state about objects than allowed by the
 * 2 words in the original Smalltalk-80 object table; this table is indexed with the object-pointer
 * resp. OOP of an object</li>
 * <li>the linear object table holds the same Java object instances for the Smalltalk objects,  but
 * addressed by their "linear object pointer", where 'nil' is object 1, 'false' is object 2, 'true'
 * is object '2' etc. 
 * <br/>This second table allows to address objects in a linear fashion (first all "real"
 * objects, then all SmallIntegers) independently of how all items are interlaced in the Bluebook
 * object table (alternating by the Bluebook (smallint-1, object-1, smallint-2, object-2, ...), but more
 * complicated  for the Stretch model (smallint-1, object-1, object-48k, object-2, smallint-2, ... ))</li>
 * <li>the object tables are 65536 entries long and hold the representations of all objects, including
 * SmallInteger OOPs, allowing for an uniform addressing and handling scheme of all objects without caring
 * if an OOP is the SmallInteger exception as Smalltalk-80 specified it (no heap space, no object table entry).</li>
 * <li>heap compaction can simply use a second memory space for copying the instance memory of objects (i.e.
 * using 2x 1 MWords for heap space, in total 4 MByte)</li>
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
	
	// flag indicating if the image is Bluebook compatible (32k object, 32k smallints)
	// or it if uses the 1186 DV6 "Stretch" model (48k objects, 16k smallints, with specific OOP scheme)
	private static boolean isStretch = false;
	
	public static boolean isStretch() { return isStretch; }
	
	// the ObjectTable of the Smalltalk-80 machine (16 bit OOPs) as specified by the Bluebook
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
	//
	// variables used for addressing in this table are named like "objectPointer" or "oop" (for object-oriented pointer)
	private static OTEntry[] objectTable;
	
	// linear object table holding the same objects as 'objectTable' above, but arranged in the linear (natural)
	// fashion instead of indexing by the objectPointer/OOP. This 2nd table simplifies enumerating through all
	// objects, as
	// - addressing is independent of the objectPointer scheme used (Bluebook or Stretch)
	// - objects and smallints are clearly separated instead of interlaced
	// 
	// variables used for addressing in this table are named like "lop" (for linear object pointer)
	private static OTEntry[] linearObjectTable;
	
	// total number of free objects in the objectTable, no matter if truly free or in one of the free-lists 
	private static int freeObjectCount = 0;
	
	// current usage limit of linear-object-pointers
	// (automatically incremented when all object-pointers below the limit are exhausted)
	private static int lotLimit;
	
	// boundary interval for the high-mark of LOPs to be considered for allocation
	// (transgressing the high-mark will cause a compaction and raising the high-mark by this value
	// if no free LOPs are available below the high-mark after compaction)
	private static final int LOTLIMIT_LEAPS = 256;
	
	// lowest last known free OOP (hint for starting search the next free OOP)
	private static int lowestFreeLop = 1;
	
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
		
		objectTable = new OTEntry[65536];
		linearObjectTable = new OTEntry[65536];
		
		// create SmallInteger entries
		int zero = (isStretch) ? 0 : 1; // Bluebook: SmallIntegers have lowest bit = 1 , Stretch: SmallIntegers have 2 lowest bits == 0;
		int step = (isStretch) ? 4 : 2; // increment for next SmallInteger oop
		int shift = (isStretch) ? 2 : 1; // shift right amount to get the int value from an oop
		int linBase = (isStretch) ?  49152 : 32768; // positive small-ints start at linear 48k for stretch and 32k for Bluebook
		for (int oop = zero; oop < objectTable.length; oop += step) {
			short intValue = (short)(((short)oop) >> shift);
			int lop = (intValue >= 0) ? (linBase + intValue) : (65536 + intValue);
			OTEntry e = new OTIntEntry(intValue, oop, lop);
			objectTable[oop] = e;
			linearObjectTable[lop] = e;
		}
		
		// create first 32768 Object entries (common to both variants, except for where it starts)
		int firstOop = (isStretch) ? 1 : 0; // the first object (the one before Nil) is unused, but should be there...
		linearObjectTable[0] = new OTObjectEntry(firstOop, 0);
		objectTable[firstOop] = linearObjectTable[0];
		int lop = 1;
		for (int oop = firstOop + 2; oop < objectTable.length; oop += 2) {
			OTEntry e = new OTObjectEntry(oop, lop);
			objectTable[oop] = e;
			linearObjectTable[lop++] = e;
		}
		
		// if stretch: fill the gaps left above with 16k more objects
		// starts at oop = 2 as: 0 is zero, 1 is the "unused" object, 3 is nil, 4 is one etc.
		if (isStretch) {
			for (int oop = 2; oop < objectTable.length; oop += 4) {
				OTEntry e = new OTObjectEntry(oop, lop);
				objectTable[oop] = e;
				linearObjectTable[lop++] = e;
			}
		}
		
		// set the limit for GC (will surely be reset when reading the image
		lotLimit = LOTLIMIT_LEAPS; // smallest limit, giving a enough entries before the limit must be raised
		
		// paranoia checks:
		// - check there is no null entry in either table
		// - Bluebook: even oop: object, odd oop: small-int, linear < 32k: object, linear >= 32k: positive int, linear >= 48k: negative int
		// - stretch: oop mod 4 == 0 => int, rest object, linear >= 48k: positive int, linear >= 56k: negative int
		boolean hadNull = false;
		for (int l = 0; l < linearObjectTable.length; l++) {
			if (linearObjectTable[l] == null) { hadNull = true; System.out.printf("init-err: linearObjectTable[%d] == null\n", l); }
		}
		for (int o = 0; o < objectTable.length; o++) {
			if (objectTable[o] == null) { hadNull = true; System.out.printf("init-err: objectTable[%d] == null\n", o); }
		}
		if (hadNull) { System.exit(-1); }
		if (isStretch) {
			for (int o = 0; o < objectTable.length; o++) {
				OTEntry e = objectTable[o];
				if ((o % 4) == 0 && !e.isSmallInt()) { System.out.printf("** init-stretch-err: oop %d is not small-integer\n", o); }
				if ((o % 4) != 0 && !e.isObject()) { System.out.printf("** init-stretch-err: oop %d is not object\n", o); }
			}
			for (int l = 0; l < linearObjectTable.length; l++) {
				OTEntry e = linearObjectTable[l];
				if (l < 49152 && !e.isObject()) { System.out.printf("** init-stretch-err: linear %d is not object\n", l); }
				if (l >= 49152) {
					if (!e.isSmallInt()) {
						System.out.printf("** init-stretch-err: linear %d is not small-integer\n", l);
					} else {
						OTIntEntry i = (OTIntEntry)e;
						if (l < 57344 && i.intValue() < 0) { System.out.printf("** init-stretch-err: linear %d is not positive\n", l); }
						if (l >= 57344 && i.intValue() >= 0) { System.out.printf("** init-stretch-err: linear %d is not negative\n", l); }
					}
				}
			}
		} else {
			for (int o = 0; o < objectTable.length; o++) {
				OTEntry e = objectTable[o];
				if ((o & 1) == 0 && !e.isObject()) { System.out.printf("** init-bluebook-err: oop %d is not object\n", o); }
				if ((o & 1) == 1 && !e.isSmallInt()) { System.out.printf("** init-bluebook-err: oop %d is not small-integer\n", o); }
			}
			for (int l = 0; l < linearObjectTable.length; l++) {
				OTEntry e = linearObjectTable[l];
				if (l < 32768 && !e.isObject()) { System.out.printf("** init-bluebook-err: linear %d is not object\n", l); }
				if (l >= 32768) {
					if (!e.isSmallInt()) {
						System.out.printf("** init-bluebook-err: linear %d is not small-integer\n", l);
					} else {
						OTIntEntry i = (OTIntEntry)e;
						if (l < 49152 && i.intValue() < 0) { System.out.printf("** init-bluebook-err: linear %d is not positive\n", l); }
						if (l >= 49152 && i.intValue() >= 0) { System.out.printf("** init-bluebook-err: linear %d is not negative\n", l); }
					}
				}
			}
		}
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
	
	public static void loadVirtualImage(String filename) throws IOException {
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
			int usedWordsInObjectTable = ((buffer[2] & 0xFFFF) << 16) | buffer[3] & 0xFFFF;
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
			
			// check somehow if it is a stretch (DV6) image ...
			isStretch = (buffer[4] != 0); // this is a significant difference between V2 and DV6, but does this mean: stretch?
			
			// create the empty object table
			initializeObjectTable();
			
			// read object table
			int entryLop = 0;
			int loadedEntries = 0;
			int freeEntries = 0;
			int firstFreeEntry = -1;
			int lastW1 = -1;
			int currSegment = 0;
			int lastLoadedLop = -1;
			boolean done = false;
			while(!done) {
				len = loadPage(bis, buffer, 0);
				if ((len & 1) != 0) { throw new RuntimeException("Invalid VirtualImage (odd number of words when reading ObjectTable page"); }
				int pos = 0;
				while(pos < len) {
					int currW0 = buffer[pos] & 0xFFFF;
					int currW1 = buffer[pos+1] & 0xFFFF;
					if (currW0 != 0x0020) {
						if (lastW1 > currW1) {
							currSegment = (currSegment + 1) & 0x000F;
//							logf("... apparent segment transition to segment %2d at lop 0x%04X\n", currSegment, entryLop);
						}
						if ((currW0 & 0x000F) == 0) {
							currW0 |= currSegment;
						}
						lastW1 = currW1;
					}
					linearObjectTable[entryLop].fromWords((short)currW0, (short)currW1);
					if (linearObjectTable[entryLop].freeEntry()) {
						if (entryLop > 0) { // skip 1st entry (reserved))
							freeEntries++;
							if (firstFreeEntry < 0) {
								firstFreeEntry = entryLop;
							}
						}
					} else {
						lastLoadedLop = entryLop;
					}
					entryLop++;
					pos += 2;
					loadedEntries++;
				}
				done = (len != 256) || (loadedEntries >= usedWordsInObjectTable);
			}
			
			// compute the upper limit of objects used so far, rounded up to 128 objects
			int tmpLotLimit = lastLoadedLop & 0xFF00;
			lotLimit = Math.min(65536, tmpLotLimit + 0x0100);
			
			// get the number of free objects in the objectTable
			freeObjectCount = freeEntries + ((linearObjectTable.length / 2) - loadedEntries);
			
			/*
			 * derive information (classes, objects) not fixed ("guaranteed") in the Bluebook
			 */
			
			// determine important class OOPs, using the well-known OOP of Nil
			OTEntry nil = linearObjectTable[1]; // first object in linearObjectTable is 'unused', second is: NilPointer
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
			int metaclassLop = nil.linearObjectPointer();
			while ( ( classCompiledMethodPointer == -1 
					  || classSmallIntegerPointer == -1
					  || classLargeNegativeIntegerPointer == -1
					  || classProcessorSchedulerPointer == -1
					  || classProcessPointer == -1
					  || classSystemDictionaryPointer == -1
					  || classFloatPointer == -1
					  || classSemaphorePointer == -1
					  || yieldSelectorPointer == -1)
					&& metaclassLop < objectTable.length) {
				OTEntry otMetaclass = linearObjectTable[metaclassLop];
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
				} else if (yieldSelectorPointer == -1 && isSymbol(metaclassLop, yieldSymbol)) {
						yieldSelectorPointer = metaclassLop;
					}
				metaclassLop++;
			}
			
			// initialize well-known object-pointers etc.
			Well.initialize(
					isStretch,
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
				lowestFreeLop = firstFreeEntry;
			} else {
				lowestFreeLop = Well.known().NilPointer;
			}
			gc("initial");
			
			// finally give some infos
			if (Config.LOG_MEM_OPS) {
				System.out.printf("Loaded image:\n");
				System.out.printf(" - heapSizeLoadedLimit ............ = 0x%06X\n", heapSizeLoadedLimit);
				System.out.printf(" - usedWordsInObjectTable ......... = %d\n", usedWordsInObjectTable);
				System.out.printf(" - loadedEntries .................. = %d\n", loadedEntries);
				System.out.printf(" - lastLoadedLop .................. = %d\n", lastLoadedLop);
				System.out.printf(" - freeEntries .................... = %d (below loadedEntries)\n", freeEntries);
				System.out.printf(" - freeEntries .................... = %d (total)\n", freeEntries + (32768 - loadedEntries));
				System.out.printf(" - lotLimit ....................... = %d\n", lotLimit);
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
			int lastLop = gc("snapshot");
			
			// create & write metadata page
			int otWordCount = (lastLop & 0xFFFE) + 2;
			resetBuffer();
			buffer[0] = (short)((heapUsed >> 16) & 0xFFFF);
			buffer[1] = (short)(heapUsed & 0xFFFF);
			buffer[2] = (short)((otWordCount >> 16) & 0xFFFF);
			buffer[3] = (short)(otWordCount & 0xFFFF);
			if (isStretch) { buffer[4] = 1; } // in symmetry to loadVirtualImage, hoping this is the right place (it is for us now!)
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
			for (int lop = 0; lop <= lastLop; lop++) {
				OTEntry oe = linearObjectTable[lop];
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
				String oldSnapshotFilename = fn + ";" + sdf.format(snapshotFile.lastModified());
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
		for (int lop = 0; lop < linearObjectTable.length; lop++) {
			OTEntry e = linearObjectTable[lop];
			if (e.isSmallInt()) {
				break; // all true objects were seen
			}
			if (!e.freeEntry() && e.count() != 0 && e.getClassOOP() == classPointer) {
				return e.objectPointer();
			}
		}
		return Well.known().NilPointer;
	}
	
	public static int instanceAfter(int objectPointer) {
		OTEntry obj = objectTable[objectPointer];
		int classPointer = obj.getClassOOP();
		for (int lop = obj.linearObjectPointer() + 1; lop < linearObjectTable.length; lop++) {
			OTEntry e = linearObjectTable[lop];
			if (e.isSmallInt()) {
				break; // all true objects were seen
			}
			if (!e.freeEntry() && e.count() != 0 && e.getClassOOP() == classPointer) {
				return e.objectPointer();
			}
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
		return ot(objectPointer).intValue();
	}
	
	public static int integerObjectOf(int value) {
		if (isStretch) {
			if (value >= -8192 && value <= 8191) {
				return (value & 0x3FFF) << 2;
			}
			throw new RuntimeException("integerObjectOf(" + value + ") => value of of range for strech virtual image");
		}
		return ((value & 0x7FFF) << 1) | 1;
	}
	
	public static boolean isIntegerObject(int objectPointer) {
		return ot(objectPointer).isSmallInt();
	}
	
	public static boolean isIntegerValue(int value) {
		if (isStretch) {
			return (value >= -8192 && value <= 8191);
		}
		return (value >= -16384 && value <= 16383);
	}
	
	/* **
	 * ********* additional interface methods (not in Bluebook ObjectMemory interface)
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
	
	// attention: this returns a true (Java) integer!
	public static int objectPointerAsOop(int pointer) {
		if (isStretch) {
			return ot(pointer).linearObjectPointer();
		} else {
			return pointer >> 1;
		}
// ** this returns a SmallInteger-pointer: (works only for Bluebook)
//		int oopValue = pointer & 0xFFFE;
//		if (oopMarkerBit == 0) { oopValue |= 1; }
//		return oopValue;
	}
	
	// attention: this expects a true (Java) integer!
	public static int oopAsObjectPointer(int oopValue) {
		if (oopValue < 0 || oopValue >= linearObjectTable.length) {
			throw new RuntimeException("oopAsObjectPointer(" + oopValue + ") => oopValue outof range");
		}
		if (isStretch) {
			return linearObjectTable[oopValue].objectPointer(); // symmetric to objectPointerAsOop() !!!
		} else {
			return oopValue << 1;
		}
// ** this expects an SmallInteger-pointer:
//		return (oopValue & 0xFFFE) | oopMarkerBit;
	}
	
	public static boolean hasObject(int oop) {
		OTEntry e = ot(oop);
		return e.isObject() && !e.freeEntry();
	}
	
	/* **
	 * ********* specifics for DV6
	 * **/
	
	public static int getNextOwnerOf(int ofPointer, int afterPointer) {
		OTEntry after = ot(afterPointer);
		for (int lop = after.linearObjectPointer() + 1; lop < linearObjectTable.length; lop++) {
			// get the current candidate object
			OTEntry cand = linearObjectTable[lop];
			if (!cand.isObject()) {
				break; // no more true objects
			}
			
			// find the number of object references in the object
			int refsCount = 0;
			if (cand.pointerFields()) {
				refsCount = cand.getWordLength();
			} else if (cand.getClassOOP() == Well.known().ClassCompiledMethodPointer) {
				int headerOop = cand.fetchWordAt(Well.known().HeaderIndex);
				int header = integerValueOf(headerOop);
				int literalCount = header & 0x003F; // lower 6 bits
				refsCount = literalCount + Well.known().LiteralStartIndex;
			}
			
			// check if the candidate references 'ofPointer'
			for (int i = 0; i < refsCount; i++) {
				if (cand.fetchPointerAt(i) == ofPointer) {
					return cand.objectPointer();
				}
			}
		}
		
		// no object pointing at 'ofPointer' found
		return Well.known().NilPointer;
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
			noteFreeLop(e.linearObjectPointer());
		}
		 
		// manage global counter
		freeObjectCount++;
	}
	
	/* package-access */
	static void noteFreeLop(int lop) {
		if (lop < lowestFreeLop) {
			lowestFreeLop = lop;
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
			logf("\n**** allocated object 0x%04X from freelist ( netWordSize = %d , class = 0x%04X (%s) )", e.objectPointer(), netWordSize, classOop, getClassName(classOop));
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
		logf("\n**** allocated object 0x%04X from heap ( netWordSize = %d , class = 0x%04X (%s) )", e.objectPointer(), netWordSize, classOop, getClassName(classOop));
		
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
		// 1st try: if no free object is found below lotLimit, do a compaction and then retry
		// 2nd try: scan the whole linearObjectTable after compaction
		int lop = lowestFreeLop;
		while (lop < linearObjectTable.length) {
			if (allowCompaction && lop > lotLimit) {
				gc("lop > lotLimit during findFreeOop()");
				return findFreeOop(false);
			}
			OTObjectEntry e = (OTObjectEntry)linearObjectTable[lop];
			if (e.freeEntry()) {
				lowestFreeLop = lop;
				return e;
			}
			lop++;
		}
		
		// last try: attempt a garbage collection before going into outOfMemory(objectTable)
		if (allowCompaction) {
			gc("no free object found during findFreeOop()");
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
	// returns the last lop in use
	public static int gc(String reason) {
		return gc(reason, -1, -1, -1);
	}
	
	// mark & sweep & compacting garbage collector with special DV6 extension for purging identity dictionaries
	// returns the last lop in use
	public static int gc(String reason, int dictClassPointer, int dictArrayPointer, int replacementKeyPointer) {
		gclogf("\n-- gc( run: %d ,  reason: %s )\n", gcCount,  reason);
		gcCount++;
		long startNano = System.nanoTime();
		
//		// sanity check: verify that no reachable object has been released with reference-counting (log only!)
//		traverseObjectsFromRoots((e,g) -> {
//			if (e.count() < 1 || e.freeEntry()) {
//				gclogf("-- gc() err: reachable free object: %s\n", e.toString());
//			}
//			return e.setUsed(g);
//		});
		
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
			gcvlogf("-- gc(): free-list for size = %d => %d entries freed\n", size, count);
		}
		freesize2oop.clear();
		
		// object-GC: special DV6 extension - prepare identity dictionaries
		// (the idea is to remove the non-empty keys from the dictionaries so they
		// won't be seen by the gc mark-phase, and collect the restore-lamda
		// procedures that will either free the key and value or restore the key,
		// depending on the outcome of the park phase (i.e. if they are also used otherwise))
		List<Consumer<Integer>> restorers = new ArrayList<>(); // Consumer argument is: gc generation for existence
		if (dictClassPointer > 0 && dictArrayPointer > 0 && replacementKeyPointer > 0
				&& "IdentityDictionary".equals(getClassName(dictClassPointer))) {
			OTEntry dictArray = Memory.ot(dictArrayPointer);
			for (int dictArrayIdx = 0; dictArrayIdx < dictArray.getWordLength(); dictArrayIdx++) {
				OTEntry dict = ot(dictArray.fetchPointerAt(dictArrayIdx));
				if (dict.getClassOOP() != dictClassPointer) {
					continue;
				}
				OTEntry values = ot(dict.fetchPointerAt(1));
				for (int dictIdx = 2; dictIdx < dict.getWordLength(); dictIdx++) {
					// skip unused key entries
					int keyPointer = dict.fetchWordAt(dictIdx);
					if (keyPointer == Well.known().NilPointer || keyPointer == replacementKeyPointer) {
						continue;
					}
					
					// create restorer/dropper lambda
					int dictKeyPos = dictIdx;
					OTObjectEntry key = (OTObjectEntry)ot(keyPointer);
					String keyString = key.toString();
					OTObjectEntry value = (OTObjectEntry)ot(values.fetchWordAt(dictIdx - 2));
					String valueString = value.toString();
					restorers.add( g -> {
						if (key.isUsed(g)) {
							// keys also used elsewhere
							System.out.printf("== restoring key: %s\n", keyString);
							dict.storeWordAt(dictKeyPos, keyPointer);
						} else {
							// key only used in this dictionary => free
							if (!key.freeEntry()) {
								System.out.printf("== freeing key: %s\n", keyString);
								key.free();
								noteFreeLop(key.linearObjectPointer());
							} else {
								System.out.printf("== already freed key: %s\n", keyString);
							}
							if (value.count() == 1) {
								System.out.printf("==   -> freeing value: %s\n", valueString);
								values.storeWordAt(dictKeyPos - 2, Well.known().NilPointer);
								value.free();
								noteFreeLop(value.linearObjectPointer());
							} else {
								System.out.printf("==   -> removed value from dictionary: %s\n", valueString);
								values.storePointerAt(dictKeyPos - 2, Well.known().NilPointer);
							}
						}
					});
					System.out.printf("== removing key: %s\n", keyString);
					System.out.printf("==   -> value: %s\n", valueString);
					dict.storeWordAt(dictKeyPos, replacementKeyPointer);
				}
			}
		}
		
		// object-GC: mark phase
		gclogf("-- gc(): mark&sweep garbage collection\n");
		int markerGeneration = traverseObjectsFromRoots( (e,g) -> e.setUsed(g) );
		
		// object-GC: sweep phase
		int lopLimit = linearObjectTable.length;
		int freedCount = 0;
		freeObjectCount = 0;
		for (int lop = 1 /* ~ nil */; lop < linearObjectTable.length; lop++) {
			OTEntry le = linearObjectTable[lop];
			if (le.isSmallInt()) {
				lopLimit = le.linearObjectPointer();
				break; // the linearObjectTable has all objects first, then all small-ints => stop at first small-int
			}
			if (le.freeEntry()) {
				freeObjectCount++;
				continue;
			}
			if (!le.isUsed(markerGeneration)) {
				gcvlogf("-- gc()... freed: %s\n", le.toString());
				((OTObjectEntry)le).free();
				noteFreeLop(le.linearObjectPointer());
				freedCount++;
				freeObjectCount++;
			}
		}
		
		// object-GC: special DV6 extension - restore pointers or replace obsolete keys
		for (Consumer<Integer> r : restorers) {
			r.accept(markerGeneration);
		}
		
		gclogf("-- gc(): freedCount: %d\n", freedCount);
		
		// heap compaction: objects to process for heap compaction
		int lop = 1; // nil
		int usedObjects = 1;
		OTObjectEntry lastUsedObject = null;
		
		// move heap words of active objects
		gclogf("-- gc(): compacting heap\n");
		int dest = 0; // next location in compacted heap
		while(lop < lopLimit) {
			// get the object and relevant data
			OTObjectEntry e = (OTObjectEntry)linearObjectTable[lop];
			int currFrom = e.address();
			int currCount = e.getSize();
			e.relocatedInHeap(dest);
			usedObjects++;
			lastUsedObject = e;
			
			// copy heap content of the object
			if ((dest + currCount) >= compactHeap.length) {
				throw MisuseHandler.outOfMemory("heap");
			}
			System.arraycopy(heapMemory, currFrom, compactHeap, dest, currCount);
			dest += currCount;
			
			// find next valid object-table entry
			lop++;;
			while(lop < lopLimit && linearObjectTable[lop].freeEntry()) {
				lop++;
			}
		}
		
		// recompute lotLimit
		int maxLop = lastUsedObject.linearObjectPointer();
		int nextLotLimitBoundary = Math.min(linearObjectTable.length, (((maxLop + (LOTLIMIT_LEAPS * 2) - 1) / LOTLIMIT_LEAPS)) * LOTLIMIT_LEAPS);
		lotLimit = nextLotLimitBoundary;
		
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
		gclogf("-- gc(): oopsUsed = %d, heapUsed = %d words, heapRemaining = %d words, heapLimit = 0x%06X\n", usedObjects, heapUsed, heapRemaining, heapLimit);
		gclogf("-- gc(): lopLimit = 0x%04X (%d), freeObjectCount = %d\n", lopLimit, lopLimit, freeObjectCount);
		gclogf("-- gc(): maxLop = 0x%04X , nextLotLimitBoundary = 0x%04X => distance: %d\n", maxLop, nextLotLimitBoundary, nextLotLimitBoundary - maxLop);
		
		// do notification
		if (gcNotificationSink != null) {
			gcNotificationSink.accept(gcCount);
		}
		
		return maxLop;
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
		OTEntry symbol = ot(symbolOop);
		if (symbol.getClassOOP() != Well.known().ClassSymbolPointer) {
			return "";
		}
		String symbolText = getStringValue(symbol);
		symbolCache.put(symbolOop, symbolText);
		return symbolText;
	}
	
	public static String getClassName(int classPointer) {
		if (fetchWordLengthOf(classPointer) < 7) { return "?"; }
		int symbolPointer = fetchPointer(6, classPointer);
		String className = getSymbolText(symbolPointer);
		return className;
	}
	
	public static String getClassNameOf(int objectPointer) {
		return getClassName(ot(objectPointer).getClassOOP());
	}
	
	public static String getStringValue(int stringPointer) {
		OTEntry string = ot(stringPointer);
		if (string.getClassOOP() != Well.known().ClassStringPointer) {
			System.out.printf("\n** getStringValue( 0x%04X ) : not a string returning empty string", stringPointer);
			return "";
		}
		return getStringValue(string);
	}
	
	public static int createStringObject(String value) {
		byte[] bytes = value.getBytes();
		int wordLen = (bytes.length + 1) / 2;
		boolean oddLen = ((bytes.length & 1) == 1);
		int stringPointer = allocateObject(wordLen, Well.known().ClassStringPointer, false, oddLen);
		OTEntry string = ot(stringPointer);
		for (int i = 0; i < bytes.length; i++) {
			string.storeByteAt(i, bytes[i]);
		}
		return stringPointer;
	}
	
}
