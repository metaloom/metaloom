package io.metaloom.loom.client.http;

import static io.metaloom.loom.client.http.LoomClientHttpRequest.DELETE;
import static io.metaloom.loom.client.http.LoomClientHttpRequest.GET;
import static io.metaloom.loom.client.http.LoomClientHttpRequest.PATCH;
import static io.metaloom.loom.client.http.LoomClientHttpRequest.POST;
import static io.metaloom.loom.client.http.LoomClientHttpRequest.PUT;

import java.io.InputStream;
import java.time.Duration;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.RestResponseModel;
import okhttp3.OkHttpClient;

public abstract class AbstractLoomOkHttpClient extends AbstractLoomClient {

	private OkHttpClient okClient;

	public AbstractLoomOkHttpClient(OkHttpClient okClient, String scheme, String hostname, int port, String pathPrefix, Duration connectTimeout, Duration readTimeout,
		Duration writeTimeout) {
		super(scheme, hostname, port, pathPrefix, connectTimeout, readTimeout, writeTimeout);
		this.okClient = okClient;
	}

	/**
	 * Return the used OK HTTP client.
	 * 
	 * @return
	 */
	public OkHttpClient getOkHttpClient() {
		return okClient;
	}

	@Override
	public void close() {
		// Not needed for OkClient
	}

	protected <T extends RestResponseModel<T>> LoomClientHttpRequest<T> postUploadRequest(String path, Class<T> responseClass,
		InputStream binaryData, String mimeType) {
		return LoomClientHttpRequest.createBinaryRequest(POST, path, this, okClient, responseClass, binaryData, mimeType);
	}

	/**
	 * Issue a {@code multipart/form-data} POST carrying one file part named {@code file}.
	 *
	 * @param <T>
	 *            response model type
	 * @param path
	 *            request path
	 * @param responseClass
	 *            expected response model
	 * @param file
	 *            the file to upload; its name is sent as the part filename
	 * @param mimeType
	 *            content type of the part, or null for {@code application/octet-stream}
	 * @param formFields
	 *            alternating name/value form fields; a null value skips the field
	 * @return the request
	 */
	protected <T extends RestResponseModel<T>> LoomClientHttpRequest<T> multipartRequest(String path, Class<T> responseClass, java.io.File file,
		String mimeType, String... formFields) {
		return LoomClientHttpRequest.createMultipartRequest(path, this, okClient, responseClass, file, file.getName(), mimeType, formFields);
	}

	protected LoomClientHttpRequest<LoomBinaryResponse> getDownloadRequest(String path) {
		return LoomClientHttpRequest.createDownloadRequest(GET, path, this, okClient, LoomBinaryResponse.class);
	}

	protected <T extends RestResponseModel<T>> LoomClientHttpRequest<T> deleteRequest(String path, Class<T> responseClass) {
		return LoomClientHttpRequest.createNoBodyRequest(DELETE, path, this, okClient, responseClass);
	}

	protected LoomClientHttpRequest<NoResponse> deleteRequest(String path) {
		return LoomClientHttpRequest.createNoResponseRequest(DELETE, path, this, okClient);
	}

	protected <T extends RestResponseModel<T>> LoomClientHttpRequest<T> getRequest(String path, Class<T> responseClass) {
		return LoomClientHttpRequest.createNoBodyRequest(GET, path, this, okClient, responseClass);
	}

	protected <T extends RestResponseModel<T>> LoomClientHttpRequest<T> postRequest(String path, RestRequestModel request, Class<T> responseClass) {
		return LoomClientHttpRequest.createJsonRequest(POST, path, this, okClient, request, responseClass);
	}

	protected <T extends RestResponseModel<T>> LoomClientHttpRequest<T> postRequest(String path, Class<T> responseClass) {
		return LoomClientHttpRequest.createNoBodyRequest(POST, path, this, okClient, responseClass);
	}

	protected <T extends RestResponseModel<T>> LoomClientHttpRequest<T> putRequest(String path, RestRequestModel request, Class<T> responseClass) {
		return LoomClientHttpRequest.createJsonRequest(PUT, path, this, okClient, request, responseClass);
	}

	protected <T extends RestResponseModel<T>> LoomClientHttpRequest<T> patchRequest(String path, RestRequestModel request, Class<T> responseClass) {
		return LoomClientHttpRequest.createJsonRequest(PATCH, path, this, okClient, request, responseClass);
	}
}
