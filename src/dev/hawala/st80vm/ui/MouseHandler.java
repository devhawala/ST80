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

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

import dev.hawala.st80vm.primitives.InputOutput;

/**
 * Mouse event handler forwarding mouse movement and button events to the Smalltalk engine.
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class MouseHandler implements MouseListener, MouseMotionListener {
	
	private final DisplayBwPane displayPanel;
	
	private int lastX = Integer.MIN_VALUE;
	private int lastY = Integer.MIN_VALUE;
	
	private static final int[] mouseKeys = {
		InputOutput.K_MOUSE_LEFT,
		InputOutput.K_MOUSE_MIDDLE,
		InputOutput.K_MOUSE_RIGHT
	};
	
	public MouseHandler(DisplayBwPane displayPanel) {
		this.displayPanel = displayPanel;
	}

	private void handleNewMousePosition(MouseEvent ev) {
		if (ev == null) { return; }
		
		if (!this.displayPanel.hasFocus()) {
			this.displayPanel.grabFocus();
		}

		int newX = Math.min(Math.max(0, ev.getX()), this.displayPanel.getWidth() - 1);
		int newY = Math.min(Math.max(0, ev.getY()), this.displayPanel.getHeight() - 1);
		
		if (this.lastX != newX || this.lastY != newY) {
			this.lastX = newX;
			this.lastY = newY;
			InputOutput.mouseMoved(this.lastX, this.lastY);
		}
	}

	@Override
	public void mouseDragged(MouseEvent ev) {
		this.handleNewMousePosition(ev);
	}

	@Override
	public void mouseMoved(MouseEvent ev) {
		this.handleNewMousePosition(ev);
	}

	@Override
	public void mouseClicked(MouseEvent ev) {
		this.handleNewMousePosition(ev);
	}

	@Override
	public void mouseEntered(MouseEvent ev) {
		this.handleNewMousePosition(ev);
	}

	@Override
	public void mouseExited(MouseEvent ev) {
		this.handleNewMousePosition(ev);
	}

	@Override
	public void mousePressed(MouseEvent ev) {
		this.handleNewMousePosition(ev);
		int btn = ev.getButton() - 1;
		if (btn >= 0 && btn < 3) {
			InputOutput.undecodedKeyDown(mouseKeys[btn]);
		}
	}

	@Override
	public void mouseReleased(MouseEvent ev) {
		this.handleNewMousePosition(ev);
		int btn = ev.getButton() - 1;
		if (btn >= 0 && btn < 3) {
			InputOutput.undecodedKeyUp(mouseKeys[btn]);
		}
	}

}