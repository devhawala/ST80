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

package dev.hawala.st80vm.ui;

/**
 * Definition of the functionality of a "real" display allowing the
 * Smalltalk engine to refresh the display bitmap shown on the "real" screen
 * and set the cursor shape.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public interface iDisplayPane {
	
	/**
	 * Set the new cursor shape to be used.
	 * 
	 * @param cursor 16 words for the cursor bits, giving a 16x16 cursor shape
	 * @param hotspotX horizontal position of the cursor hotspot in the 16x16 shape, should be in the range 0..15
	 * @param hotspotY vertical position of the cursor hotspot in the 16x16 shape, should be in the range 0..15
	 */
	void setCursor(short[] cursor, int hotspotX, int hotspotY);

	/**
	 * Update a portion of the black&amp;white display screen bitmap.
	 * 
	 * @param mem the heap memory array where to get the bitmap words from
	 * @param start the start offset in {@code mem} for the bitmap (most significant bit at {@code mem[start]} is at coordinate 0x0)
	 * @param displayWidth width of the Smalltalk display bitmap in pixels (bits)
	 * @param displayRaster number of words (16 bit) in a display scan line
	 * @param displayHeight number of scan lines in the display bitmap
	 * @param firstLine first scan line that was changed by the Smalltalk system since the last update (0-based) 
	 * @param lastLine last scan line that was changed by the Smalltalk system since the last update (0-based)
	 */
	void copyDisplayContent(short[] mem, int start, int displayWidth, int displayRaster, int displayHeight, int firstLine, int lastLine);
}
