package io.metaloom.cortex.node.hash;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options shared by all four hash nodes ({@code md5}, {@code sha256}, {@code sha512},
 * {@code chunk-hash}), which are configured together under the single {@value #KEY} key.
 *
 * <p>
 * The four algorithm flags are worker-scoped switches - each node reads only its own from
 * {@code isProcessable} - and no descriptor has ever advertised them, so they stay out of the
 * contract. Surfacing them would put all four switches on all four edit forms.
 * </p>
 */
public class HashNodeOptions extends AbstractNodeOptions<HashNodeOptions> {

	public static final String KEY = "hash";

	@ParamDoc(hidden = true)
	private boolean md5 = true;

	@ParamDoc(hidden = true)
	private boolean sha512 = true;

	@ParamDoc(hidden = true)
	private boolean sha256 = true;

	@ParamDoc(hidden = true)
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
