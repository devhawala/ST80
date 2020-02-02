package dev.hawala.st80vm.ui;

public interface DisplayPane {
	
	void setCursor(short[] cursor, int hotspotX, int hotspotY);

	boolean copyDisplayContent(short[] mem, int start, int displayWidth, int displayRaster, int displayHeight, int firstLine, int lastLine);
}
