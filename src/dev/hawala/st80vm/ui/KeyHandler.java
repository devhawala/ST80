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

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.List;

import dev.hawala.st80vm.primitives.InputOutput;

/**
 * Key event handler forwarding keyboard events to the Smalltalk engine.
 * <p>
 * Remark: there are some hard-coded mappings for a german keyboard, that should
 * be irrelevant for non-german keyboards.
 * </p>
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class KeyHandler implements KeyListener {
		
		private static final int EC_SHIFT = 16;
		private static final int EC_CONTROL = 17;
		private static final int EC_ALT = 18;
		private static final char[] diacriticals = "^´`�".toCharArray();
		
		private static boolean isDiactritical(char c) {
			for (int i = 0; i < diacriticals.length; i++) {
				if (diacriticals[i] == c) { return true; }
			}
			return false;
		}
		
		private final List<Integer> currentPressed = new ArrayList<>();
		private final List<Character> lastSent = new ArrayList<>();
		
		private boolean isControl = false;
		private boolean sentControl = false;
		private boolean isShift = false;
		private boolean sentShift = false;
		
		private void sendControlDown() {
//			System.out.printf("  ==> undecodedKeyDown(K_CONTROL)\n");
			InputOutput.undecodedKeyDown(InputOutput.K_CONTROL);
			this.sentControl = true;
		}
		
		private void sendControlUp() {
//			System.out.printf("  ==> undecodedKeyUp(K_CONTROL)\n");
			InputOutput.undecodedKeyUp(InputOutput.K_CONTROL);
		}
		
		private void sendShiftDown() {
//			System.out.printf("  ==> undecodedKeyDown(K_LEFT_SHIFT)\n");
			InputOutput.undecodedKeyDown(InputOutput.K_LEFT_SHIFT);
			this.sentShift = true;
		}
		
		private void sendShiftUp() {
//			System.out.printf("  ==> undecodedKeyUp(K_LEFT_SHIFT)\n");
			InputOutput.undecodedKeyUp(InputOutput.K_LEFT_SHIFT);
		}
		
		private void sendKey(int key) {
			if (this.isControl && !this.sentControl) {
				this.sendControlDown();
			}
			if (this.isShift && this.isControl && !this.sentShift) {
				this.sendShiftDown();
			}
			if (key >= 20 && key < 128) {
//				System.out.printf("  ==> decodedKeyPressed(): %c (0x%02X)\n", (char)key, key);
			} else {
//				System.out.printf("  ==> decodedKeyPressed(): 0x%04X\n", key);
			}
			InputOutput.decodedKeyPressed(key);
		}
		
		private static char mapGermans(char c) {
			switch(c) {
			case 'ö': return '[';
			case 'Ö': return '{';
			case 'ä': return ']';
			case 'Ä': return '}';
			case 'ü': return '\\';
			case 'Ü': return '|';
			default: return c;
			}
		}

		@Override
		public void keyPressed(KeyEvent evt) {
			int code = evt.getExtendedKeyCode();
			Integer downCode = Integer.valueOf(code);
			Character currChar = mapGermans(evt.getKeyChar());
			
			if (!this.currentPressed.contains(downCode)) {
//				System.out.printf("at %d : panel.keyPressed -> keyCode = %03d, extKeyCode = %05d, keyChar = %c == %d\n",
//				System.currentTimeMillis(), evt.getKeyCode(), evt.getExtendedKeyCode(), evt.getKeyChar(), (int)evt.getKeyChar());
				
				this.currentPressed.add(downCode);
				
				// interpret code and possibly send undecodedKeyDown() event to the Smalltalk engine
				
				if (code == EC_CONTROL) {
					this.isControl = true;
					this.sentControl = false;
				}
				if (code == EC_ALT && this.isControl) { // handle AltGr == Ctrl down , Alt down (on Windows!)
					this.isControl = false;
					this.sentControl = false;
				}
				if (code == EC_SHIFT) {
					this.isShift = true;
					this.sentShift = false;
				}
				if (isDiactritical(currChar) && (int)currChar <= 128) {
					this.sendKey(currChar);
				}
				if (this.isControl && (int)currChar == 65535) { // e.g. Ctrl-6
					currChar = (char)evt.getKeyCode();
				}
				if (this.isControl && (int)currChar >= 32 && (int)currChar <= 128) {
					this.sendKey(currChar);
				}
				if (code == KeyEvent.VK_INSERT) {
					// map Insert => LF
					this.sendKey(0x0A);
				}
			}
		}

		@Override
		public void keyTyped(KeyEvent evt) {
			Character currChar =  evt.getKeyChar();
			
			if (!this.lastSent.contains(currChar)) {
//				System.out.printf("panel.keyTyped -> keyCode = %03d, extKeyCode = %05d, keyLocation = %d, keyChar = %c == %d\n",
//				evt.getKeyCode(), evt.getExtendedKeyCode(), evt.getKeyLocation(), evt.getKeyChar(), (int)evt.getKeyChar());
				
				this.lastSent.add(evt.getKeyChar());
				
				// interpret code and possibly send decodedKeyPressed() event to the Smalltalk engine
				switch(currChar) {
				case (char)0x0A: // swap LF => CR
					currChar = (char)0x0D;
					break;
				case (char)0x0D: // swap CR => LF
					currChar = (char)0x0A;
					break;
				default:
					if (!this.isControl) {
						currChar = mapGermans(currChar);
					}
					break;
				}

				if (isDiactritical(currChar) // already sent in keydown
					|| (int)currChar > 128) {// not an ascii => unknown to ST-80
					return;
				}
				
				if (this.isControl && (int)currChar < 32 && evt.getExtendedKeyCode() == 0) {
					// Ctrl-@ .. Ctrl-_ == not a true control key
					currChar = Character.toLowerCase((char)((int)currChar + 64)); // shift to @ .. _
					switch(currChar) { // fix some common wrong shifts with control key on Windows
					case '_' : currChar = '-'; break;
					case ':': currChar = '.'; break;
					case ';': currChar = ','; break;
					default: break;
					}
				}
				this.sendKey(currChar);
			}
		}

		@Override
		public void keyReleased(KeyEvent evt) {
//			System.out.printf("at %d : panel.keyReleased -> keyCode = %03d, extKeyCode = %05d, keyChar = %c == %d\n",
//			System.currentTimeMillis(), evt.getKeyCode(), evt.getExtendedKeyCode(), evt.getKeyChar(), (int)evt.getKeyChar());
			
			int code = evt.getExtendedKeyCode();
			Integer upCode = Integer.valueOf(code);
			Character currChar = evt.getKeyChar();
			
			if (this.currentPressed.contains(upCode)) { this.currentPressed.remove(upCode); }
			if (this.lastSent.contains(currChar)) { this.lastSent.remove(currChar); }
			
			// interpret code and possibly send decodedKeyPressed() event to the Smalltalk engine
			if (code == EC_CONTROL) {
				if (this.sentControl) { this.sendControlUp(); }
				if (this.sentShift) { this.sendShiftUp(); } 
				this.isControl = false;
				this.sentControl = false;
				this.sentShift = false;
			}
			if (code == EC_SHIFT) {
				if (this.sentShift) { this.sendShiftUp(); } 
				this.isShift = false;
			}
		}
		
	}