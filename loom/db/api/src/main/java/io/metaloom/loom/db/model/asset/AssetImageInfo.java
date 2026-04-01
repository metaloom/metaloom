package io.metaloom.loom.db.model.asset;

public interface AssetImageInfo extends AssetMediaInfo {

	String getDominantColor();

	Asset setDominantColor(String color);

}
