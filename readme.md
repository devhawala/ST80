## ST80 - a Smalltalk-80 virtual machine based on the "Bluebook" specification

ST80 is a virtual machine for Smalltalk-80 as specified in the "Bluebook" (Smalltalk-80:
the Language and its Implementation, see Bibliography) implemented in Java 8.

The ST80 program allows to run some historical versions of Smalltalk-80:

- the Smalltalk-80 Version 2 image of 1983 found at the archive.org website
  (see [Smalltalk-80](https://archive.org/details/smalltalk-80)). The archive found there seems
  to be the content of an original Xerox distribution tape for Smalltalk-80, at least regarding the most
  important files like the virtual memory image itself, the Smalltalk-80 source code and some trace files
  indicating which instructions are executed when the image is restarted.

- the Smalltalk-80 DV6 distribution of 1987 found at Bitsavers
  ([1186_Smalltalk-80_DV6_Dec87](http://bitsavers.org/bits/Xerox/1186/1186_Smalltalk-80_DV6_Dec87.zip)).
  This is Version 6 of Smalltalk-80 for the Xerox 1186 (6085) Daybreak workstations (thus the _DV6_ version). The floppy
  images at Bitsavers are slightly corrupted as each first track is lost, giving a hard time to read the floppies with
  a modified version of the Dwarf emulator for restoring the content.    
  The Smalltalk-80 DV6 image uses a modified encoding scheme for object pointers called _Stretch_, which differs from
  the Bluebook specification for having more true objects in the live system (48k objects instead of 32k) at the expense of
  the number of SmallIntegers (16k instead of 32k).
  
- the Smalltalk-80 "The Analyst V1.2" distribution of 1987 found at Bitsavers
  ([1186_The_Analyst_V1.2_Dec87](http://bitsavers.org/bits/Xerox/1186/1186_The_Analyst_V1.2_Dec87.zip)).
  This is the _Analyst_ package installed in a DV6 Smalltalk-80 system, using the Stretch memory model but
  extended with the _Large-Object-Oriented-Memory_ (LOOM) model. As ST80 does not support LOOM, the "type 5" image file
  found in the distribution must be converted with an utility provided by ST80, producing a "type 1" DV6 Stretch
  image file that ST80 can run (with not much free object table and heap memory left however).

As the appearance dates of the Smalltalk-80 book series and the source code of Version 2 are all 1983 (the image file is from 1985,
but this may be due to a Unix copy operation), the idea and challenge to "resurrect" this vintage
Smalltalk version emerged, in the pure interest of preserving an important piece of computer history as usable and
experienceable item instead of a "look at it but don't touch" museum exhibit in a glas case.    
Extending the first implementation afterwards to also support the _Stretch_ memory model for DV6 was a "natural"
evolution path.

Both vintage Smalltalk environments cannot compete with any serious modern - commercial or open source - Smalltalk
implementation, which swim rings around the original 16-bit Smalltalk-80 systems,
be it in terms of execution speed, memory (heap size, max. number of objects), technical capabilities (networking,
version management, ...), user interface (color, frameworks, ...) etc.

For most parts of the implementation, ST80 is a faithful translation to Java of the Smalltalk pseudo-code
used in the Bluebook for specifying the virtual machine, with some bugfixes. The main exception is the object memory,
which could be implemented without the hardware restrictions for the available RAM that were common when 
Smalltalk-80 was specified, allowing for a much simpler design of the at most 1 MWord (yes: 16 segments of 65536 
16-bit-words or 2 MBytes) heap memory and the 16 bit object pointers. Besides this, all required and some
optional primitives are implemented by ST80.   
Subsequent changes to support the _Stretch_ mode were mostly localized to the object memory implementation, however
with some minimal but important changes in the interpreter due to the reduced value range for SmallIntegers.

Both Smalltalk-80 versions were intended to run on a specific computer environment:

- the available Version 2 Smalltalk image expects to run on an Alto machine for all disk I/O operations. So an emulation for an
Alto harddisk was added to ST80, implementing the "vendor-specific" primitives for the Alto. This emulation uses
the same disk image format used by common Alto emulators ([ContrAlto](https://github.com/livingcomputermuseum/ContrAlto)
or [Salto](http://bitsavers.org/bits/Xerox/Alto/simulator/salto/)). The disk image contains a single disk Alto file system
which is accessed by the relevant Smalltalk classes through simulated low-level Alto hardware disk-I/O operations.    
ST80 also includes an utility for creating, loading and writing Alto disk images, list the disk content
and manipulating the disk like adding/renaming/deleting files.

- the DV6 Smalltalk image and the Analyst system built on top of it use the file system provided by XDE/Tajo, the Xerox
Development Environment, running on an 1186 workstation. ST80 provides a simple emulation of the XDE environment which maps
the root volume of the Tajo filesystem to a base directory of the OS where ST80 runs, providing the basic facilities like 
search path and case insensitive filenames. Most of the larger set of "vendor-specific" primitives used by the DV6 version
of Smalltalk-80 are implemented by ST80.

In a nutshell, ST80 should allow to work "as usual" with the original images for the vintage Smalltalk-80 Version 2
and DV6 environments. The following screenshot shows the ST80 application window running the version 2 image with
a companion Alto disk where the source code for the method shown in the browser (notice the variable name and comment) and
the _SmalltalkBalloon_ picture were loaded from:

![ST80 screenshot](st80-sample-800x600.png)

### Data files for running ST80

In addition to the memory image, a Smalltalk-80 system uses a number of files in an external file system. These are the sources
file holding the source code for all classes in the image, the changes file logging all changes (like class/method modifications,
_doit_ evaluations) for recovery after a system crash and some further data files like externalized ("filed-out") Smalltalk code,
bitmaps etc. 

In the original setup, these files reside in the file system of the operating system where the Smalltalk interpreter was started.
ST80 simulates these operating system environments (Alto resp. XDE/Tajo) to some (rather minimal) extent to allow the Smalltalk
image to work with files in the expected way. The following sections describe how the expected file systems are mapped to the
"real" OS where ST80 runs.

#### Alto environment for Smalltalk-80 Version 2

ST80 uses a _data file set_ of up to 3 files grouped by the common base _filename_ for a single Smalltalk-80
Version 2 environment instance:

- _filename_`.im` is the required Smalltalk image file

- _filename_`.dsk` is the associated Alto disk image file containing the filesystem that the Smalltalk
  environment has access to; the Alto disk image file is optional, as (at least the version 2) Smalltalk environment
  can run without having access to a filesystem, however this results in some restrictions (the class browser
  shows methods without comments or the names of temporary variables by disassembling the compiled method code,
  modifications cannot be logged to a _.changes_ file, ...)
  
- _filename_`.dsk.delta` is automatically created when the Alto disk image is present and holds the disk pages
  changed by the Smalltalk environment

Depending on the presence of an Alto disk image, ST80 behaves differently for snapshots and saving changes.

**Data file set with the Smalltalk image file only**

If the data file set has only the Smalltalk image, the only way to persist modifications in the Smalltalk
environment is to create a snapshot of the environment through the background middle-button menu using the
_Save_ command or the _Quit_ command with the _Save, then quit_ option.

The new snapshot replaces the Smalltalk image file used to start ST80. Before overwriting with the new snapshot, ST80
backups the current snapshot file to a file copy with same name and the file creation timestamp appended. For example
the first backup for the original `VirtualImage.im` will be named `VirtualImage.im;1985.11.06_12.01.05.000`,
while the new snapshot will again be named `VirtualImage.im`.

**Complete data file set with Alto disk image**

In the presence of the Alto disk image in the data file set, the Smalltalk environment in the original delivery state
must be initialized (only once) to use the disk file system by executing the following Smalltalk code:

```
Disk _ AltoFileDirectory new.

SourceFiles _ Array new: 2.
SourceFiles at: 1 put:
	(FileStream oldFileNamed: 'Smalltalk-80.sources').
SourceFiles at: 2 put:
	(FileStream oldFileNamed: 'Smalltalk-80.changes').
(SourceFiles at: 1) readOnly.
```

This Smalltalk fragment needs not be typed in a workspace, as it is available in the system workspace in the
first section **Create File System**; in the original delivery Smalltalk image, the system workspace is already
opened when the system is brought up, moreover the above text is selected by default, so a _do it_ from the
middle-button menu of the pre-opened system workspace should suffice.    
(the next sentence in the system workspace disconnects the system from the Alto filesystem, so this line should
not be included in the selection)

With the connected Alto filesystem, the Smalltalk environment automatically writes modifications to the changes
file specified in the `SourceFiles` array at the 2nd position (see above).

When ST80 terminates normally, changes to the Alto disk by the Smalltalk environment since the last snapshot
resp. since the program start are saved to the delta file in the data file set.

When a new snapshot of the Smalltalk environment is created, ST80 first backups the current file data set
into an archive ZIP file named _filename_-_timestamp_.ZIP before creating the new data file set, consisting
of the new snapshot and a full save of the Alto disk (i.e. only the new `.dsk` disk image without delta file).

Remark:    
when a snapshot is requested by the user, the Smalltalk system asks for the name of the snapshot, proposing
_snapshot_ as default name. The Smalltalk system uses the given name for creating `.im` and `.changes` files in
the Alto file system (if necessary) and informs the virtual machine about the new snapshot name. The content of the
current changes file is copied in the Alto filesystem to the new one and Smalltalk will log further changes to the new
change file. As ST80 does not write the snapshot into the Alto filesystem (saving space in this resource restricted to
about 2.5 MBytes), it will create the new data file set in the local file system with this name as common filename
(and possibly backup an existing file set with same name).    
However, the Smalltalk system is not symmetric regarding to the snapshot name: if the name is defaulted (i.e. _snapshot_
is simply confirmed) while a different snapshot name was last used (e.g. _works_), then the Smalltalk system will inform the
virtual machine about the new name, but will still continue to use the current change file instead of switching to
the new one.    
For this reason and as disk space is no longer a restriction these days, it could be a good practice to stick with the
default snapshot name and copy the whole data file set into a new directory (preserving the default snapshot name) when
a new snapshot branch is necessary.

#### Tajo environment for Smalltalk-80 DV6

A Smalltalk-80 DV6 image cannot be used without an external file system. Although the disk connection for source
files preset in the image can be released (by setting `Disk`to `nil`), it will not be possible to create snapshots
of the running Smalltalk system, as the file system is interfaced directly by Smalltalk for this. So ST80 supports
a file system for a Smalltalk-80 DV6 image by default (if the files besides the image seem acceptable, see _Invoking ST80_
and below).

The emulated Tajo/XDE file system operates directly on the local OS file system where ST80 is started and maps
a given base directory as volume `<User>` of the Tajo environment. The directory structure and files under the
base directory are made visible under this volume root with the same directory structure and with case-insensitive
names. There is however one major exception: files having a semicolon in the filename are not mapped into the
Tajo file system, allowing to hide files used or created by ST80 from the Smalltalk environment. These files are:

- the search path definition file

- Smalltalk image backup files, which have the original filename with a semicolon and the timestamp appended

A search path definition file is named `;searchpath.txt` and contains - one in a line - the full path of each
entry (a Tajo directory) to be in the search path in lookup order. The entries in the search path must be spelled in the
Tajo syntax (where `>` is the directory separator) and start with the volume (either `<User>` or `<>`).     
For example, if the OS base directory `smalltalk-root` contains the directories `Smalltalk` and `Goodies`,
the following `;searchpath.txt` may be used:

```
<>Smalltalk
<User>Goodies
<whatever>docs
<User>sources
<user>
```

So looking up a file will first search in `<User>Smalltalk` (effectively `smalltalk-root/Smalltalk`), then
in `<User>Goodies` (which is `smalltalk-root/Goodies`) and finally in `<User>` (aka `smalltalk-root`).
The following entries of the file are ignored by the emulation: `<whatever>docs` (only the volume `<User>` is allowed) and
`<User>sources` (no corresponding real directory).

The first entry in the search path has a special importance, as it is there that new files are created if
only the filename is given or used (e.g. when filing out a Smalltalk item). 

The "search path tool" from the Smalltalk background menu allows to manipulate the active search path. Any file
operations in Smalltalk on the Tajo file system are synchronous, i.e. the file manipulation occurs directly
in the OS file system, no matter if it is a file I/O (especially a write), a rename or a delete.

The search path definition file is located in the OS base directory for the Tajo volume, in fact it defines the containing
directory to be the volume root:

- when ST80 is started for an Smalltalk image file, it first checks for a file with the extension `.sources` or
`.changes` besides it.

- if no such file is found, no Tajo file system will be emulated and the image is run stand alone without file system
  (with known restrictions: no snapshots)

- if such a file is present, the file `;searchpath.txt` is looked for in the image's directory up to 3 directory
  levels upwards
  
- if a `;searchpath.txt` is found, the directory containing this file is used as root volume with the search
  path defined in that file.
  
- if no `;searchpath.txt` is found, the directory containing the image is used as root volume and a search
  path consisting only of `<User>` is initially used.

The Tajo file system emulation caches the names and paths of all files in the volume at startup of ST80. This means that
files added or renamed later below the volume root directory at OS level will not be visible. Best is not to touch the
files and directories below the root directory as long as ST80 is running and using it.

Most filing operations from inside the Smalltalk environment are supported, however renaming or deleting a directory
is only possible if it is empty.

Before a new snapshot of the Smalltalk environment is created, the Tajo file with the snapshot name (as given in the prompter
with extension `.im`) is looked up in the search path by the Smalltalk system, creating a new snapshot file in the
first search path entry if the snapshot file is not found. The memory image is then written into the real OS file 
corresponding to the Tajo file, overwriting any content present. Before overwriting with the new snapshot, ST80 first
backups the current snapshot file to a file with same name and the file creation timestamp appended. For example
the backup for a `snapshot.im` file may be named `snapshot.im;2020.02.17_20.17.12.385`,
while the snapshot will again be named `snapshot.im`. The backup files will be located besides the image
file.

Remarks:

- when creating an image snapshot, the DV6 version has the same asymetric behavior as version 2: changing the name
  proposed in the prompter will create the image file with the new name and switch to a changes file matching this new
  name. However keeping the proposed default name ("snapshot") if a different name was used before will only change the
  name of the image file but Smalltalk will still continue to use the previous changes file with the old name.    
  For this reason and as disk space is no longer a restriction these days, it could be a good practice to stick with the
  default snapshot name and copy the whole root directory for the Tajo volume to a new location (preserving the default
  snapshot name) when a new snapshot branch is necessary.

- there is a bug in Smalltalk related to the _search path tool_ found in the desktop background menu: the Smalltalk
  system refreshes the directory lists in open _search path tool_ viewers in some situations but fails in doing so,
  opening a "Message not understood: update" notifier.    
  This is annoying (but not a real problem) during interactions, but a real show stopper if it happens when restarting
  an image snapshot that had the search path tool opened: as Smalltalk minimizes the display height to reduce heap space
  before writing the snapshot, the "Message not understood: update" notifier holds the execution of the restart sequence
  before the screen size is restored, so the screen stays in the minimal size, preventing any meaningful interaction with
  the system and thus making it useless.    
  Therefore:
  
  -- **never** snapshot the Smalltalk environment while the _search path tool_ opened (not even collapsed)
  
  -- a good practice is to close the _search path tool_ directly after using it (which is seldom necessary anyway)

### A tour through the ST80 sample environments

Besides the ST80 source code and the executable jar `st80vm.jar`, the Gitub project also contains the archive
`sample-env.zip` containing a ready to run Smalltalk-80 sample environment for each of Version 2 and DV6.

After unpacking the archive, the new subdirectory `sample-env` contains the following items:

- the subdirectory `1186-dv6` with the files for a sample Smalltalk-80 DV6 environment

- the subdirectory `1186-analyst` with the files for a sample Smalltalk-80 Analyst-1.2 environment

- the subdirectory `alto-v2` with the files for a sample Smalltalk-80 Version 2 environment
  
- a set of scripts to simplify running ST80:

  -- `st80.cmd` resp. `st80.sh` to run ST80 and specify the environment and options on the command line
  
  -- `st80_dv6.cmd` resp. `st80_dv6.sh` to directly run the DV6 sample environment including all options,
     specifically for setting the western european time zone
  
  -- `st80_analyst.cmd` resp. `st80_analyst.sh` to directly run the Analyst-1.2 sample environment including
     all options, specifically for setting the western european time zone
  
  -- `st80_v2.cmd` resp. `st80_v2.sh` to directly run the Version 2 sample environment including all options,
     specifically for adjusting the GMT time so Smalltalk shows western european time
  
  -- `st80vm.jar` (the executable jar for ST80)

#### Sample Smalltalk-80 DV6 environment

The directory `1186-dv6` contains the items for an emulated Tajo volume for the Smalltalk-80 DV6 sample
environment with the following items:

- the subdirectory `Smalltalk` with the files from the original floppy set:

  -- `snapshhot.im` (renamed from the image file `ST80-DV6.im`)
  
  -- `ST80-DV6.changes` and `ST80-DV6.initialChanges`
  
  -- `ST80-DV6.sources`
  
- the subdirectory `Smalltalk/Goodies` with the files from the "Goodies" floppy, mainly pictures (`*.form`),
  samples Smalltalk classes and code (`*.st`, `*.workspace`)
  
- the search path definition file `;searchpath.txt` for the Tajo volume, defining the following search path:    
  `<>Smalltalk`    
  `<>Smalltalk>Goodies`

For a quick start with Smalltalk-80 DV6, enter the `sample-env` directory and enter `st80_dv6` resp.
`./st80_dv6.sh` in a command shell; the following window should open (here on Windows):

![ST80 in sample-env running the DV6 snapshot.im](st80x-snapshot-dv6-in-sample-env.png)

#### Sample Smalltalk-80 DV6 + Analyst-V1.2 environment

The directory `1186-analyst` contains the items for an emulated Tajo volume for the Smalltalk-80 Analyst-1.2 sample
environment with the following items:

- the subdirectory `system` with the files from the original floppy set:

  -- `Analyst.im` (converted from the original image from the floppy disk set)
  
  -- `Analyst.im-original` (the original image file, not usable with ST80)
  
  -- `Analyst.changes` and `Analyst-DV6.initialChanges`
  
  -- `Analyst.sources` and `ST80-DV6.sources`
  
- the subdirectory `data` with the Analyst sample/demo files from the original floppy set
  
- the search path definition file `;searchpath.txt` for the Tajo volume, defining the following search path:    
  `<>data`    
  `<>system`

Warning:    
running the converted Analyst-1.2 image with ST80 is possible, but the memory conditions are tighter as for other Version 2
or plain DV6, as ST80 supports "only" the Stretch memory model whereas the original image expects the additional LOOM feature.
So out-of-memory problems are probable if using more advanced features of Analyst.

For a quick start with Smalltalk-80 Analyst-1.2, enter the `sample-env` directory and enter `st80_analyst` resp.
`./st80_analyst.sh` in a command shell; the ST80 screen should be all gray and moving the mouse will show the logon
page. The following users are predefined:

Name|Password
----|--------
analyst|analyst
demo|demo
system|system

The following screenshot shows a session after logon as user `system` and opening a few Analyst specific tools
(here on Linux):

![Analyst-1.2 screenshot](st80x-snapshot-analyst-in-sample-env.png)


#### Sample Smalltalk-80 Version 2 environment

The directory `alto-v2` contains the following files for the Smalltalk-80 Version 2 sample environment:

- the subdirectory `archive.org` with a copy of the Smalltalk-80 tape as found at the `archive.org` website

- the subdirectory `altodisk-files` containing files for creating an Alto disk image file from scratch. These are

  -- the original `Smalltalk-80.sources` file from the Smalltalk-80 tape,
  
  -- a handcrafted initial `Smalltalk-80.changes`,
  
  -- a set of pictures (`*.form`) taken from the Smalltalk-80 DV6 floppies for Xerox 1186 / 6085
     
  -- scripts for creating the minimal Alto disk image (`build-snapshot-disk.cmd/.sh`) as well as an Alto disk
    including the `*.form` pictures (`build-snapshot-disk-with-forms.cmd/.sh`)

- a data file set with base filename `snapshot` consisting of the original Smalltalk-80 Version 2 memory image (renamed
  from `VirtualImage` to `snapshot.im`) and an Alto disk Image including the pictures; this environment was started,
  connected with the Alto disk (see above), the display resized to 1024x808, the original viewers reshaped to use the larger
  display and then a new snapshot of the environment was taken.
  
- the archive `snapshot-2020.02.02_13.23.49.204.zip` created by the snapshot action and containing the original state
  of the Smalltalk-80 environment (i.e. unconnected to the Alto disk, display size 640x480 as delivered originally)
  
For a quick start with Smalltalk-80 V2, enter the `sample-env` directory and enter `st80_v2` resp.
`./st80_v2.sh` in a command shell; the following window should open (here on Windows):

![ST80 in sample-env running the V2 snapshot.im](st80x-snapshot-in-sample-end.png)

#### Shutting down the Smalltalk-80 versions

To shut down the Smalltalk-80 Version 2 or DV6 environments, move the mouse to a free place on the gray background and press the
middle mouse button.
The following command menu opens, select _Quit_ (probably already under the mouse position and therefore selected), which
opens the follow-up menu for selecting how to leave the system (where _Save_ means to create a snapshot first):

![ST80 leaving the system](st80x-leaving-the-system.png)

For any further explorations, the "Orange book" (see the Bibliography section) will be useful to learn how to use and program with
the Smalltalk-80 environment.

The Analyst system has moved the same options to a submenu for the middle button (Smalltalk..Session) and also allows to
leave the Analyst with "Log out":

![Analyst leaving the system](st80x-leaving-the-system-analyst.png) 

### Running ST80

##### Requirements

A Java 8 (or newer) runtime on a decent window system is needed to run ST80.

Having a hardware 3-button mouse button attached to system is recommended, as Smalltalk-80 is very mouse centric compared to
modern user interfaces and defines interactions with the UI objects in terms of 3 mouse keys.

Smalltalk-80 expects a standard english keyboard, so any international keyboard should be able to generate
the necessary input. However some specific key mappings should be known:

- the left arrow (assignment) is equivalent to the '`_`' key

- the up arrow (return value from message) is equivalent to the '`^`' key

- the return or enter key generates the CR code (carriage return, the line-end code for Smalltalk-80)

- the _INS_ key (_Einfg_ on a german keyboard) generates the LF code (linefeed)

- some non-ASCII keys on a german keyboard are mapped to the key at the same place on an US-english keyboard
  (e.g. '`ö`' becomes '`[`', '`ä`' becomes '`]`' etc.)

##### Invoking ST80

The file `st80vm.jar` is an executable jar which runs the ST80 interpreter, so ST80 can be invoked
directly with:

```
java -jar st80vm.jar ...
```
The scripts `st80.cmd` and `st80.sh` (for Windows resp. Unixoids) in the `sample-env` archive/directory simplify the
program invocation.

ST80 has the following program parameters:

- `imagename[.im]` _required_    
  the name of the Smalltalk image to run. The extension `.im` of the image file can be omitted on the command line,
  the image file must however have this extension.    
  Depending on the files present besides the image, ST80 will decide which environment to use, checking first for an Alto
  and then for a Tajo environment:
  
  -- If a file with extension `.dsk` matching the image filename is present, then an Alto environment is assumed
     where _imagename_ specifies the name of the name on the data file set, consisting of the required virtual memory
     image, the optional Alto disk image and possibly the delta file for the Alto file system.
     
  -- If a file with extension `.sources` or `.changes` matching the image filename is present, then ST80
     assumes a Tajo enviroment and looks for a file `;searchpath.txt` in the same or up to 3 parent directories;
     if the searchpath file is found, then the Tajo root volume is the directory of the searchpath file, else the
     root volume is the directory of the image file.
  
  -- if none of the above, the image is run without an attached file system

- `--statusline` _optional_    
  add the status line at the bottom of the ST80 application window
  
- `--stats` _optional_    
  issue some statistical data collected during the session when ST80 ends normally
  
- `--timeadjust:nn` _optional_    
  add _nn_ minutes to GMT to correct time in Smalltalk (positive values for east).    
  Background: the Smalltalk-80 Version 2 image reads GMT from the virtual machine, but hardcoded-ly
  adjusts local time to California time (i.e. Xerox-PARC time) and uses this for all time operations.
  This parameter modifies the GMT value generated to get a different local time computation, the value 540
  produces western european non-DST local time.    
  (this option should only be used when running a Version 2 image)

- `--tz:offsetMinutes[:dstFirstDay:dstLastDay]` _optional_    
  set the timezone and daylight saving parameters for a DV6 image    
  as the DV6 image uses the local time parameters of the XDE/Tajo environment, this option allows to set
  the local time offset to GMT in minutes (so positive value are in the east, e.g. 60 for western european time)
  and the first and last daylight saving dates (given as day of the year, with the time change occuring in the
  night to the next sunday following the given day).    
  The defaults are "no timezone offset", "last sunday in march" and "last sunday in october". The option can
  be given with the time offset alone or with all 3 values.
  
The status line added to the ST80 window with the `--statusline` parameter is present in the above
screenshots. The values in the status line are updated about 3 times per second. `uptime` is the runtime
of the program in seconds, `insns` is the average execution rate for Smalltalk instructions since the
last status update in 1000 instructions per second, the `msg` figure is the average message send rate
in 1000 messages per second. Both average values depend on the current activity in the Smalltalk environment
and vary over time as ST80 tries to reduce real CPU usage by throttling the execution if the Smalltalk
environment seems to be idle (see below). `free ot` gives the number of free object table entries,
`free heap` gives the free heap space in 1024 words units. `gc` is the number of compacting garbage
collections since program start.

An ST80 session is terminated _normally_ in the following ways:

- through the middle-button menu on the Smalltalk background, selecting the _Quit_ command and then
  either _Save, then quit_ (first writing a snapshot) or _Quit, without saving_    
  in both cases, changes to the Alto disk are saved to the delta file
  
- by closing the ST80 main window, which opens the following confirmation dialog, allowing to continue
  with the ST80 session, to terminate the session with saving the Alto disk changes to the delta file
  or to quit without saving anything:    
  ![ST80 close confirmation dialog](st80-close-confirmation.png)
  
- if ST80 encounters an internal error (resulting in a Java stacktrace written to the console),
  the ST80 session is aborted, but the changes to the Alto disk are written to the delta file (if possible),
  so lost modifications may be recovered with the Smalltalk mechanisms

The ST80 session can be terminated _abnormally_ with Ctrl-C in the console where ST80 was started or
by killing the ST80 Java process: in this case no changes to the Alto disk are saved.

When the option `--stats` is given, ST80 writes some statistical data to the console when the
program terminates normally:

```
## terminating ST80, cause: primitiveQuit invoked
uptime: 404826 ms , total instructions: 1027015275 (avg: 2536k/s , max: 56182k/s)
messages as :: smalltalk: 85154708 , primitive: 56667506 , specialPrimitive: 85743928 (total: 227566142 , avg: 562k/s , max: 4468k/s)
method cache :: hits: 130043293 , fails: 11725179 , resets: 5 (total: 141768472 , hit-%: 91,0)
```

The `avg` values give the corresponding average over the runtime of the program, the `max` values give
the respective peak value issued in the status line of the main window (be it shown or not).

### Creating and manipulating Alto disk images

ST80 brings a built-in utility program for handling Alto disk images (which may also be useful when working
with Alto emulators like ContrAlto or Salto).

The utility is invoked with the scripts `altodisk.cmd` or `altodisk.sh` (for Windows resp. Unixoids),
which run the ST80 class `dev.hawala.st80vm.alto.AltoFile`.

The first command line parameter must be one of:

- `--create`    
  start a new formatted Alto disk
  
- `image-filename`    
  read and work with the specified Alto disk image, the filename must be given with the extension (usually `.dsk`),
  but also loads the possibly available deltas to the disk (with extension `.dsk.delta`)
  
All subsequent operations on the Alto disk image are performed on an in-memory copy of the image file, requiring
to save the changes as last action for really changing the disk image.    
Working with the disk content is specified by a sequence of the following subcommands on the command line:

- `--list`   
  list the root directory of the disk with filename, byte size and create, write, read timestamps
  
- `--scan`    
  scan the disk for leader pages and list the file name and size for files claiming to be in the
  root directory
  
- `--import filename alto-filename`    
  copy the content of the local file _filename_ to the Alto disk giving it the _alto-filename_; this is
  a binary copy operation without character set or line-end conversion, meaning that text files should be
  ASCII text and must have a single CR (0x0D) as line-end to be correctly readable in the Smalltalk environment
  
- `--export alto-filename filename`    
  copy the content of the file _alto-filename_ on the Alto disk to the local file _filename_; this is
  a binary copy operation without character set or line-end conversion
  
- `--rm alto-filename`    
  delete the file _alto-filename_ on the Alto disk
  
- `--ren alto-fn-old alto-fn-new`    
  rename the file _alto-fn-old_ on the Alto disk to the new name _alto-fn-new_
  
- `--save`    
  write back the in-memory Alto disk to the disk image file that was originally loaded, this subcommand
  may not be used if a new disk was created with the initial subcommand `--create`    
  remark: if the disk image loaded originally had a delta file, this delta file is removed, as the new
  disk image is written with all changes merged
  
- `--saveas filename`    
  write the in-memory Alto disk to the file _filename_ on the local file system; this subcommand must
  be used if `--create` was given initially, but can also be used to create a copy of an existing
  Alto disk image
  
The following example shows how to create a minimal Alto disk name _altodisk.dsk_ for the Smalltalk-80 Version 2
image (assuming that the files _Smalltalk-80.sources_ and _Smalltalk-80.changes_ are present on the local disk):

```
altodisk --create \
  --import Smalltalk-80.sources Smalltalk-80.sources \
  --import Smalltalk-80.changes Smalltalk-80.changes \
  --list \
  --saveas altodisk.dsk

--import ::

--import ::

--list ::
Name                                     Bytes   Created             Written             Read
---------------------------------------- ------- ------------------- ------------------- -------------------
DiskDescriptor                               642 2020.02.01 15.41.35 2020.02.01 15.41.35 1970.01.01 01.00.00
Smalltalk-80.changes                          46 2020.02.01 15.41.35 2020.01.17 08.34.54 1970.01.01 01.00.00
Smalltalk-80.sources                     1411072 2020.02.01 15.41.35 2015.09.26 20.02.40 1970.01.01 01.00.00
SysDir                                     20480 2020.02.01 15.41.35 2020.02.01 15.41.35 1970.01.01 01.00.00

--saveas ::

```
The subcommands are confirmed with the subcommand option processed, each possibly followed by message lines (error
messages or the file lists for `--list` or `--scan`)

### Converting _type 5_ images (DV6+LOOM) for ST80

The Java main class

```
dev.hawala.st80vm.ConvertType5Image
```

in `st80vm.jar` allows to convert a _type 5_ image saved by the Smalltalk-80 DV6 for 1186 runtime (Stretch+LOOM memory
model) to a _type 1_ image (Stretch only memory model) that can be used by ST80, provided the image content does
not depend on the LOOM extension, that is fitting in the restricted 16 bit Stretch environment (less than 48k active objects,
less than 1 MWords of used heap).

This class is run as command line program and requires as 1st argument the name of the source image file. The optional 2nd
argument is the name of the target image file. If omitted, the target file will be the source file name with `.converted.im`
appended.


### Remarks and limitations

- ST80 supports arbitrary sizes of the Smalltalk display (see below for the limits). An automatic resizing of
  the ST80 application window occurs whenever the display size is changed in the running Smalltalk system with
  `DisplayScreen displayExtent:`_width_`@`_height_

- before writing a snapshot, the Smalltalk system reduces the display bitmap height to a minimum in the
  intent of reducing the heap memory size (in conjunction with a compacting garbage collection) and restores
  the display bitmap to the previous size after writing the snapshot.    
  So the ST80 window seems to collapse for a short time before going back to the original size, as ST80 
  follows the geometry controlled by the Smalltalk environment...    
  ...nothing to worry about (these display size changes were probably only noticeable as slight flickering when
  Smalltalk originally ran on a physical screen that simply cannot shrink and grow)

- Smalltalk-80 by the Bluebook is a 16 bit system!    
  This information seems innocent, but this limits the maximum size of array objects to 65533 16-bit-words (max(16-bit)
  minus 2 words for the length and class). This again seems not so important, but as the Smalltalk display bitmap is an
  array object, this limits the maximum display geometry: if `((pixel-width + 15) / 16) * pixel-height`
  is more than 65533 when sending the `displayExtent:` message, then the DisplayScreen object will not deny
  the request, but it will prevent disaster by making the display only a few pixels high.    
  As the ST80 window follows this display size, it will be impossible to continue working in this session, as
  any meaningful command in context menus will not be accessible: only the last menu item is visible, so _Quit_
  can possibly be selected in the background menu, but the only visible choice in the follow-up menu is _Continue_).    
  So be careful when changing the screen sizes (e.g. for a 1280 pixel width, the maximum usable height is 819)
  
- ST80 tries to reduce the load on the real CPU when the Smalltalk environment appears to be idle. Although there is
  no explicit or specified idle state in the Bluebook specification, the `yield` message sent to the `Processor`
  object seems to be a valid indication for an idle condition of the Smalltalk system.    
  Therefore the ST80 interpreter enters a 10ms sleep phase for each `yield` message sent, with any incoming mouse or
  keyboard event resuming the interpreter. Depending on the focused viewer and the cursor location (inside this viewer
  or not), this reduces the load on the CPU core running the interpreter from 100% to about 8% to 25%.    
  However not all views (resp. controller) use the `yield` message to give other processes a chance to run, so CPU
  load stays at 100% on one core for some applications (like the Form editor) or in other situations (e.g. an open context menu). 

- ST80 does not support the programmatic cursor placement by Smalltalk methods through primitives 91 (cursorLocPut)
  and 92 (cursorLink), as the ST80 UI simply maps the Smalltalk cursor to the real cursor of the native window system
  of the underlying OS (Linux, Windows etc.).    
  This may restrict the look&feel of some Smalltalk applications.

### Bibliography

The following files and documents available in the internet were useful for creating ST80:

- [image.tar.gz](https://archive.org/download/smalltalk-80/image.tar.gz)    
  the archived Smalltalk-80 system found at the [archive.org](https://archive.org/) website

- [1186_Smalltalk-80_DV6_Dec87.zip](http://bitsavers.org/bits/Xerox/1186/1186_Smalltalk-80_DV6_Dec87.zip)    
  the Smalltalk-80 installation floppy images for Xerox 1186/6085 workstations found at the [Bitsavers](http://bitsavers.org/) website

- [1186_The_Analyst_V1.2_Dec87.zip](http://bitsavers.org/bits/Xerox/1186/1186_The_Analyst_V1.2_Dec87.zip)    
  the Smalltalk-80 "The Analyst-V1.2" floppy images for Xerox 1186/6085 workstations found at the [Bitsavers](http://bitsavers.org/) website,    
  documentation for "The Analyst V1.2" can be found at [Bitsavers](http://bitsavers.org/pdf/xerox/xsis/)

- [Smalltalk-80: the Language and its Implementation](http://stephane.ducasse.free.fr/FreeBooks/BlueBook/Bluebook.pdf)   
  Adele Goldberg and David Robson, 1983   
  (the "Bluebook" repeatedly mentioned here and in the sources)
  
- [Smalltalk-80, The Interactive Programming Environment](http://stephane.ducasse.free.fr/FreeBooks/TheInteractiveProgrammingEnv/TheInteractiveProgrammingEnv.pdf)    
  Adele Goldberg, 1983   
  (the "Orange book")
  
- [Smalltalk-80, Bits of History, Words of Advice](http://stephane.ducasse.free.fr/FreeBooks/BitsOfHistory/BitsOfHistory.pdf)   
  Glenn Krasner, 1983    
  (the "Green book")
  
- [AltoHWRef.part2.pdf](http://bitsavers.org/pdf/xerox/alto/AltoHWRef.part2.pdf)    
  Xerox, 1978,1979    
  (for the implementation of the Alto I/O interface to the Alto disk image)
  
- [aar.c](http://bitsavers.org/bits/Xerox/Alto/tools/aar.c)    
  L. Stewart 1992,1993    
  (C program to list and extract files from Alto disk images available at Bitsavers, useful for learning about the file system data structures of an Alto disk)

- [Dlion_stklst.pdf](http://bitsavers.org/pdf/xerox/8010_dandelion/smalltalk/Dlion_stklst.pdf)    
  (a collection of microcode and mesa listings found at the Bitsavers website, giving hints in pages 10 to 12 for understanding
  the _Stretch_ mode modification to the Bluebook)

### History

- 2025-04-25    
  Fix for SmallInteger primitives "primitiveDiv" and "primitiveQuo" (Issue #5)

- 2021-06-10    
  Fixed 2 errors in transliteration of Smalltalk-VM-pseudocode from the Bluebook to Java 

- 2020-10-29    
  Added conversion of _type 5_ images (Stretch+LOOM memory model) to _type 1_ images (Stretch memory model)    
  Added vendor-specific primitive dummies for 1186 allowing to run the (converted) Analyst-1.2 image

- 2020-02-20 ... 2020-02-21    
  Fixes for a DV6-related bug when saving the content from a file list (lower pane via 'put')    
  Fix for the flickering mouse pointer on the right edge of scrollbars 

- 2020-02-19    
  new: added support for Smalltalk-80 DV6 for 1186 / 6085 workstation (Stretch object memory model)    
  bugfix: prevent divide by zero when creating the status line, crashing at startup on (very) fast machines    
  bugfix: handle combination rule for BitBlt outside [0..15] (Pen now paints black instead of white with default value 16)

- 2020-02-02    
  initial commit to Github    
  support for Smalltalk-80 Version 2 for the Alto

### License

ST80 is released under the BSD license, see the file [License.txt](License.txt).

### Disclaimer

All product names, trademarks and registered trademarks mentioned herein and in the
source files for the ST80 program are the property of their respective owners.