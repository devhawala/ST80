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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.IOException;
import java.io.PrintStream;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;

import dev.hawala.st80vm.alto.AltoDisk;
import dev.hawala.st80vm.alto.AltoVmFilesHandler;
import dev.hawala.st80vm.alto.Disk.InvalidDiskException;
import dev.hawala.st80vm.interpreter.Interpreter;
import dev.hawala.st80vm.interpreter.QuitSignal;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.primitives.InputOutput;
import dev.hawala.st80vm.primitives.iVmFilesHandler;
import dev.hawala.st80vm.ui.DisplayBwPane;
import dev.hawala.st80vm.ui.KeyHandler;
import dev.hawala.st80vm.ui.MouseHandler;

/**
 * Main program for the ST80 emulator, which runs a Smalltalk virtual
 * machine implementing the specifications on the "Bluebook"
 * (Smalltalk-80 - The Language and its Implementation, by Adele Goldberg
 * and David Robson, 1983), allowing to work with a Smalltalk-80 V2 snapshot. 
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class ST80 {
	
	/**
	 * Dummy file set handler for the fallback "image only" case
	 */
	private static class ImageOnlyVmFilesHandler implements iVmFilesHandler {
		
		public ImageOnlyVmFilesHandler(String fn) throws IOException {
			Memory.loadVirtualImage(fn, false);
		}

		@Override
		public void setSnapshotFilename(String filename) {
			// ignored
		}

		@Override
		public boolean saveSnapshot(PrintStream ps) {
			Memory.saveVirtualImage(null);
			return true;
		}

		@Override
		public boolean saveDiskChanges(PrintStream ps) {
			// no disk => no changes
			return true;
		}
	}
	
	/**
	 * Window state handler for preventing uncontrolled closing of the top-level window
	 */
	private static class WindowStateListener implements WindowListener {
		
		private final JFrame mainWindow;
		private final boolean statsAtEnd;
		
		public WindowStateListener(JFrame mainWindow, boolean statsAtEnd) {
			this.mainWindow = mainWindow;
			this.statsAtEnd = statsAtEnd;
		}

		@Override
		public void windowOpened(WindowEvent e) { }

		@Override
		public void windowClosing(WindowEvent e) {
			String[] messages = {
				"The Smalltalk-80 engine should be closed using the rootwindow context menu.",
				"Closing the main window will not snapshot the Smalltalk state, but disk changes can be saved.",
				"If the disk is not saved, all changes in this session will be lost.",
				"How do you want to proceed?"
			};
			String[] choices = { "Continue" , "Save disk and quit" , "Quit without saving" };
			int result = JOptionPane.showOptionDialog(
					this.mainWindow,
					messages,
					"Smalltalk-80 Engine is currently running",
					JOptionPane.YES_NO_CANCEL_OPTION,
					JOptionPane.QUESTION_MESSAGE,
				    null,
				    choices,
				    choices[0]);
			if (result == 1) {
				Interpreter.stopInterpreter();
			} else if (result == 2) {
				if (this.statsAtEnd) {
					System.out.printf("\n## terminating ST80, cause: close window (without saving disk)\n");
					Interpreter.printStats(System.out);
				}
				System.exit(0);
			}
		}

		@Override
		public void windowClosed(WindowEvent e) { }

		@Override
		public void windowIconified(WindowEvent e) { }

		@Override
		public void windowDeiconified(WindowEvent e) { }

		@Override
		public void windowActivated(WindowEvent e) { }

		@Override
		public void windowDeactivated(WindowEvent e) { }
	}
	
	/**
	 * 
	 * @param args
	 * @throws IOException
	 * @throws InvalidDiskException
	 */
	public static void main(String[] args) throws IOException, InvalidDiskException {
		/*
		 * get commandline arguments
		 */
		String imageFile = null;
		boolean haveStatusline = false;
		Integer timeAdjustMinutes = null;
		boolean statsAtEnd = false;
		
		for (String arg : args) {
			String lcArg = arg.toLowerCase();
			if ("--statusline".equals(lcArg)) {
				haveStatusline = true;
			} else if (lcArg.startsWith("--timeadjust:")) {
				String minutes = lcArg.substring("--timeadjust:".length());
				try {
					timeAdjustMinutes = Integer.valueOf(minutes);
				} catch(NumberFormatException nfe) {
					System.out.printf("warning: ignoring invalid argument '%s' for --timeAdjust:\n", minutes);
				}
			} else if ("--stats".equals(lcArg)) {
				statsAtEnd = true;
			} else if (arg.startsWith("--")) {
				System.out.printf("warning: ignoring invalid option '%s'\n", arg);
			} else if (imageFile == null) {
				imageFile = arg;
			} else {
				System.out.printf("warning: ignoring argument '%s'\n", arg);
			}
		}
		if (imageFile == null) {
			System.out.printf("error: missing image (base)filename, aborting\n");
			System.out.printf("\nUsage: st80 [--statusline] [--stats] [--timeadjust:nn] image-file[.im]\n");
			return;
		}
		
		/*
		 * create the file set handler for the given image file, loading the Smalltalk image and
		 * possibly the associated Alto disk, and register it to the relevant primitive implementations
		 */
		iVmFilesHandler vmFiles = AltoVmFilesHandler.forImageFile(imageFile);
		if (vmFiles == null) {
			vmFiles = new ImageOnlyVmFilesHandler(imageFile); 
		}
		InputOutput.setVmFilesHandler(vmFiles);
		AltoDisk.setVmFilesHandler(vmFiles);
		
		/*
		 * setup Smalltalk time (for correcting the hard-coded Xerox-PARC timezone)
		 */
		if (timeAdjustMinutes != null) {
			InputOutput.setTimeAdjustmentMinutes(timeAdjustMinutes);
		}
		
		/*
		 * build a rather simple Java-Swing UI
		 */
		
		// create top-level window
		JFrame mainFrame = new JFrame();
		mainFrame.getContentPane().setLayout(new BorderLayout(2, 2));

		// the b/w display bitmap panel with mouse/keyboard handlers
		Dimension dims = new Dimension(640, 480);
		DisplayBwPane displayPanel = new DisplayBwPane(mainFrame, dims);
		mainFrame.getContentPane().add(displayPanel, BorderLayout.CENTER);
		MouseHandler mouseHandler = new MouseHandler(displayPanel);
		displayPanel.addMouseMotionListener(mouseHandler);
		displayPanel.addMouseListener(mouseHandler);
		displayPanel.addKeyListener(new KeyHandler());
		
		// connect the (Java) display panel with the (Smalltalk) display bitmap
		InputOutput.registerDisplayPane(displayPanel);
		
		// add the status line 
		if (haveStatusline) {
			JLabel statusLine = new JLabel(" Mesa Engine not running");
			statusLine.setFont(new Font("Monospaced", Font.BOLD, 12));
			mainFrame.getContentPane().add(statusLine, BorderLayout.SOUTH);
			Interpreter.setStatusConsumer( s -> statusLine.setText(s) );
		}
		
		// finalize the top-level window and display it in an own thread
		mainFrame.pack();
		mainFrame.setResizable(false);
		mainFrame.setTitle("Smalltalk-80 Engine");
		mainFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		mainFrame.addWindowListener(new WindowStateListener(mainFrame, statsAtEnd));
		
		EventQueue.invokeLater(() -> mainFrame.setVisible(true));
		
		/*
		 * restart the virtual-image, running the Smalltalk interpreter
		 * in the main thread until it finished by itself
		 */
		int suspendedContextAtSnapshot = Interpreter.firstContext();
		Interpreter.setVirtualImageRestartContext(suspendedContextAtSnapshot);
		try {
			Interpreter.interpret();
		} catch(QuitSignal qs) {
			vmFiles.saveDiskChanges(System.out);
			if (statsAtEnd) {
				System.out.printf("\n## terminating ST80, cause: %s\n", qs.getMessage());
				Interpreter.printStats(System.out);
			}
			mainFrame.setVisible(false);
			System.exit(0);
		} catch(Exception e) {
			vmFiles.saveDiskChanges(System.out);
			e.printStackTrace();
			if (statsAtEnd) {
				Interpreter.printStats(System.out);
			}
			mainFrame.setVisible(false);
			System.exit(0);
		}
	}

}
