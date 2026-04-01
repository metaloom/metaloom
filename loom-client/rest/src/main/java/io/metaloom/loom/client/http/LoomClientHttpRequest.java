package io.metaloom.loom.client.http;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.error.LoomHttpClientException;
import io.metaloom.loom.client.http.impl.LoomClientRequestImpl;
import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.reactivex.rxjava3.core.Single;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;

public interface LoomClientHttpRequest<T extends RestResponseModel<T>> extends LoomClientRequest<T> {

	public static final Logger log = LoggerFactory.getLogger(LoomClientHttpRequest.class);

	public static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");

	public static final String PUT = "PUT";
	public static final String PATCH = "PATCH";
	public static final String GET = "GET";
	public static final String DELETE = "DELETE";
	public static final String POST = "POST";

	/**
	 * Create request without payload and no response (e.g. delete requests)
	 * 
	 * @param <T>
	 * @param method
	 * @param path
	 * @param loomClient
	 * @param okClient
	 * @return
	 */
	public static LoomClientHttpRequest<NoResponse> createNoResponseRequest(String method, String path, LoomHttpClient loomClient,
		OkHttpClient okClient) {
		return new LoomClientRequestImpl<>(method, path, loomClient, okClient, NoResponse.class, null);
	}

	/**
	 * Create request without payload.
	 * 
	 * @param <T>
	 * @param method
	 * @param path
	 * @param loomClient
	 * @param okClient
	 * @param responseClass
	 * @return
	 */
	public static <T extends RestResponseModel<T>> LoomClientHttpRequest<T> createNoBodyRequest(String method, String path, LoomHttpClient loomClient,
		OkHttpClient okClient, Class<T> responseClass) {
		return new LoomClientRequestImpl<>(method, path, loomClient, okClient, responseClass, null);
	}

	public static LoomClientHttpRequest<LoomBinaryResponse> createDownloadRequest(String method, String path, LoomHttpClient loomClient,
		OkHttpClient okClient,
		Class<LoomBinaryResponse> responseClass) {
		return new LoomClientRequestImpl<>(method, path, loomClient, okClient, responseClass, null);
	}

	public static <T extends RestResponseModel<T>> LoomClientHttpRequest<T> createBinaryRequest(String method, String path, LoomHttpClient loomClient,
		OkHttpClient okClient, Class<T> responseClass, InputStream data, String contentType) {
		return new LoomClientRequestImpl<>(method, path, loomClient, okClient, responseClass, new RequestBody() {
			@Override
			public MediaType contentType() {
				return MediaType.get(contentType);
			}

			@Override
			public void writeTo(BufferedSink sink) throws IOException {
				try {
					sink.writeAll(Okio.source(data));
				} finally {
					data.close();
				}
			}
		});
	}

	/**
	 * Create request with payload.
	 * 
	 * @param <T>
	 * @param method
	 * @param path
	 * @param loomClient
	 * @param okClient
	 * @param request
	 * @param responseClass
	 * @return
	 */
	public static <T extends RestResponseModel<T>> LoomClientHttpRequest<T> createJsonRequest(String method, String path, LoomHttpClient loomClient,
		OkHttpClient okClient, RestRequestModel request, Class<T> responseClass) {
		String bodyStr = LoomJson.encode(request);
		if (log.isDebugEnabled()) {
			log.debug("Sending request: " + method + " " + path + "\n" + bodyStr);
		}
		RequestBody body = RequestBody.create(bodyStr, MEDIA_TYPE_JSON);
		return new LoomClientRequestImpl<>(method, path, loomClient, okClient, responseClass, body);
	}

	/**
	 * Returns a single which can be used to execute the request and listen to the result.
	 * 
	 * @return
	 */
	Single<T> async();

	/**
	 * Executes the request in a synchronized blocking way and returns the returned JSON data.
	 * 
	 * @return
	 * @throws LoomHttpClientException
	 */
	JsonNode json() throws LoomHttpClientException;

	/**
	 * Executes the request in a synchronized blocking way.
	 * 
	 * @return
	 * @throws HttpErrorException
	 */
	T sync() throws LoomClientException;

}
