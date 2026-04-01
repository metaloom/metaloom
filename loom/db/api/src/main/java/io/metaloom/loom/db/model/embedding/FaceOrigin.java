package io.metaloom.loom.db.model.embedding;

import java.awt.Dimension;
import java.awt.Point;

public interface FaceOrigin {

	int faceNr();

	Point positition();

	Dimension size();

	long frame();
}
