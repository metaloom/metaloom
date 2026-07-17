package io.metaloom.cortex.node.hash;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class HashNodeOptions extends AbstractNodeOptions<HashNodeOptions> {

	public static final String KEY = "hash";

	private boolean md5 = true;

	private boolean sha512 = true;

	private boolean sha256 = true;

	private boolean chunkHash = true;

	@Override
	protected HashNodeOptions self() {
		return this;
	}

	public boolean isMD5() {
		return md5;
	}

	public HashNodeOptions setMD5(boolean flag) {
		this.md5 = flag;
		return this;
	}

	public boolean isChunkHash() {
		return chunkHash;
	}

	public HashNodeOptions setChunkHash(boolean chunkHash) {
		this.chunkHash = chunkHash;
		return this;
	}

	public boolean isSHA256() {
		return sha256;
	}

	public HashNodeOptions setSHA256(boolean flag) {
		this.sha256 = flag;
		return this;
	}

	public boolean isSHA512() {
		return sha512;
	}

	public HashNodeOptions setSHA512(boolean flag) {
		this.sha512 = flag;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// At least one hash algorithm must be enabled
		if (!md5 && !sha256 && !sha512 && !chunkHash) {
			errors.add("At least one hash algorithm must be enabled (md5, sha256, sha512, or chunkHash)");
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
