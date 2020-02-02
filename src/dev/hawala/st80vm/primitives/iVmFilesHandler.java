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

import java.io.PrintStream;

/**
 * Interface for handling a file set consisting at least of a Smalltalk image
 * and possibly additional items providing the external file system to which
 * the image is attached to.
 * <p>
 * When an instance of this interface is created, the constructor must load
 * the Smalltalk image into the object memory and possibly open the file system
 * in the implementation specific way. This instance must then be registered
 * to the {@link InputOutput} class and possibly to the implementation for the
 * vendor specific primitives allowing the Smalltalk engine to access the file
 * system provided with the file set.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public interface iVmFilesHandler {
	
	/**
	 * Set the name of the next snapshot to be written.
	 * @param filename the snapshot name as provided by the Smalltalk system
	 */
	void setSnapshotFilename(String filename);
	
	/**
	 * Create a snapshot of the running Smalltalk environment, i.e. write the
	 * object memory in a compatible format for later continuation with the 
	 * filename given with {@link setSnapshotFilename} (or the original filename
	 * of the running snapshot) and do the necessary save operations for the
	 * attached (vendor specific) file system. This may also involve backing
	 * up the current snapshot and file system items (e.g. to an ZIP-archive)
	 * before writing out the new snapshot and filesystem items. 
	 * 
	 * @param ps sink where to write messages
	 * @return {@code true} if successful or {@false} if an error was encountered
	 * 		and message were written to {@code ps}
	 */
	boolean saveSnapshot(PrintStream ps);
	
	/**
	 * Save modifications to the (vendor specific) file system without creating
	 * a snapshot of the Smalltalk object memory.
	 * 
	 * @param ps sink where to write messages
	 * @return {@code true} if successful or {@false} if an error was encountered
	 * 		and message were written to {@code ps}
	 */
	boolean saveDiskChanges(PrintStream ps);
	
}