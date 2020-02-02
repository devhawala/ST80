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

package dev.hawala.st80vm.alto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import dev.hawala.st80vm.alto.Disk.InvalidDiskException;

/**
 * Command-line utility for creating / listing / modifyingAlto disk images
 * (single disk filesystem only).
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class AltoFile {
	
	private static void logf(String format, Object... args) {
		System.out.printf(format, args);
	}
	
	private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd HH.mm.ss");
	
	private static class ArgSequence {
		private final String[] args;
		private int idx = 0;
		
		private ArgSequence(String[] a) {
			this.args = a;
		}
		
		private String next() {
			if (idx >= args.length) { return null; }
			return this.args[idx++];
		}
		
		private String nextValue() {
			String val = this.next();
			if (val == null || val.startsWith("--")) { return null; }
			return val;
		}
		
		private String nextCmd() {
			String val = this.next();
			if (val == null || !val.startsWith("--")) { return null; }
			return val;
		}
	}

	public static void main(String[] args) throws InvalidDiskException, IOException {
		ArgSequence as = new ArgSequence(args);

		String diskFileName = as.next();
		if (diskFileName == null) {
			logf("options:\n" +
				 " --create | <image-filename>\n" +
				 " --list\n" +
				 " --scan\n" + 
				 " --import <filename> <alto-filename>\n" +
				 " --export <alto-filename> <filename>\n" + 
				 " --rm <alto-filename>\n" +
				 " --ren <alto-fn-old> <alto-fn-new>\n" +
				 " --save\n" +
				 " --saveas <filename>\n");
			return;
		} else if ("--create".equals(diskFileName)) {
			Disk.format();
		} else {
			File cand = new File(diskFileName);
			if (!cand.exists()) {
				logf("Alto-disk-image not found, aborting");
				return;
			}
			Disk.loadDiskImage(cand);
		}
		
		String cmd = as.nextCmd();
		while(cmd != null) {
			logf("\n%s ::\n", cmd);
			try {
				if ("--list".equalsIgnoreCase(cmd)) {
					List<FileEntry> entries = Disk.listFiles();
					logf("Name                                     Bytes   Created             Written             Read\n");
					logf("---------------------------------------- ------- ------------------- ------------------- -------------------\n");
					for (FileEntry e : entries) {
						LeaderPage lp = e.getLeaderPage();
						logf("%-40s %7d %s %s %s\n", e.getFilename(), e.getSizeInBytes(), sdf.format(lp.getCreated()), sdf.format(lp.getWritten()), sdf.format(lp.getRead()));
					}
				} else if ("--scan".equalsIgnoreCase(cmd)) {
					List<FileEntry> entries = Disk.scanRootFiles();
					logf("Name                                       Bytes\n");
					logf("---------------------------------------- -------\n");
					for (FileEntry e : entries) {
						logf("%-40s %7d\n", e.getFilename(), e.getSizeInBytes());
					}
				} else if ("--save".equalsIgnoreCase(cmd)) {
					Disk.saveDiskImage(null);
				} else if ("--saveas".equalsIgnoreCase(cmd)) {
					String filename = as.nextValue();
					if (filename == null) {
						logf("** missing parameter <filename>\n");
					} else {
						Disk.saveDiskImage(new File(filename));
					}
				} else if ("--import".equalsIgnoreCase(cmd)) {
					String filename = as.nextValue();
					String altoFilename = as.nextValue();
					if (filename == null) {
						logf("** missing parameter <filename>\n");
					} else if (altoFilename == null) {
						logf("** missing parameter <altoFilename>\n");
					} else {
						File f = new File(filename);
						LeaderPage leaderPage = Disk.createFile(altoFilename, null, new Date(f.lastModified()), null);
						FileWriter fw = new FileWriter(leaderPage, 0);
						
						try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f))) {
							int b;
							while((b = bis.read()) >= 0) {
								fw.putByte((byte)b);
							}
						}
					}
				} else if ("--export".equalsIgnoreCase(cmd)) {
					String altoFilename = as.nextValue();
					String filename = as.nextValue();
					if (altoFilename == null) {
						logf("** missing parameter <altoFilename>\n");
					} else if (filename == null) {
						logf("** missing parameter <filename>\n");
					} else {
						File f = new File(filename);
						LeaderPage leaderPage = Disk.getFile(altoFilename);
						if (leaderPage != null) {
						FileReader fr = new FileReader(leaderPage.getPage());
						
						try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(f))) {
							while(!fr.isAtEnd()) {
								byte b = fr.nextByte();
								bos.write(b);
							}
						}
						} else {
							logf("** file not found : <altoFilename>\n");
						}
					}
				} else if ("--rm".equalsIgnoreCase(cmd)) {
					String altoFilename = as.nextValue();
					if (altoFilename == null) {
						logf("** missing parameter <altoFilename>\n");
					} else {
						Disk.deleteFile(altoFilename);
					}
				} else if ("--ren".equalsIgnoreCase(cmd)) {
					String altoOldFilename = as.nextValue();
					String altoNewFilename = as.nextValue();
					if (altoOldFilename == null) {
						logf("** missing parameter <alto-fn-old>\n");
					} else if (altoNewFilename == null) {
						logf("** missing parameter <alto-fn-new>\n");
					} else {
						Disk.renameFile(altoOldFilename, altoNewFilename);
					}
				} else {
					logf("\n** invalid subcommand: %s\n", cmd);
				}
			} catch (Exception e) {
				logf("** exception %s: %s\n", e.getClass().getName(), e.getMessage());
			}
			
			cmd = as.nextCmd();
		}
	}

}
