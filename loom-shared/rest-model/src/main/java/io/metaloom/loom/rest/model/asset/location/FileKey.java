package io.metaloom.loom.rest.model.asset.location;

public class FileKey {

	private Long inode;
	private Long stDev;
	private Long edate;
	private Long edateNano;

	public FileKey() {
	}

	public FileKey(long inode, long stDev, long edate, long edateNano) {
		this.inode = inode;
		this.stDev = stDev;
		this.edate = edate;
		this.edateNano = edateNano;
	}

	public Long getInode() {
		return inode;
	}

	public FileKey setInode(Long inode) {
		this.inode = inode;
		return this;
	}

	public Long getStDev() {
		return stDev;
	}

	public FileKey setStDev(Long stDev) {
		this.stDev = stDev;
		return this;
	}

	public Long getEdate() {
		return edate;
	}

	public FileKey setEDate(Long edate) {
		this.edate = edate;
		return this;
	}

	public Long getEdateNano() {
		return edateNano;
	}

	public FileKey setEDateNano(Long edateNano) {
		this.edateNano = edateNano;
		return this;
	}

}
