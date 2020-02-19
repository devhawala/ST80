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

/**
 * Singleton with constants for specific Smalltalk class or object pointers, indices
 * for accessing instance variables of specific Smalltalk class instances or the like.
 * <p>
 * The provided values are not pure constants in the sense that they were defined
 * externally, like the "guaranteed object-pointers" defined by Bluebook. A number
 * of the values are searched for in the snapshot after loading it: these values
 * may theoretically vary for different versions of the distribution image delivered
 * by Xerox.
 * <br/>
 * Moreover, this singleton handles the situations where a deviating identification
 * scheme for SmallInteger OOPs is used in an image: the Bluebook defines SmallInteger OOPs
 * as having the least significant bit 1, but the DV6 image snapshot for Daybreak/Pilot
 * uses the so-called stretch mode, where SmallInteger OOPs have the least 2 significant
 * bits 0 and allowing for 48k objects instead of 32k by the Bluebook.
 * </p>
 * <p>
 * The idiom for getting one of the constants is: {@code Well.known().}<i>name</i>.
 * </p>
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Well {
	
	/*
	 * well-known / guaranteed object-pointers defined by Bluebook
	 */
	
	// "SmallIntegers"
	public final int MinusOnePointer;
	public final int ZeroPointer;
	public final int OnePointer;
	public final int TwoPointer;
		
	// "UndefinedObject and Booleans"
	public final int NilPointer;
	public final int FalsePointer;
	public final int TruePointer;
	
	// "Root"
	public final int SchedulerAssociationPointer;

	// "Classes"
	public final int ClassStringPointer;
	public final int ClassArrayPointer;
	public final int ClassMethodContextPointer;
	public final int ClassBlockContextPointer;
	public final int ClassPointPointer;
	public final int ClassLargePositivelntegerPointer;
	public final int ClassMessagePointer;
	public final int ClassCharacterPointer;
	
	// "Selectors"
	public final int DoesNotUnderstandSelector;
	public final int CannotReturnSelector;
	public final int MustBeBooleanSelector;

	// "Tables"
	public final int SpecialSelectorsPointer; // 32 x {selector,arg-count} pairs for 'send-special-selector' instructions
	public final int CharacterTablePointer;   // 256 x character (class Character instances)
	
	/*
	 * object-pointers derived from the virtual image (not defined by Bluebook as "guaranteed")
	 */
	
	// class pointers
	public final int ClassSymbolPointer;
	public final int ClassMetaclassPointer;
	public final int ClassCompiledMethodPointer;
	public final int ClassSmallIntegerPointer;
	public final int ClassLargeNegativeIntegerPointer;
	public final int ClassFloatPointer;
	public final int ClassSemaphorePointer;
	public final int ClassAssociationPointer;
	
	// message pointers
	public final int YieldSelector;
	
	
	/*
	 * well-known indices for accessing several object-instance data as defined by Bluebook
	 */
	
	// "Class CompiledMethod"
	public final int HeaderIndex = 0;
	public final int LiteralStartIndex = 1;
	
	// "Class MethodContext"
	public final int SenderIndex = 0;
	public final int InstructionPointerIndex = 1;
	public final int StackPointerIndex = 2;
	public final int MethodIndex = 3;
	public final int ReceiverIndex = 5;
	public final int TempFrameStart = 6;
	
	// "Class BlockContext"
	public final int CallerIndex = 0;
	public final int BlockArgumentCountIndex = 3;
	public final int InitialIPIndex = 4;
	public final int HomeIndex = 5;
	
	// "Class Class"
	public final int SuperclassIndex = 0;
	public final int MessageDictionaryIndex = 1;
	public final int InstanceSpecificationIndex = 2;
	
	// "Fields of a message dictionary"
	public final int MethodArrayIndex = 1;
	public final int SelectorStart = 2;
	
	// "Message indices"
	public final int MessageSelectorIndex = 0;
	public final int MessageArgumentsIndex = 1;
	public final int MessageSize = 2;
		
	/*
	 * well-known field-indices derived from the Smalltalk-80 sources
	 */
	
	// class Association
	public final int KeyIndex = 0;
	public final int ValueIndex = 1;
	
	// class LinkedList
	public final int FirstLinkIndex = 0;
	public final int LastLinkIndex = 1;
	
	// class Link
	public final int NextLinkIndex = 0;
	
	// class Semaphore (subclasses: LinkedList)
	public final int ExcessSignalsIndex = 2;
	
	// class ProcessorScheduler
	public final int QuiescentProcessListsIndex = 0; // Array of LinkedList (len = 8, index is priority)
	public final int ActiveProcessIndex = 1;         // Process: currently executing process
	
	// class Process (subclass of: Link)
	public final int ProcessNext = 0;             // next process in linked list (inherited from class Link)
	public final int ProcessSuspendedContext = 1; // <Context> activeContext at time of process suspension
	public final int ProcessPriority = 2;         // <Integer> partial indication of relative scheduling
	public final int ProcessMyList = 3;           // <LinkedList> on which the process is suspended
	
	// class Point
	public final int ClassPointSize = 2;
	public final int XIndex = 0;
	public final int YIndex = 1;
	
	// class Character
	public final int CharacterValueIndex = 0;
	
	// class Stream
	public final int StreamArrayIndex = 0;
	public final int StreamIndexIndex = 1;
	public final int StreamReadLimitIndex = 2;
	public final int StreamWriteLimitIndex = 3;
	
	// class Form
	public final int FormBitsIndex = 0;   // WordArray
	public final int FormWidthIndex = 1;  // Integer
	public final int FormHeightIndex = 2; // Integer
	public final int FormOffsetIndex = 3; // Point (seems to be the shifting offset to position the bitmap over the hotspot, e.g. -8@-8 => hotspot is in the center of the bitmap)
	
	
	/*
	 *************************************************************************** implementation
	 */
	
	
	/*
	 * non-constant well known values
	 */
	
	private static boolean isStretch;
	private static int sClassSymbolPointer = -1;
	private static int sClassMetaclassPointer;
	private static int sClassCompiledMethodPointer;
	private static int sClassSmallIntegerPointer;
	private static int sClassLargeNegativeIntegerPointer;
	private static int sClassFloatPointer;
	private static int sClassSempahorePointer;
	private static int sClassAssociationPointer;
	private static int sYieldSelector;
	
	/* package-access */
	static void initialize(
			boolean stretch,
			int oopClassSymbolPointer,
			int oopClassMetaclassPointer,
			int oopClassCompiledMethodPointer,
			int oopClassSmallIntegerPointer,
			int oopClassLargeNegativeIntegerPointer,
			int oopClassFloatPointer,
			int oopClassSemaphorePointer,
			int oopClassAssociationPointer,
			int oopYieldSelector) {
		isStretch = stretch;
		sClassSymbolPointer = oopClassSymbolPointer;
		sClassMetaclassPointer = oopClassMetaclassPointer;
		sClassCompiledMethodPointer = oopClassCompiledMethodPointer;
		sClassSmallIntegerPointer = oopClassSmallIntegerPointer;
		sClassLargeNegativeIntegerPointer = oopClassLargeNegativeIntegerPointer;
		sClassFloatPointer = oopClassFloatPointer;
		sClassSempahorePointer = oopClassSemaphorePointer;
		sClassAssociationPointer = oopClassAssociationPointer;
		sYieldSelector = oopYieldSelector;
	}
	
	private static Well singleton;
	
	public static Well known() {
		if (singleton == null) {
			if (sClassSymbolPointer < 0) {
				throw new IllegalStateException("Well-known values not properly initialized");
			}
			singleton = new Well();
		}
		return singleton;
	}
	
	private Well() {
		if (isStretch) {
			this.MinusOnePointer = 0xFFFC;
			this.ZeroPointer = 0;
			this.OnePointer = 4;
			this.TwoPointer = 8;
		} else {
			this.MinusOnePointer = 0xFFFF;
			this.ZeroPointer = 1;
			this.OnePointer = 3;
			this.TwoPointer = 5;
		}
		
		int oopMarkerBit = (isStretch) ? 1 : 0;
		this.NilPointer = 0x0002 + oopMarkerBit;
		this.FalsePointer = 0x0004 + oopMarkerBit;
		this.TruePointer = 0x0006 + oopMarkerBit;
		this.SchedulerAssociationPointer = 0x0008 + oopMarkerBit;

		this.ClassStringPointer = 0x000E + oopMarkerBit;
		this.ClassArrayPointer = 0x0010 + oopMarkerBit;

		this.ClassMethodContextPointer = 0x0016 + oopMarkerBit;
		this.ClassBlockContextPointer = 0x0018 + oopMarkerBit;
		this.ClassPointPointer = 0x001A + oopMarkerBit;
		this.ClassLargePositivelntegerPointer = 0x001C + oopMarkerBit;

		this.ClassMessagePointer = 0x0020 + oopMarkerBit;

		this.ClassCharacterPointer = 0x0028 + oopMarkerBit;
		this.DoesNotUnderstandSelector = 0x002A + oopMarkerBit;
		this.CannotReturnSelector = 0x002C + oopMarkerBit;
		this.MustBeBooleanSelector = 0x0034 + oopMarkerBit;

		this.SpecialSelectorsPointer = 0x0030 + oopMarkerBit;
		this.CharacterTablePointer = 0x0032 + oopMarkerBit;
		
		this.ClassSymbolPointer = sClassSymbolPointer;
		this.ClassMetaclassPointer = sClassMetaclassPointer;
		this.ClassCompiledMethodPointer = sClassCompiledMethodPointer;
		this.ClassSmallIntegerPointer = sClassSmallIntegerPointer;
		this.ClassLargeNegativeIntegerPointer = sClassLargeNegativeIntegerPointer;
		this.ClassFloatPointer = sClassFloatPointer;
		this.ClassSemaphorePointer = sClassSempahorePointer;
		this.ClassAssociationPointer = sClassAssociationPointer;
		
		this.YieldSelector = sYieldSelector;
	}

}
