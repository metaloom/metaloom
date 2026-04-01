package io.metaloom.loom.client.http.impl;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.http.LoomClientHttpRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.error.LoomHttpClientException;
import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.reactivex.rxjava3.core.Single;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Request.Builder;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LoomClientRequestImpl<T extends RestResponseModel<T>> implements LoomClientHttpRequest<T> {

	public static final Logger log = LoggerFactory.getLogger(LoomClientRequestImpl.class);

	private final LoomHttpClient loomClient;

	private final OkHttpClient okClient;

	private final okhttp3.HttpUrl.Builder urlBuilder;

	private final Class<T> responseClass;

	private RequestBody body;

	private final String method;

	public LoomClientRequestImpl(String method, String path, LoomHttpClient loomClient, OkHttpClient client,
		Class<T> responseClass, RequestBody requestBody) {
		this.loomClient = loomClient;
		this.okClient = client;
		this.method = method;
		this.urlBuilder = createUrlBuilder(path);
		this.responseClass = responseClass;
		this.body = requestBody;
	}

	private okhttp3.HttpUrl.Builder createUrlBuilder(String path) {
		return new HttpUrl.Builder()
			.scheme(loomClient.getScheme())
			.host(loomClient.getHostname())
			.port(loomClient.getPort())
			.addEncodedPathSegments(loomClient.getPathPrefix())
			.addEncodedPathSegments(LoomHttpClient.API_V1_PATH)
			.addPathSegments(path);
	}

	private Request build() {
		Builder builder = new Request.Builder().url(urlBuilder.build());
		builder.method(method, body);
		String token = loomClient.getToken();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return builder.build();
	}

	@Override
	public JsonNode json() throws LoomHttpClientException {
		return executeSyncJson(build());
	}

	@Override
	public Single<T> async() {
		return executeAsync(build());
	}

	@Override
	public LoomClientHttpRequest<T> addQueryParameter(String key, String value) {
		urlBuilder.addQueryParameter(key, value);
		return this;
	}

	@Override
	public T sync() throws LoomHttpClientException {
		return executeSync(build());
	}

	/**
	 * Execute the request synchronously.
	 *
	 * @param request
	 * @return Response body text
	 * @throws HttpErrorException
	 */
	private String executeSyncPlain(Request request) throws LoomHttpClientException {
		try (Response response = okClient.newCall(request).execute()) {
			ResponseBody body = response.body();
			String bodyStr = "";
			if (body != null) {
				try {
					bodyStr = body.string();
				} catch (Exception e) {
					throw new LoomHttpClientException("Error while reading body", e);
				}
			}
			if (!response.isSuccessful()) {
				if (log.isDebugEnabled()) {
					log.debug("Failed request with code {" + response.code() + "} and body:\n" + bodyStr);
				}

				throw new LoomHttpClientException("Request failed {" + response.message() + "}", response.code(), response.message(), bodyStr);
			}

			return bodyStr;
		} catch (IOException e1) {
			throw new LoomHttpClientException("Error while excuting request", e1);
		}
	}

	/**
	 * Execute the request synchronously.
	 *
	 * @param request
	 * @throws HttpErrorException
	 */
	private void executeSyncNoResponse(Request request) throws LoomHttpClientException {
		try (Response response = okClient.newCall(request).execute()) {
			if (response.isSuccessful()) {
				return;
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Failed request with code {" + response.code() + "}");
				}

				ResponseBody body = response.body();
				String bodyStr = "";
				if (body != null) {
					try {
						bodyStr = body.string();
					} catch (Exception e) {
						throw new LoomHttpClientException("Error while reading body", e);
					}
				}

				throw new LoomHttpClientException("Request failed {" + response.message() + "}", response.code(), response.message(), bodyStr);
			}
		} catch (IOException e1) {
			throw new LoomHttpClientException("Error while excuting request", e1);
		}
	}

	private LoomBinaryResponse executeSyncBinary(Request request) throws LoomHttpClientException {
		try {
			Response response = okClient.newCall(request).execute();
			return new LoomBinaryResponseImpl(response);
		} catch (IOException e1) {
			throw new LoomHttpClientException("Error while excuting request", e1);
		}
	}

	@SuppressWarnings("unchecked")
	public T executeSync(Request request) throws LoomHttpClientException {
		if (NoResponse.class.isAssignableFrom(responseClass)) {
			executeSyncNoResponse(request);
			return null;
		} else if (RestModel.class.isAssignableFrom(responseClass)) {
			Class<? extends RestModel> r = (Class<? extends RestModel>) responseClass;
			String bodyStr = executeSyncPlain(request);
			if (log.isDebugEnabled()) {
				log.debug("Response JSON:\n" + bodyStr);
			}
			return (T) LoomJson.parse(bodyStr, r);
		} else if (LoomBinaryResponse.class.equals(responseClass)) {
			return (T) executeSyncBinary(request);
		} else {
			throw new RuntimeException("Unsupported response class encountered. Got: " + responseClass.getName());
		}
	}

	/**
	 * Execute the request synchronously.
	 *
	 * @param request
	 * @return Parsed response object
	 * @throws HttpErrorException
	 */
	private JsonNode executeSyncJson(Request request) throws LoomHttpClientException {
		try {
			String bodyStr = executeSyncPlain(request);
			return LoomJson.toJson(bodyStr);
		} catch (JsonProcessingException e) {
			throw new LoomHttpClientException("Error while excuting request", e);
		}
	}

	/**
	 * Execute the request asynchronously.
	 *
	 * @param request
	 * @return Single which yields the response data
	 */
	private Single<T> executeAsync(Request request) {
		return Single.create(sub -> {
			Call call = okClient.newCall(request);
			sub.setCancellable(call::cancel);
			call.enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					// Don't call the onError twice. Canceling will trigger another error.
					if (!"Canceled".equals(e.getMessage())) {
						sub.onError(e);
					}
				}

				@Override
				public void onResponse(Call call, Response response) {
					if (LoomBinaryResponse.class.equals(responseClass)) {
						sub.onSuccess((T) new LoomBinaryResponseImpl(response));
						return;
					}

					try (ResponseBody responseBody = response.body()) {
						ResponseBody body = response.body();
						String bodyStr = "";
						if (body != null) {
							try {
								bodyStr = body.string();
							} catch (Exception e) {
								sub.onError(new LoomHttpClientException("Error while reading body", e));
								return;
							}
						}
						if (!response.isSuccessful()) {
							sub.onError(new LoomHttpClientException("Request failed", response.code(), response.message(), bodyStr));
							return;
						}
						if (RestModel.class.isAssignableFrom(responseClass)) {
							Class<? extends RestModel> r = (Class<? extends RestModel>) responseClass;
							sub.onSuccess((T) LoomJson.parse(bodyStr, r));
						} else {
							throw new RuntimeException("Unsupported response class encountered. Got: " + responseClass.getName());
						}

					} catch (Exception e) {
						sub.onError(e);
					}
				}
			});
		});
	}

	@Override
	public LoomClientHttpRequest<T> self() {
		return this;
	}

}
