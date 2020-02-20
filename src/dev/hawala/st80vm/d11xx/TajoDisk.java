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


import static dev.hawala.st80vm.interpreter.Interpreter.positive16BitIntegerFor;
import static dev.hawala.st80vm.interpreter.Interpreter.positive16BitValueOf;
import static dev.hawala.st80vm.interpreter.Interpreter.positive32BitIntegerFor;
import static dev.hawala.st80vm.interpreter.Interpreter.positive32BitValueOf;
import static dev.hawala.st80vm.interpreter.InterpreterBase.argumentCount;
import static dev.hawala.st80vm.interpreter.InterpreterBase.popStack;
import static dev.hawala.st80vm.interpreter.InterpreterBase.primitiveFail;
import static dev.hawala.st80vm.interpreter.InterpreterBase.push;
import static dev.hawala.st80vm.interpreter.InterpreterBase.unPop;
import static dev.hawala.st80vm.memory.Memory.getStringValue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.hawala.st80vm.Config;
import dev.hawala.st80vm.d11xx.TajoFilesystem.TajoFile;
import dev.hawala.st80vm.interpreter.Interpreter;
import dev.hawala.st80vm.interpreter.Interpreter.Primitive;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.memory.OTEntry;
import dev.hawala.st80vm.memory.Well;
import dev.hawala.st80vm.primitives.iVmFilesHandler;

/**
 * Implementation of the file system related primitive 'pilotCall' for accessing
 * the OS infrastructure of the 1108/1186 Smalltalk-80 implementation (version DV6)
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class TajoDisk {
	
	private TajoDisk() { }
	
	private static void w(String name) { if (Config.LOG_PRIMITIVES) { System.out.printf("\n-> TajoFilesystem." + name); } }
	
	private static void logf(String pattern, Object... args) {
		if (Config.TRACE_TAJO_OPS) { System.out.printf(pattern, args); }
	}
	
	
	public static final Primitive primitivePilotCall = () -> { w("pilotCall");
		int[] args = { // primitive has max. 4 args, keep some place for more
			Well.known().NilPointer,
			Well.known().NilPointer,
			Well.known().NilPointer,
			Well.known().NilPointer,
			Well.known().NilPointer,
			Well.known().NilPointer	
		};
		int operation = positive16BitValueOf(Interpreter.popStack());
		int argCount = argumentCount() - 1; // operation was popped separately
		for (int i = argCount - 1; i >= 0; i--) {
			args[i] = popStack();
		}
		int self = popStack();
		
		switch(operation) {
		case 1 : return createFile(self, args[0], args[1]);
		case 2 : return acquireFile(self, args[0], args[1]);
		case 3 : return doesFileExist(self, args[0]);
		case 4 : return rename(self, args[0], args[1]);
		case 5 : return remove(self, args[0]);
		case 6 : return freePages(self);
		case 7 : return setSnapshotFile(self, args[0]);
		case 8 : return getDirNames(self);
		case 9 : return createDirectory(self, args[0]);
		case 10: return deleteDirectory(self, args[0]);
		case 11: return setEnumerationPattern(self, args[0]);
		case 12: return getNextName(self);
		case 13: return close(self, args[0]);
		case 14: return setAccessOf(self, args[0], args[1]);
		case 15: return fileSize(self, args[0]);
		case 16: return read(self, args[0], args[1], args[2]);
		case 17: return write(self, args[0], args[1], args[2]);
		// operation 18 (truncate) defined in ErrorMsgs, but operation never used in DV6 sources...?
		// operation 19 (getTimeOf) defined in ErrorMsgs, but operation never used in DV6 sources...?
		// operation 20 (setTime) defined in ErrorMsgs, but operation never used in DV6 sources...?
		case 21: return getSearchPath(self);
		case 22: return setSearchPath(self, args[0]);
		case 23: return getFileProperties(self, args[0]);
		case 24: return maxOpenFiles(self);
		case 25: return position(self, args[0], args[1]);
		case 26: return fullNameOf(self, args[0]);
		case 27: return swapName(self, args[0], args[1]);
		
		default:
			System.out.printf("\n ### unknown / unsupported pilotCall operation: %d", operation);
			return restoreAndFail();
		}
	};
	
	private static boolean restoreAndFail() {
		unPop(argumentCount() + 1); // arguments + self
		return primitiveFail();
	}
	
	// "Create a new file named fileName. Return a fileDescriptor."
	private static boolean createFile(int self, int filenamePointer, int accessPointer) {
		String filename = getStringValue(filenamePointer);
		int accessValue = positive16BitValueOf(accessPointer);
		
		logf("\n ==> createFile( '%s' , %d )", filename, accessValue);
		
		// create the file
		TajoFile tajoFile = TajoFilesystem.create(filename, false);
		if (tajoFile == null) {
			// creation failed...
			logf(" ... create failed");
			return restoreAndFail();
		}
		
		// access the file
		Access access = getAccess(accessValue);
		int fd = createFileDescriptor(tajoFile, access);
		if (fd < 0) {
			// no free file descriptor available...
			logf(" ... no free file descriptor available");
			return restoreAndFail();
		}
		
		// return the file descriptor
		int fdPointer = positive32BitIntegerFor(fd);
		if (Interpreter.success()) {
			push(fdPointer);
			return true;
		} else {
			// some other error...
			logf(" ... other error (success() != true)");
			return restoreAndFail();
		}
	}
	
	// "Acquire the file named fileName with the specified access. Return a fileDescriptor."
	private static boolean acquireFile(int self, int filenamePointer, int accessPointer) {
		String filename = getStringValue(filenamePointer);
		int accessValue = positive16BitValueOf(accessPointer);
		
		logf("\n ==> acquireFile( '%s' , %d )", filename, accessValue);
		
		// get the file
		TajoFile tajoFile = TajoFilesystem.locate(filename);
		if (tajoFile == null) {
			// file not found...
			logf(" ... file not found");
			return restoreAndFail();
		}
		
		// access the file
		Access access = getAccess(accessValue);
		int fd = createFileDescriptor(tajoFile, access);
		if (fd < 0) {
			// no free file descriptor available...
			logf(" ... no free file descriptor available");
			return restoreAndFail();
		}
		
		// return the file dewscriptor
		int fdPointer = positive32BitIntegerFor(fd);
		if (Interpreter.success()) {
			push(fdPointer);
			return true;
		} else {
			// other error...
			logf(" ... other error");
			return restoreAndFail();
		}
	}
	
	// "Return true if the file exists, otherwise false."
	private static boolean doesFileExist(int self, int filenamePointer) {
		String filename = getStringValue(filenamePointer);
		
		logf("\n ==> doesFileExist( '%s' )", filename);
		
		// get the file
		TajoFile tajoFile = TajoFilesystem.locate(filename);
		
		// return the existence boolean
		push( (tajoFile != null) ? Well.known().TruePointer : Well.known().FalsePointer );
		return true;
	}
	
	// "Rename the file to newName."
	private static boolean rename(int self, int oldFilenamePointer, int newFilenamePointer) {
		String oldFilename = getStringValue(oldFilenamePointer);
		String newFilename = getStringValue(newFilenamePointer);
		
		logf("\n ==> rename( '%s' , '%s' )", oldFilename, newFilename);
		
		// get the file
		TajoFile tajoFile = TajoFilesystem.locate(oldFilename);
		if (tajoFile == null) {
			// file not found...
			logf(" ... file not found");
			return restoreAndFail();
		}
		
		// rename
		if (tajoFile.rename(newFilename)) {
			// successful: return self
			logf(" ... file renamed");
			push(self);
			return true;
		} else {
			// rename failed...
			logf(" ... rename failed");
			return restoreAndFail();
		}
	}
	
	// "Delete the file. returns true, if the file was successfully deleted and nil if the file didn't exist"
	private static boolean remove(int self, int filenamePointer) {
		String filename = getStringValue(filenamePointer);
		
		logf("\n ==> remove( '%s' )", filename);
		
		// get the file
		TajoFile tajoFile = TajoFilesystem.locate(filename);
		if (tajoFile == null  || tajoFile.isDirectory()) {
			logf(" ... file not found or is directory");
			return restoreAndFail();
		}
		
		// delete
		tajoFile.delete();
		
		// return self
		push(self);
		return true;
	}
	
	// "Answer the disk free space in pages."
	private static boolean freePages(int self) {
		logf("\n ==> freePages()");
		
		// get free pages count
		int freePages = TajoFilesystem.getFreePages();
		logf(" ... freePages = %d", freePages);
		
		// return as 32 bit int
		int freePagesPointer = positive32BitIntegerFor(freePages);
		push(freePagesPointer);
		return true;
	}
	
	private static iVmFilesHandler vmFilesHandler = null;
	
	public static void setVmFilesHandler(iVmFilesHandler handler) {
		vmFilesHandler = handler;
	}
	
	// "Set the file named fileName as the snapshot file."
	private static boolean setSnapshotFile(int self, int filenamePointer) {
		String filename = getStringValue(filenamePointer);
		
		logf("\n ==> setSnapshotFile( '%s' )", filename);
		
		// the DV6 Smalltalk system ensures that the file exists in the Tajo filesystem,
		// creating the empty file in the top searchpath directory if necessary
		TajoFile tajoFile = TajoFilesystem.locate(filename);
		if (tajoFile == null) {
			// file not found...
			logf(" ... file not found");
			return restoreAndFail();
		}
		
		// set image filename on vmFilesHandler
		if (vmFilesHandler != null) {
			vmFilesHandler.setSnapshotFilename(tajoFile.getOsFile().getAbsolutePath());
		}
		
		// return self
		push(self);
		return true;
	}
	
	// "Answer the string of the names of all directories on the underlying file system."
	// wrong: must be array of string
	// unclear: syntax for subdirectories (full path <volume>path1>...>name ?, simple name ?, relative name parent>...>name ?)
	private static boolean getDirNames(int self) {
		logf("\n ==> getDirNames()");
		
		// get the directories
		List<TajoFile> directories = TajoFilesystem.enumerateFiles( f -> f.isDirectory() );
		
		// build the array of strings
		int arrayPointer = Memory.instantiateClassWithPointers(Well.known().ClassArrayPointer, directories.size() + 1); // +1 for <>
		OTEntry array = Memory.ot(arrayPointer);
		int idx = 0;
		array.storePointerAt(idx++, Memory.createStringObject("<>"));
		for(TajoFile d : directories) {
			String dirName = d.getNiceFullpath();
			logf(" ...: %s", dirName);
			array.storePointerAt(idx++, Memory.createStringObject(dirName));
		}
		
		// return the array
		push(arrayPointer);
		return true;
	}
	
	// "Create the new directory."
	// unclear: what to return? (fullpath?)
	private static boolean createDirectory(int self, int dirnamePointer) {
		String dirname = getStringValue(dirnamePointer);
		
		logf("\n ==> createDirectory( '%s' )", dirname);
		
		// create the file
		TajoFile tajoDir = TajoFilesystem.create(dirname, false);
		if (tajoDir == null) {
			// creation failed...
			logf(" ... creation failed");
			return restoreAndFail();
		}
		
		// return the full path
		int fullnamePointer = Memory.createStringObject(tajoDir.getNiceFullpath());
		push(fullnamePointer);
		return true;
	}
	
	// "Delete the directory."
	private static boolean deleteDirectory(int self, int dirnamePointer) {
		String dirname = getStringValue(dirnamePointer);
		
		logf("\n ==> deleteDirectory( '%s' )", dirname);
		
		// get the file
		TajoFile tajoDir = TajoFilesystem.locate(dirname);
		if (tajoDir == null || !tajoDir.isDirectory() || tajoDir.getChildrenCount() != 0) {
			logf(" ... not found or not a directory or not empty");
			return restoreAndFail();
		}
		
		// delete
		tajoDir.delete();
		
		// return self
		push(self);
		return true;
	}
	
	private static StringBuilder mapChar(StringBuilder sb, char c) {
		if (c == '.') {sb.append("\\."); return sb; }
		if (c == '*') {sb.append("\\*"); return sb; }
		if (c == '(') {sb.append("\\("); return sb; }
		if (c == ')') {sb.append("\\)"); return sb; }
		if (c == '?') {sb.append("\\?"); return sb; }
		if (c == '{') {sb.append("\\{"); return sb; }
		if (c == '}') {sb.append("\\}"); return sb; }
		sb.append(c);
		return sb;
	}
	
	private static final List<String> matchedFilenames = new ArrayList<>();
	private static int lastFetchedFilename = -1;
	
	// "Set the enumerated pattern to patternString."
	private static boolean setEnumerationPattern(int self, int patternPointer) {
		String pattern = getStringValue(patternPointer);
		
		logf("\n ==> setEnumerationPattern( '%s' )", pattern);
		
		// transform the pattern from Smalltalk to (Java-)Regex
		StringBuilder sb = new StringBuilder();
		boolean escapeNext = false;
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (escapeNext) {
				escapeNext = false;
				mapChar(sb, c);
			} else if (c == '\'') {
				escapeNext = true;
			} else if (c == '#') {
				sb.append(".{1}");
			} else if (c == '*') {
				sb.append(".*");
			} else {
				mapChar(sb, c);
			}
		}
		String regexPattern = sb.toString(); // ................. pattern to select items
		String regexSubdirPattern = regexPattern + ">.*$"; // ... pattern to reject subdirectories
		logf(" ... regex: %s   / subdirRegex: %s", regexPattern, regexSubdirPattern);
		
		// find matching files with the following assumptions:
		// - it is expected that the pattern has a full path
		// - find only files and not directores
		Pattern pat = Pattern.compile(regexPattern.toLowerCase());
		Pattern subdirPat = Pattern.compile(regexSubdirPattern.toLowerCase());
		List<TajoFile> files = TajoFilesystem.enumerateFiles(
				f -> !f.isDirectory()
					 && pat.matcher(f.getCmpAbsoluteFilename()).matches()
					 && !subdirPat.matcher(f.getCmpAbsoluteFilename()).matches()
				);
		for (TajoFile f : files) {
			String name = f.getNiceName();
			// logf("\n       ... %s", name);
			matchedFilenames.add(name); 
		}
		lastFetchedFilename = -1;
		
		// return self
		push(self);
		return true;
	}
	
	// "Answer the next file name matching the enumerated pattern."
	private static boolean getNextName(int self) {
		logf("\n ==> getNextName()");
		
		// return next in list or nil
		if (matchedFilenames.isEmpty()) {
			logf(" ... re-done");
			push(Well.known().NilPointer);
			return true;
		}
		lastFetchedFilename++;
		if (lastFetchedFilename >= matchedFilenames.size()) {
			logf(" ... done");
			matchedFilenames.clear();
			push(Well.known().NilPointer);
			return true;
		}
		String fn = matchedFilenames.get(lastFetchedFilename);
		logf(" ... fn: %s", fn);
		push(Memory.createStringObject(fn));
		return true;
	}
	
	// "Release the file."
	private static boolean close(int self, int fileDescriptorPointer) {
		int fileDescriptor = positive32BitValueOf(fileDescriptorPointer);
		
		logf("\n ==> close( 0x%08X )", fileDescriptor);
		
		closeFileDescriptor(fileDescriptor);
		
		// done
		push(self);
		return true;
	}
	
	// "Set the mode of access file and return true if successful, otherwise false."
	private static boolean setAccessOf(int self, int fileDescriptorPointer, int accessPointer) {
		int fileDescriptor = positive32BitValueOf(fileDescriptorPointer);
		int access = positive16BitValueOf(accessPointer);
		
		logf("\n ==> setAccessOf( 0x%08X , %d )", fileDescriptor, access);
		
		FileDescriptor fd = getFileDescriptor(fileDescriptor);
		if (fd == null) {
			logf(" ... invalid file descriptor");
			push(Well.known().FalsePointer);
			return true;
		}
		fd.access = getAccess(access);
		push(Well.known().TruePointer);
		return true;
	}
	
	// "Return the size in bytes."
	private static boolean fileSize(int self, int fileDescriptorPointer) {
		int fileDescriptor = positive32BitValueOf(fileDescriptorPointer);
		
		logf("\n ==> fileSize( 0x%08X )", fileDescriptor);
		
		FileDescriptor fd = getFileDescriptor(fileDescriptor);
		if (fd == null) {
			logf(" ... invalid file descriptor");
			return restoreAndFail();
		}
		
		long size;
		try {
			size = fd.raf.length();
		} catch (IOException e) {
			logf(" ... error on fd.raf.length(): %s", e.getMessage());
			return restoreAndFail();
		}
		int sizePointer = positive32BitIntegerFor((int)Math.min(size, 0x7FFFFFFFL));
		push(sizePointer);
		return true;
	}
	
	private static final byte[] fileBuffer = new byte[512];
	
	// "Read the next buffer from the file and answer how many bytes were actually read. 0 means we are at End of File"
	private static boolean read(int self, int fileDescriptorPointer, int collectionPointer, int nBytesPointer) {
		int fileDescriptor = positive32BitValueOf(fileDescriptorPointer);
		int nBytes = positive16BitValueOf(nBytesPointer);
		
		logf("\n ==> read( 0x%08X , 0x%04X , %d )", fileDescriptor, collectionPointer, nBytes);
		
		FileDescriptor fd = getFileDescriptor(fileDescriptor);
		if (fd == null) {
			logf(" ... invalid file descriptor");
			return restoreAndFail();
		}
		if (fd.raf == null) {
			logf(" ... is directory: read() not allowed");
			return restoreAndFail();
		}
		
		if (fd.access != Access.read && fd.access != Access.readwrite) { // fail??
			logf(" ... access not read(write)");
			push(Well.known().ZeroPointer);
			return true;
		}
		
		OTEntry collection = Memory.ot(collectionPointer);
		int bytesRead = 0;
		int remainingBytes = Math.min(collection.getByteLength(), nBytes);
		while(remainingBytes > 0) {
			try {
				int count = fd.raf.read(fileBuffer, 0, Math.min(fileBuffer.length, remainingBytes));
				if (count <= 0) { break; } // eof
				for (int i = 0; i < count; i++) {
					collection.storeByteAt(bytesRead++, fileBuffer[i]);
				}
				remainingBytes -= count;
			} catch (IOException e) {
				// some error => simulate eof
				break;
			}
		}
		logf(" ... bytes read: %d", bytesRead);
		
		// return bytesRead
		int bytesReadPointer = positive32BitIntegerFor(bytesRead);
		push(bytesReadPointer);
		return true;
	}
	
	// "Write the page into the file and answer how many bytes have been written."
	private static boolean write(int self, int fileDescriptorPointer, int pagePointer, int nBytesPointer) {
		int fileDescriptor = positive32BitValueOf(fileDescriptorPointer);
		int nBytes = positive16BitValueOf(nBytesPointer);
		
		logf("\n ==> write( 0x%08X , 0x%04X , %d )", fileDescriptor, pagePointer, nBytes);
		
		FileDescriptor fd = getFileDescriptor(fileDescriptor);
		if (fd == null) {
			logf(" ... invalid file descriptor");
			return restoreAndFail();
		}
		if (fd.raf == null) {
			logf(" ... is directory: write() not allowed");
			return restoreAndFail();
		}
		
		if (fd.access != Access.write && fd.access != Access.readwrite) { // fail??
			logf(" ... access not (read)write");
			push(Well.known().ZeroPointer);
			return true;
		}
		
		OTEntry page = Memory.ot(pagePointer);
		int bytesWritten = 0;
		int remainingBytes = Math.min(page.getByteLength(), nBytes);
		int currPos = 0;
		while(remainingBytes > 0) {
			int chunkSize = Math.min(fileBuffer.length, remainingBytes);
			for (int i = 0; i < chunkSize; i++) {
				fileBuffer[i] = (byte)page.fetchByteAt(currPos++);
			}
			try {
				fd.raf.write(fileBuffer, 0, chunkSize);
				bytesWritten += chunkSize;
				remainingBytes -= chunkSize;
			} catch (IOException e) {
				break;
			}
		}
		logf(" ... bytesWritten: %d", bytesWritten);
		
		int bytesWrittenPointer = positive32BitIntegerFor(bytesWritten);
		push(bytesWrittenPointer);
		return true;
	}
	
	// "return an Array of Strings"
	private static boolean getSearchPath(int self) {
		logf("\n ==> getSearchPath()");
		
		// get the searchpath
		List<String> searchPath = new ArrayList<>();
		TajoFilesystem.getSearchPath(searchPath);
		
		// build the array of strings
		int arrayPointer = Memory.instantiateClassWithPointers(Well.known().ClassArrayPointer, searchPath.size());
		OTEntry array = Memory.ot(arrayPointer);
		int idx = 0;
		for(String p : searchPath) {
			logf(" ...: %s", p);
			array.storePointerAt(idx++, Memory.createStringObject(p));
		}
		
		// return the array
		push(arrayPointer);
		return true;
	}
	
	// "aList is an Array of Strings"
	private static boolean setSearchPath(int self, int listPointer) {
		
		logf("\n ==> setSearchPath( %d )", listPointer);
		
		// get the new searchpath
		OTEntry list = Memory.ot(listPointer);
		logf("\n     list: %s", list.toString());
		List<String> searchPath = new ArrayList<>();
		for (int i = 0; i < list.getWordLength(); i++) {
			String elem = Memory.getStringValue(list.fetchPointerAt(i));
			logf(" ...: %s", elem);
			searchPath.add(elem);
		}
		
		// set it in the filesystem
		TajoFilesystem.setSearchPath(searchPath);
		
		// done
		push(self);
		return true;
	}
	
	// 01.01.1901 -> 01.01.1970: 69 years having 17 leap years
	private static final long SMALLTALK_TO_UNIX_TIME_DELTA_SECONDS = ((69L * 365L) + 17L) * 86400L;
	
	private static long unixToSmalltalkTime(long unixTime) {
		long smalltalkTime = (unixTime / 1000) + SMALLTALK_TO_UNIX_TIME_DELTA_SECONDS;
		return smalltalkTime;
	}
	
	private static boolean isTextFile(TajoFile file) {
		String lcName = file.getCmpFilename();
		if (lcName.endsWith(".st")) { return true; }
		if (lcName.endsWith(".changes")) { return true; }
		if (lcName.endsWith(".sources")) { return true; }
		if (lcName.endsWith(".text")) { return true; }
		if (lcName.endsWith(".workspace")) { return true; }
		return false;
	}
	
	// "if the file does not exist, return nil, else return an array containing: create date, write date, read date, length (bytes), and type"
	private static boolean getFileProperties(int self, int filenamePointer) {
		String filename = getStringValue(filenamePointer);
		
		logf("\n ==> getFileProperties( '%s' )", filename);
		
		// get the file
		TajoFile file = TajoFilesystem.locate(filename);
		if (file == null) {
			logf(" ... file not found");
			push(Well.known().NilPointer);
			return true;
		}
		
		// build the properties array
		int arrayPointer = Memory.instantiateClassWithPointers(Well.known().ClassArrayPointer, 5);
		OTEntry array = Memory.ot(arrayPointer);
		long modifyDate = file.getOsFile().lastModified(); // that's the only date we have
		int modifyDatePointer = positive32BitIntegerFor(unixToSmalltalkTime(modifyDate));
		int readDatePointer = positive32BitIntegerFor(2114294400L); // marker date for: never read
		int lengthPointer = positive32BitIntegerFor((int)Math.min(file.getOsFile().length(), 0x7FFFFFFFL));
		array.storePointerAt(0, modifyDatePointer); // create date
		array.storePointerAt(1, modifyDatePointer); // write date
		array.storePointerAt(2, readDatePointer);   // read date
		array.storePointerAt(3, lengthPointer);     // length in bytes
		if (file.isDirectory()) {
			array.storePointerAt(4, positive16BitIntegerFor(1)); // type: directory
		} else if (isTextFile(file)) {
			array.storePointerAt(4, positive16BitIntegerFor(2)); // type: text
		} else {
			array.storePointerAt(4, positive16BitIntegerFor(0)); // type: unspecified
		}

		// return the array
		push(arrayPointer);
		return true;
	}
	
	// "(uncommented)"
	private static boolean maxOpenFiles(int self) {
		logf("\n ==> maxOpenFiles()");
		
		// return MAX_OPEN_FILES
		push(positive16BitIntegerFor(MAX_OPEN_FILES));
		return true;
	}
	
	// "(uncommented)"
	private static boolean position(int self, int fileDescriptorPointer, int bytePositionPointer) {
		int fileDescriptor = positive32BitValueOf(fileDescriptorPointer);
		int bytePosition = positive32BitValueOf(bytePositionPointer);
		
		logf("\n ==> position( 0x%08X , %d )", fileDescriptor, bytePosition);
		
		FileDescriptor fd = getFileDescriptor(fileDescriptor);
		if (fd == null) {
			logf(" ... invalid file descriptor");
			return restoreAndFail();
		}
		if (fd.raf == null) {
			logf(" ... is directory: position() not allowed");
			return restoreAndFail();
		}
		
		try {
			fd.raf.seek(bytePosition);
		} catch (IOException e) {
			// ignored
		}
		
		push(self);
		return true;
	}
	
	// "return the complete name of aFileName; nil if not found."
	private static boolean fullNameOf(int self, int filenamePointer) {
		String filename = getStringValue(filenamePointer);
		
		logf("\n ==> fullNameOf( '%s' )", filename);
		
		// get the file
		TajoFile file = TajoFilesystem.locate(filename);
		if (file == null) {
			logf(" ... file not found");
			push(Well.known().NilPointer);
			return true;
		}
		
		// return the full nice name
		String name = file.getNiceFullpath();
		logf(" ...: %s", name);
		int namePointer = Memory.createStringObject(name);
		push(namePointer);
		return true;
	}
	
	// "exchange the fileNames. Do not copy the files. "
	private static boolean swapName(int self, int filenamePointer, int secondFilenamePointer) {
		String oldFilename = getStringValue(filenamePointer);
		String newFilename = getStringValue(secondFilenamePointer);
		
		logf("\n ==> swapName( '%s' , '%s' ) -- not implemented !!", oldFilename, newFilename);
		
		// fail...
		return restoreAndFail();
	}
	
	private static final int MAX_OPEN_FILES = 256;
	private static final int FILEDESCRIPTOR_MARKER = (int)(System.currentTimeMillis() & 0x7FFFFF) << 8;
	
	private enum Access { read, readwrite, write, rename }
	
	private static Access getAccess(int value) { // access: 2 = readonly , 3 = readwrite , 4 = writeonly , 7 = rename
		switch(value) {
		case 2: return Access.read;
		case 4: return Access.write;
		case 7: return Access.rename;
		default: return Access.readwrite;
		}
	}
	
	private static class FileDescriptor {
		public final TajoFile tajoFile;
		public final RandomAccessFile raf;
		public Access access;
		
		private FileDescriptor(TajoFile f, Access a) throws IOException {
			this.tajoFile = f;
			this.raf = (f.isDirectory()) ? null : f.open();
			this.access = a;
		}
	}
	
	private static final FileDescriptor[] openFiles = new FileDescriptor[MAX_OPEN_FILES];
	private static int lastStartPos = 7;
	
	private static int createFileDescriptor(TajoFile f, Access a) {
		// find a "random" free entry
		lastStartPos = (lastStartPos + 1) & 0xFF;
		int fdIdx = (lastStartPos + 1) & 0xFF;
		while(openFiles[fdIdx] != null && fdIdx != lastStartPos) {
			fdIdx = (fdIdx + 1) & 0xFF;
		}
		if (openFiles[fdIdx] != null) {
			// no free fd table entry found
			return -1;
		}
		
		// place a new file descriptor at the free entry
		try {
			FileDescriptor fileDescriptor = new FileDescriptor(f, a);
			openFiles[fdIdx] = fileDescriptor;
			return fdIdx + FILEDESCRIPTOR_MARKER;
		} catch (IOException e) {
			return -1;
		}
	}
	
	private static FileDescriptor getFileDescriptor(int fd) {
		int fdIdx = fd - FILEDESCRIPTOR_MARKER;
		if (fdIdx < 0 || fdIdx > openFiles.length) {
			return null;
		}
		return openFiles[fdIdx];
	}
	
	private static void closeFileDescriptor(int fd) {
		FileDescriptor fileDescriptor = getFileDescriptor(fd);
		if (fileDescriptor == null) { return; }
		openFiles[fd - FILEDESCRIPTOR_MARKER] = null;
		if (fileDescriptor.raf != null) {
			try {
				fileDescriptor.raf.close();
			} catch (IOException e) {
				// ignored
			}
		}
	}
	
}
