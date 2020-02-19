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

package dev.hawala.st80vm;

/**
 * Configuration of runtime logging behavior of the Smalltalk engine.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class Config {
	
	private Config() { }
	
	public static final boolean OPTIMIZE_SCREEN_REFRESH = true;
	
	private static final boolean LOG = false;
	
	public static final boolean LOG_INSTRS = false; // LOG && true;
	
	public static final boolean LOG_PRIMITIVES = false; // LOG && true;
	
	public static final boolean LOG_MEM_OPS = false; // LOG && true;
	public static final boolean LOG_MEM_GC = false; // LOG && true;
	public static final boolean LOG_MEM_GC_DETAILS = false; // LOG && true;
	
	public static final boolean TRACE_FLOAT = false; // LOG && true;
	
	public static final boolean TRACE_IO = false; // LOG && true;
	
	public static final boolean TRACE_BITBLT = false; // LOG && true;
	
	public static final boolean TRACE_ALTODISK_IO = false; // LOG && true;
	
	public static final boolean TRACE_TAJO_OPS = false; // LOG && true;

}
